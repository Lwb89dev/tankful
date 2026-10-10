package com.tankful.android.data.store

import com.tankful.android.data.db.OutboxEntity
import com.tankful.android.json.canonicalJson
import com.tankful.android.json.sha256Hex
import com.tankful.android.json.toCanonicalString
import com.tankful.android.ledger.Logbook
import com.tankful.android.model.SETTINGS_ENTITY_ID
import com.tankful.android.model.SYNC_SCHEMA
import com.tankful.android.transfer.IntervalJudgementContent
import com.tankful.android.transfer.maintenanceContentOf
import com.tankful.android.transfer.refuelContentOf
import com.tankful.android.transfer.standaloneExpenseContent
import com.tankful.android.transfer.vehicleContentOf
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@Serializable
private data class SnapshotWire(
    val schema: String,
    val entityType: String,
    val entityId: String,
    val revision: Long,
    val installId: String,
    val baseRevision: Long? = null,
    val baseInstallId: String? = null,
    val contentHash: String,
    val updatedAt: String,
    val tombstone: Boolean,
    val resolvesContentHashes: List<String> = emptyList(),
    val content: JsonObject? = null,
)

/** Stamps content hashes. Enqueues one current snapshot when sync is on and the hash changed. */
fun stampLogbook(book: Logbook, sync: Boolean, installId: String, now: Instant): List<OutboxEntity> {
    val updatedAt = now.toCanonicalString()
    val outbox = mutableListOf<OutboxEntity>()
    stampVehicles(book, sync, installId, updatedAt, outbox)
    stampRefuels(book, sync, installId, updatedAt, outbox)
    stampExpenses(book, sync, installId, updatedAt, outbox)
    stampMaintenance(book, sync, installId, updatedAt, outbox)
    stampJudgements(book, sync, installId, updatedAt, outbox)
    return outbox
}

fun settingsOutbox(
    encoded: String,
    revision: Long,
    installId: String,
    baseRevision: Long?,
    baseInstallId: String?,
    updatedAt: String,
): OutboxEntity {
    val hash = sha256Hex(encoded)
    val wire = SnapshotWire(
        schema = SYNC_SCHEMA,
        entityType = "settings",
        entityId = SETTINGS_ENTITY_ID,
        revision = revision,
        installId = installId,
        baseRevision = baseRevision,
        baseInstallId = baseInstallId,
        contentHash = hash,
        updatedAt = updatedAt,
        tombstone = false,
        content = canonicalJson.parseToJsonElement(encoded).jsonObject,
    )
    return OutboxEntity(
        id = "settings:$SETTINGS_ENTITY_ID:$revision",
        entityType = "settings",
        entityId = SETTINGS_ENTITY_ID,
        revision = revision,
        state = "PENDING",
        signedEvent = null,
        payload = canonicalJson.encodeToString(wire),
        createdAt = System.currentTimeMillis(),
    )
}

private fun stampVehicles(
    book: Logbook,
    sync: Boolean,
    installId: String,
    updatedAt: String,
    outbox: MutableList<OutboxEntity>,
) {
    for ((id, row) in book.vehicles.toList()) {
        val encoded = canonicalJson.encodeToString(vehicleContentOf(row))
        val next = stamped(row.contentHash, row.revision, row.installId, row.deletedAt != null, encoded, installId, sync)
        if (next == null) continue
        book.vehicles[id] = row.copy(contentHash = next.hash, installId = next.installId)
        if (sync) outbox += next.toOutbox("vehicle", id, updatedAt, encoded)
    }
}

private fun stampRefuels(
    book: Logbook,
    sync: Boolean,
    installId: String,
    updatedAt: String,
    outbox: MutableList<OutboxEntity>,
) {
    for ((id, row) in book.refuels.toList()) {
        val metres = row.odometerReadingId?.let { book.readings[it] }?.takeIf { it.deletedAt == null }?.metres
        val encoded = canonicalJson.encodeToString(refuelContentOf(row, metres))
        val next = stamped(row.contentHash, row.revision, row.installId, row.deletedAt != null, encoded, installId, sync)
        if (next == null) continue
        book.refuels[id] = row.copy(contentHash = next.hash, installId = next.installId)
        if (sync) outbox += next.toOutbox("refuel", id, updatedAt, encoded)
    }
}

private fun stampExpenses(
    book: Logbook,
    sync: Boolean,
    installId: String,
    updatedAt: String,
    outbox: MutableList<OutboxEntity>,
) {
    for ((id, row) in book.expenses.toList()) {
        val content = standaloneExpenseContent(row) ?: continue
        val encoded = canonicalJson.encodeToString(content)
        val next = stamped(row.contentHash, row.revision, row.installId, row.deletedAt != null, encoded, installId, sync)
        if (next == null) continue
        book.expenses[id] = row.copy(contentHash = next.hash, installId = next.installId)
        if (sync) outbox += next.toOutbox("expense", id, updatedAt, encoded)
    }
}

private fun stampMaintenance(
    book: Logbook,
    sync: Boolean,
    installId: String,
    updatedAt: String,
    outbox: MutableList<OutboxEntity>,
) {
    for ((id, row) in book.maintenance.toList()) {
        val metres = row.odometerReadingId?.let { book.readings[it] }?.takeIf { it.deletedAt == null }?.metres
        val encoded = canonicalJson.encodeToString(maintenanceContentOf(row, metres))
        val next = stamped(row.contentHash, row.revision, row.installId, row.deletedAt != null, encoded, installId, sync)
        if (next == null) continue
        book.maintenance[id] = row.copy(contentHash = next.hash, installId = next.installId)
        if (sync) outbox += next.toOutbox("maintenance", id, updatedAt, encoded)
    }
}

private fun stampJudgements(
    book: Logbook,
    sync: Boolean,
    installId: String,
    updatedAt: String,
    outbox: MutableList<OutboxEntity>,
) {
    for ((id, row) in book.judgements.toList()) {
        val content = IntervalJudgementContent(row.id, row.openingRefuelId, row.closingRefuelId, row.confirmed)
        val encoded = canonicalJson.encodeToString(content)
        val next = stamped(row.contentHash, row.revision, row.installId, row.deletedAt != null, encoded, installId, sync)
        if (next == null) continue
        book.judgements[id] = row.copy(contentHash = next.hash, installId = next.installId)
        if (sync) outbox += next.toOutbox("interval_judgement", id, updatedAt, encoded)
    }
}

private data class Stamp(
    val hash: String,
    val revision: Long,
    val previousHash: String?,
    val installId: String?,
    val previousInstallId: String?,
    val tombstone: Boolean,
)

private fun stamped(
    previousHash: String?,
    revision: Long,
    previousInstallId: String?,
    tombstone: Boolean,
    encoded: String,
    installId: String,
    sync: Boolean,
): Stamp? {
    val hash = sha256Hex(encoded)
    val missingInstall = previousInstallId.isNullOrBlank()
    if (hash == previousHash && !(sync && missingInstall)) return null
    val chosen = when {
        sync && missingInstall -> installId.takeIf { it.isNotBlank() }
        else -> previousInstallId ?: installId.takeIf { it.isNotBlank() }
    }
    return Stamp(hash, revision, previousHash, chosen, previousInstallId, tombstone)
}

private fun Stamp.toOutbox(type: String, entityId: String, updatedAt: String, encoded: String): OutboxEntity {
    val install = installId ?: "00000000-0000-4000-8000-000000000000"
    val firstPublish = previousInstallId.isNullOrBlank()
    val baseRevision = if (previousHash == null || firstPublish) null else revision - 1
    val baseInstall = if (firstPublish) null else previousInstallId
    val wire = SnapshotWire(
        schema = SYNC_SCHEMA,
        entityType = type,
        entityId = entityId,
        revision = revision,
        installId = install,
        baseRevision = baseRevision,
        baseInstallId = baseInstall,
        contentHash = hash,
        updatedAt = updatedAt,
        tombstone = tombstone,
        content = canonicalJson.parseToJsonElement(encoded).jsonObject,
    )
    return OutboxEntity(
        id = "$type:$entityId:$revision",
        entityType = type,
        entityId = entityId,
        revision = revision,
        state = "PENDING",
        signedEvent = null,
        payload = canonicalJson.encodeToString(wire),
        createdAt = System.currentTimeMillis(),
    )
}


