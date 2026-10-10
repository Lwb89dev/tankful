package com.tankful.android.sync

import com.tankful.android.data.db.AppliedEventEntity
import com.tankful.android.data.db.ConflictEntity
import com.tankful.android.data.db.OutboxEntity
import com.tankful.android.data.db.PendingSnapshotEntity
import com.tankful.android.data.db.PublishedContentEventEntity
import com.tankful.android.data.db.QuarantineEntity
import com.tankful.android.data.db.SnapshotForkEntity
import com.tankful.android.data.db.SyncDao
import com.tankful.android.data.store.CatalogStore
import com.tankful.android.data.store.DeviceSettings
import com.tankful.android.data.store.GarageLog
import com.tankful.android.data.store.settingsOutbox
import com.tankful.android.json.canonicalJson
import com.tankful.android.json.sha256Hex
import com.tankful.android.json.toCanonicalString
import com.tankful.android.ledger.JudgementRow
import com.tankful.android.ledger.Logbook
import com.tankful.android.ledger.VehicleRow
import com.tankful.android.model.ExpenseOrigin
import com.tankful.android.model.SETTINGS_ENTITY_ID
import com.tankful.android.model.SYNC_SCHEMA
import com.tankful.android.model.TANKFUL_SNAPSHOT_KIND
import com.tankful.android.money.MAJOR_CURRENCIES
import com.tankful.android.quantity.MEASURE_IMPERIAL
import com.tankful.android.quantity.MEASURE_METRIC
import com.tankful.android.transfer.ExpenseContent
import com.tankful.android.transfer.IntervalJudgementContent
import com.tankful.android.transfer.MaintenanceContent
import com.tankful.android.transfer.RefuelContent
import com.tankful.android.transfer.SettingsContent
import com.tankful.android.transfer.VehicleContent
import com.tankful.android.transfer.restoreExpense
import com.tankful.android.transfer.restoreMaintenance
import com.tankful.android.transfer.restoreRefuel
import com.tankful.android.transfer.systemCategoryIds
import com.tankful.android.transfer.userSettings
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Singleton
class SyncEngine @Inject constructor(
    private val garage: GarageLog,
    private val catalog: CatalogStore,
    private val device: DeviceSettings,
    private val dao: SyncDao,
    private val signer: Nip55Signer,
    private val relays: RelayClient,
) {
    suspend fun publish() {
        if (!device.syncEnabled.first()) return
        val urls = relayUrls()
        if (urls.isEmpty()) return
        val allow = urls.filter { it.startsWith("ws://") }.toSet()
        val pubkey = device.signerPubkey.first() ?: return
        val packageName = device.signerPackage.first()
        enqueueSettings()
        for (row in dao.pendingOutbox().take(OUTBOX_BATCH)) {
            publishRow(row, urls, allow, pubkey, packageName)
        }
    }

    suspend fun pull(manual: Boolean) {
        if (!device.syncEnabled.first()) return
        val pubkey = device.signerPubkey.first() ?: return
        val urls = relayUrls()
        if (urls.isEmpty()) return
        val allow = urls.filter { it.startsWith("ws://") }.toSet()
        val packageName = device.signerPackage.first()
        var state = loadState(pubkey)
        val cipher = linkedMapOf<String, String>()
        val since = syncSince(device.syncWatermarkSeconds(), manual)
        val outcome = runRelayPages(since, 200, { request ->
            loadPage(urls, allow, request, pubkey, cipher)
        }) { event ->
            state = handle(state, event, cipher[event.id], pubkey, packageName)
            handleKind(state, event)
        }
        persist(state)
        val watermark = outcome.watermark
        if (watermark != null) device.setSyncWatermarkSeconds(watermark)
    }

    private suspend fun publishRow(
        row: OutboxEntity,
        urls: List<String>,
        allow: Set<String>,
        pubkey: String,
        packageName: String?,
    ) {
        val signed = row.signedEvent ?: signPayload(row.payload, pubkey, packageName)
        if (signed == null || !snapshotEventOk(signed, pubkey)) {
            dao.upsertOutbox(row.copy(state = OutboxState.AWAITING_SIGNER.name))
            return
        }
        val attempts = urls.map { url -> relays.publish(url, allow, signed) }
        val result = publishOutbox(true, urls, attempts)
        for (url in result.acknowledgedRelays) {
            dao.upsertAck(com.tankful.android.data.db.OutboxRelayAckEntity(row.id, url, true))
            val eventId = eventIdOf(signed) ?: continue
            dao.insertPublished(PublishedContentEventEntity(url, eventId, row.entityType, row.entityId))
        }
        if (!result.deleteOutboxRow) {
            dao.upsertOutbox(row.copy(state = result.state.name, signedEvent = signed))
            return
        }
        maybeKind5(row, pubkey, packageName, urls, allow)
        dao.deleteAcks(row.id)
        dao.deleteOutbox(row.id)
    }

    private suspend fun signPayload(payload: String, pubkey: String, packageName: String?): String? {
        val cipher = signer.encrypt(packageName, payload, pubkey)
        if (cipher.mapping.outcome != SignerFailure.OK) return null
        val content = cipher.value ?: return null
        if (!nip44Wire(content)) return null
        val createdAt = Instant.now().epochSecond
        val unsigned = unsignedEvent(pubkey, createdAt, content)
        val signed = signer.sign(packageName, unsigned)
        if (signed.mapping.outcome != SignerFailure.OK) return null
        val event = signed.value ?: return null
        if (eventText(event, "content") != content) return null
        if (!snapshotEventOk(event, pubkey)) return null
        return event
    }

    private suspend fun maybeKind5(
        row: OutboxEntity,
        pubkey: String,
        packageName: String?,
        urls: List<String>,
        allow: Set<String>,
    ) {
        if (!device.nip09Enabled.first()) return
        if (!row.payload.contains("\"tombstone\":true")) return
        val ids = dao.contentEventIds(row.entityType, row.entityId).toSet()
        val draft = buildKind5(ids, "")
        if (draft.eTags.isEmpty()) return
        val createdAt = Instant.now().epochSecond
        val tags = draft.eTags.map { listOf("e", it) }
        val id = nip01Id(pubkey, createdAt, draft.kind, tags, draft.content)
        val unsigned = canonicalJson.encodeToString(eventBody(id, pubkey, createdAt, draft.kind, tags, draft.content, null))
        val signed = signer.sign(packageName, unsigned).value ?: return
        if (!verifySignedEvent(signed)) return
        if (eventText(signed, "pubkey") != pubkey) return
        if (eventKind(signed) != 5) return
        if (eventText(signed, "content") != "") return
        for (url in urls) relays.publish(url, allow, signed)
    }

    private suspend fun handle(
        state: SyncState,
        event: PagedEvent,
        cipher: String?,
        pubkey: String,
        packageName: String?,
    ): SyncState {
        if (event.author != pubkey) return state
        if (event.id in state.appliedEventIds || event.id in state.quarantinedEventIds) return state
        if (cipher == null) return state
        val plain = signer.decrypt(packageName, cipher, pubkey)
        if (plain.mapping.outcome != SignerFailure.OK) return state
        val text = plain.value ?: return state.copy(quarantinedEventIds = state.quarantinedEventIds + event.id)
        val snapshot = parseSnapshot(text)
        val decrypted = if (snapshot == null) DecryptResult.Undecryptable else DecryptResult.Plain(snapshot)
        return receiveSnapshot(state, event.id, event.author) { decrypted }
    }

    private fun handleKind(state: SyncState, event: PagedEvent): HandleKind {
        if (event.author != state.ownPubkey) return HandleKind.SETTLED
        if (event.id in state.quarantinedEventIds) return HandleKind.QUARANTINED
        if (event.id in state.appliedEventIds) return HandleKind.APPLIED
        return HandleKind.AWAITING_SIGNER
    }

    private suspend fun loadState(pubkey: String): SyncState {
        val book = garage.read()
        val pending = dao.pendingSnapshots().mapNotNull { row ->
            parseSnapshot(row.snapshotJson)?.let { PendingSnapshot(row.eventId, it) }
        }
        val heads = headsOf(book).toMutableMap()
        val settings = settingsHead()
        if (settings != null) heads[settings.entityType to settings.entityId] = settings
        return SyncState(
            ownPubkey = pubkey,
            heads = heads,
            appliedEventIds = dao.appliedIds().toSet(),
            quarantinedEventIds = dao.quarantineIds().toSet(),
            pending = pending,
        )
    }

    private suspend fun persist(state: SyncState) {
        catalog.ensureCustomCategories(categoryIds(state.heads.values))
        garage.edit { book -> applyHeads(book, state.heads) }
        val settings = state.heads["settings" to SETTINGS_ENTITY_ID]
        if (settings != null) applySettings(settings)
        for (id in state.appliedEventIds) dao.insertApplied(AppliedEventEntity(id, System.currentTimeMillis()))
        for (id in state.quarantinedEventIds) dao.insertQuarantine(QuarantineEntity(id, "undecryptable", System.currentTimeMillis()))
        for (item in state.pending) {
            dao.upsertPending(PendingSnapshotEntity(item.eventId, canonicalJson.encodeToString(snapshotJson(item.snapshot))))
        }
        for (fork in state.forks) {
            val head = state.heads[fork.entityType to fork.entityId]
            dao.upsertFork(SnapshotForkEntity(UUID.randomUUID().toString(), fork.entityType, fork.entityId, fork.contentHash, fork.content.toString()))
            val open = (fork.entityType to fork.entityId) in state.openConflicts
            dao.upsertConflict(
                ConflictEntity(
                    id = "${fork.entityType}:${fork.entityId}:${fork.contentHash}",
                    entityType = fork.entityType,
                    entityId = fork.entityId,
                    state = if (open) "OPEN" else "CLOSED",
                    localHash = head?.contentHash ?: "",
                    otherHash = fork.contentHash,
                    otherSnapshot = fork.content?.toString() ?: "",
                ),
            )
        }
    }

    private suspend fun loadPage(
        urls: List<String>,
        allow: Set<String>,
        request: PageRequest,
        pubkey: String,
        cipher: MutableMap<String, String>,
    ): List<PagedEvent> {
        val events = mutableListOf<RelayEvent>()
        for (url in urls) events += relays.fetch(url, allow, request, pubkey)
        return events.map { event ->
            cipher[event.id] = event.content
            PagedEvent(event.id, event.createdAt, event.author, "")
        }
    }

    private suspend fun enqueueSettings() {
        val row = catalog.settings()
        val content = userSettings(
            row.defaultCurrency,
            relayUrls(),
            row.defaultLeadTimeDays,
            device.theme.first(),
            device.measure.first(),
        )
        val encoded = canonicalJson.encodeToString(content)
        val hash = sha256Hex(encoded)
        if (hash == row.contentHash && !row.installId.isNullOrBlank()) return
        val installId = device.ensureInstallId()
        val chained = !row.installId.isNullOrBlank() && !row.contentHash.isNullOrBlank()
        val revision = if (chained) row.revision + 1 else row.revision
        val baseRevision = if (chained) row.revision else null
        val baseInstall = if (chained) row.installId else null
        for (pending in dao.pendingOutbox()) {
            if (pending.entityType == "settings") dao.deleteOutbox(pending.id)
        }
        val updatedAt = Instant.now().toCanonicalString()
        dao.upsertOutbox(settingsOutbox(encoded, revision, installId, baseRevision, baseInstall, updatedAt))
        catalog.saveSettings(row.copy(revision = revision, installId = installId, contentHash = hash))
    }

    private suspend fun settingsHead(): Head? {
        val row = catalog.settings()
        val install = row.installId ?: return null
        val hash = row.contentHash ?: return null
        if (install.isBlank() || hash.isBlank()) return null
        val content = userSettings(
            row.defaultCurrency,
            relayUrls(),
            row.defaultLeadTimeDays,
            device.theme.first(),
            device.measure.first(),
        )
        val encoded = canonicalJson.parseToJsonElement(canonicalJson.encodeToString(content)).jsonObject
        return Head("settings", row.id, row.revision, install, null, null, hash, false, encoded, emptySet())
    }

    private suspend fun applySettings(head: Head) {
        if (head.tombstone) return
        val content = head.content ?: return
        val decoded = runCatching {
            canonicalJson.decodeFromJsonElement(SettingsContent.serializer(), content)
        }.getOrNull() ?: return
        if (decoded.id != SETTINGS_ENTITY_ID) return
        val current = catalog.settings()
        val currency = decoded.defaultCurrency.takeIf { it in MAJOR_CURRENCIES } ?: current.defaultCurrency
        val relays = decoded.relayUrls.map { it.trim() }.filter { relayUrlAllowed(it) }.distinct().take(RELAY_LIST_CAP)
        catalog.saveSettings(
            current.copy(
                defaultCurrency = currency,
                relayUrlsJson = canonicalJson.encodeToString(relays),
                defaultLeadTimeDays = decoded.defaultLeadTimeDays.coerceIn(0, 365),
                revision = head.revision,
                installId = head.installId,
                contentHash = head.contentHash,
            ),
        )
        val theme = decoded.theme
        if (theme == "SYSTEM" || theme == "LIGHT" || theme == "DARK") device.setTheme(theme)
        val measure = decoded.measure
        if (measure == MEASURE_METRIC || measure == MEASURE_IMPERIAL) device.setMeasure(measure)
    }

    private suspend fun relayUrls(): List<String> {
        val raw = catalog.settings().relayUrlsJson
        val array = runCatching { canonicalJson.parseToJsonElement(raw) }.getOrNull() as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .map { it.trim() }
            .filter { relayUrlAllowed(it) }
            .distinct()
            .take(RELAY_LIST_CAP)
    }
}

private fun snapshotEventOk(json: String, pubkey: String): Boolean {
    if (!verifySignedEvent(json)) return false
    if (eventText(json, "pubkey") != pubkey) return false
    if (eventKind(json) != TANKFUL_SNAPSHOT_KIND) return false
    val content = eventText(json, "content") ?: return false
    return nip44Wire(content)
}

private fun headsOf(book: Logbook): Map<Pair<String, String>, Head> {
    val heads = linkedMapOf<Pair<String, String>, Head>()
    for (row in book.vehicles.values) putHead(heads, "vehicle", row.id, row.revision, row.installId, row.contentHash, row.deletedAt != null)
    for (row in book.refuels.values) putHead(heads, "refuel", row.id, row.revision, row.installId, row.contentHash, row.deletedAt != null)
    for (row in book.expenses.values) {
        if (row.origin != ExpenseOrigin.STANDALONE) continue
        putHead(heads, "expense", row.id, row.revision, row.installId, row.contentHash, row.deletedAt != null)
    }
    for (row in book.maintenance.values) {
        putHead(heads, "maintenance", row.id, row.revision, row.installId, row.contentHash, row.deletedAt != null)
    }
    for (row in book.judgements.values) putHead(heads, "interval_judgement", row.id, row.revision, row.installId, row.contentHash, row.deletedAt != null)
    return heads
}

private fun putHead(
    heads: MutableMap<Pair<String, String>, Head>,
    type: String,
    id: String,
    revision: Long,
    installId: String?,
    hash: String?,
    tombstone: Boolean,
) {
    if (installId == null || hash == null) return
    heads[type to id] = Head(type, id, revision, installId, null, null, hash, tombstone, null, emptySet())
}

private fun applyHeads(book: Logbook, heads: Map<Pair<String, String>, Head>) {
    applyTyped(book, heads, "vehicle", ::applyVehicle)
    applyTyped(book, heads, "refuel", ::applyRefuel)
    applyTyped(book, heads, "expense", ::applyExpense)
    applyTyped(book, heads, "maintenance", ::applyMaintenance)
    applyTyped(book, heads, "interval_judgement", ::applyJudgement)
}

private fun applyTyped(
    book: Logbook,
    heads: Map<Pair<String, String>, Head>,
    type: String,
    apply: (Logbook, Head) -> Unit,
) {
    for (head in heads.values) {
        if (head.entityType == type) apply(book, head)
    }
}

private fun applyVehicle(book: Logbook, head: Head) {
    val existing = book.vehicles[head.entityId]
    if (head.tombstone && existing != null) {
        book.vehicles[head.entityId] = existing.copy(deletedAt = existing.deletedAt ?: System.currentTimeMillis())
        return
    }
    val content = head.content ?: return
    val decoded = runCatching { canonicalJson.decodeFromJsonElement(VehicleContent.serializer(), content) }.getOrNull() ?: return
    val created = runCatching { Instant.parse(decoded.createdAt) }.getOrNull() ?: existing?.createdAt ?: return
    val base = existing ?: VehicleRow(decoded.id, created, decoded.manufacturer, decoded.model, decoded.modelYear)
    book.vehicles[head.entityId] = base.copy(
        createdAt = created,
        manufacturer = decoded.manufacturer,
        model = decoded.model,
        modelYear = decoded.modelYear,
        powertrain = decoded.powertrain,
        primaryFuel = decoded.primaryFuel,
        secondaryFuel = decoded.secondaryFuel,
        plate = decoded.plate,
        vin = decoded.vin,
        purchaseDate = decoded.purchaseDate,
        purchasePriceMinor = decoded.purchasePriceMinor,
        purchaseCurrency = decoded.purchaseCurrency,
        tagIds = decoded.tagIds,
        nickname = decoded.nickname,
        revision = head.revision,
        installId = head.installId,
        contentHash = head.contentHash,
        deletedAt = if (head.tombstone) System.currentTimeMillis() else null,
    )
}

private fun applyRefuel(book: Logbook, head: Head) {
    val existing = book.refuels[head.entityId]
    if (head.tombstone && existing != null) {
        book.refuels[head.entityId] = existing.copy(deletedAt = existing.deletedAt ?: System.currentTimeMillis())
        val expense = book.expenses[existing.expenseId]
        if (expense != null && expense.deletedAt == null) {
            book.expenses[existing.expenseId] = expense.copy(deletedAt = existing.timestampEpochMs)
        }
        return
    }
    val content = head.content ?: return
    val decoded = runCatching { canonicalJson.decodeFromJsonElement(RefuelContent.serializer(), content) }.getOrNull() ?: return
    book.restoreRefuel(decoded, head.revision, head.installId, head.contentHash)
}

private fun applyExpense(book: Logbook, head: Head) {
    val existing = book.expenses[head.entityId]
    if (head.tombstone && existing != null && existing.origin == ExpenseOrigin.STANDALONE) {
        book.expenses[head.entityId] = existing.copy(deletedAt = existing.deletedAt ?: System.currentTimeMillis())
        return
    }
    val content = head.content ?: return
    val decoded = runCatching { canonicalJson.decodeFromJsonElement(ExpenseContent.serializer(), content) }.getOrNull() ?: return
    book.restoreExpense(decoded, categoriesFor(decoded.categoryId), head.revision, head.installId, head.contentHash)
}

private fun applyMaintenance(book: Logbook, head: Head) {
    val existing = book.maintenance[head.entityId]
    if (head.tombstone && existing != null) {
        book.maintenance[head.entityId] = existing.copy(deletedAt = existing.deletedAt ?: System.currentTimeMillis())
        val expense = book.expenses[existing.expenseId]
        if (expense != null && expense.deletedAt == null) {
            book.expenses[existing.expenseId] = expense.copy(deletedAt = existing.timestampEpochMs)
        }
        return
    }
    val content = head.content ?: return
    val decoded = runCatching { canonicalJson.decodeFromJsonElement(MaintenanceContent.serializer(), content) }.getOrNull() ?: return
    book.restoreMaintenance(decoded, categoriesFor(decoded.categoryId), head.revision, head.installId, head.contentHash)
}

private fun categoriesFor(id: String): Set<String> {
    if (id.length !in 1..80 || id.any { it.isWhitespace() }) return systemCategoryIds()
    return systemCategoryIds() + id
}

private fun categoryIds(heads: Collection<Head>): Set<String> {
    val ids = mutableSetOf<String>()
    for (head in heads) {
        if (head.tombstone) continue
        val content = head.content ?: continue
        if (head.entityType != "expense" && head.entityType != "maintenance") continue
        val id = content["categoryId"]?.jsonPrimitive?.contentOrNull ?: continue
        ids += id
    }
    return ids
}

private fun applyJudgement(book: Logbook, head: Head) {
    val content = head.content ?: return
    val decoded = runCatching { canonicalJson.decodeFromJsonElement(IntervalJudgementContent.serializer(), content) }.getOrNull() ?: return
    book.judgements[decoded.id] = JudgementRow(
        id = decoded.id,
        openingRefuelId = decoded.openingRefuelId,
        closingRefuelId = decoded.closingRefuelId,
        confirmed = decoded.confirmed,
        deletedAt = if (head.tombstone) System.currentTimeMillis() else null,
        revision = head.revision,
        installId = head.installId,
        contentHash = head.contentHash,
    )
}

private fun unsignedEvent(pubkey: String, createdAt: Long, content: String): String {
    val id = nip01Id(pubkey, createdAt, TANKFUL_SNAPSHOT_KIND, emptyList(), content)
    return canonicalJson.encodeToString(eventBody(id, pubkey, createdAt, TANKFUL_SNAPSHOT_KIND, emptyList(), content, null))
}

private fun eventBody(
    id: String,
    pubkey: String,
    createdAt: Long,
    kind: Int,
    tags: List<List<String>>,
    content: String,
    sig: String?,
): JsonObject {
    val map = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
        "id" to JsonPrimitive(id),
        "pubkey" to JsonPrimitive(pubkey),
        "created_at" to JsonPrimitive(createdAt),
        "kind" to JsonPrimitive(kind),
        "tags" to JsonArray(tags.map { tag -> JsonArray(tag.map { JsonPrimitive(it) }) }),
        "content" to JsonPrimitive(content),
    )
    if (sig != null) map["sig"] = JsonPrimitive(sig)
    return JsonObject(map)
}

private fun eventIdOf(json: String): String? {
    return runCatching { canonicalJson.parseToJsonElement(json).jsonObject["id"]?.jsonPrimitive?.content }.getOrNull()
}

private fun parseSnapshot(text: String): Snapshot? {
    val obj = runCatching { canonicalJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    val schema = obj.string("schema") ?: return null
    if (schema != SYNC_SCHEMA) return Snapshot(schema, "", "", 0, "", null, null, "", false, emptyList(), null)
    return Snapshot(
        schema = schema,
        entityType = obj.string("entityType") ?: return null,
        entityId = obj.string("entityId") ?: return null,
        revision = obj.long("revision") ?: return null,
        installId = obj.string("installId") ?: return null,
        baseRevision = obj.long("baseRevision"),
        baseInstallId = obj.string("baseInstallId"),
        contentHash = obj.string("contentHash") ?: return null,
        tombstone = obj["tombstone"]?.jsonPrimitive?.content == "true",
        resolvesContentHashes = emptyList(),
        content = obj["content"] as? JsonObject,
    )
}

private fun snapshotJson(snapshot: Snapshot): JsonObject {
    return canonicalJson.parseToJsonElement(canonicalJson.encodeToString(wireOf(snapshot))).jsonObject
}

private fun wireOf(snapshot: Snapshot): JsonObject {
    val map = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
    map["schema"] = JsonPrimitive(snapshot.schema)
    map["entityType"] = JsonPrimitive(snapshot.entityType)
    map["entityId"] = JsonPrimitive(snapshot.entityId)
    map["revision"] = JsonPrimitive(snapshot.revision)
    map["installId"] = JsonPrimitive(snapshot.installId)
    map["contentHash"] = JsonPrimitive(snapshot.contentHash)
    map["tombstone"] = JsonPrimitive(snapshot.tombstone)
    val content = snapshot.content
    if (content != null) map["content"] = content
    return JsonObject(map)
}

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.long(name: String): Long? = this[name]?.jsonPrimitive?.contentOrNull?.toLongOrNull()


