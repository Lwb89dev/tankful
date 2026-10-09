package com.tankful.android.sync

import com.tankful.android.model.TANKFUL_SNAPSHOT_KIND
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

data class ParentPointer(val installId: String?, val revision: Long?)

data class Snapshot(
    val schema: String,
    val entityType: String,
    val entityId: String,
    val revision: Long,
    val installId: String,
    val baseRevision: Long?,
    val baseInstallId: String?,
    val contentHash: String,
    val tombstone: Boolean,
    val resolvesContentHashes: List<String> = emptyList(),
    val content: JsonObject? = null,
)

data class Head(
    val entityType: String,
    val entityId: String,
    val revision: Long,
    val installId: String,
    val baseRevision: Long?,
    val baseInstallId: String?,
    val contentHash: String,
    val tombstone: Boolean,
    val content: JsonObject?,
    val ancestors: Set<ParentPointer>,
)

data class ForkCopy(
    val entityType: String,
    val entityId: String,
    val contentHash: String,
    val content: JsonObject?,
)

data class PendingSnapshot(val eventId: String, val snapshot: Snapshot)

data class SyncState(
    val ownPubkey: String,
    val heads: Map<Pair<String, String>, Head> = emptyMap(),
    val appliedEventIds: Set<String> = emptySet(),
    val quarantinedEventIds: Set<String> = emptySet(),
    val forks: List<ForkCopy> = emptyList(),
    val pending: List<PendingSnapshot> = emptyList(),
    val openConflicts: Set<Pair<String, String>> = emptySet(),
    val entityTags: Set<Triple<String, String, String>> = emptySet(),
    val decryptCalls: Int = 0,
)

sealed interface DecryptResult {
    data class Plain(val snapshot: Snapshot) : DecryptResult
    data object Undecryptable : DecryptResult
}

private const val SYNC_SCHEMA = "tankful-sync-snapshot-v1"

fun receiveSnapshot(
    state: SyncState,
    eventId: String,
    pubkey: String,
    decrypt: () -> DecryptResult,
): SyncState {
    if (pubkey != state.ownPubkey) return state
    if (eventId in state.appliedEventIds || eventId in state.quarantinedEventIds) return state
    val decrypted = decrypt()
    val counted = state.copy(decryptCalls = state.decryptCalls + 1)
    return when (decrypted) {
        DecryptResult.Undecryptable -> counted.copy(quarantinedEventIds = counted.quarantinedEventIds + eventId)
        is DecryptResult.Plain -> applySnapshot(counted, eventId, decrypted.snapshot)
    }
}

fun applySnapshot(state: SyncState, eventId: String, snapshot: Snapshot): SyncState {
    if (eventId in state.appliedEventIds) return state
    if (snapshot.schema != SYNC_SCHEMA) {
        return state.copy(quarantinedEventIds = state.quarantinedEventIds + eventId)
    }
    val key = snapshot.entityType to snapshot.entityId
    val head = state.heads[key]
    if (head == null) return insertUnknown(state, eventId, snapshot)
    if (head.contentHash == snapshot.contentHash) return markApplied(state, eventId)
    if (snapshot.resolvesContentHashes.isNotEmpty()) {
        return applyResolution(state, eventId, snapshot, head)
    }
    if (head.tombstone) return applyAgainstTombstone(state, eventId, snapshot, head)
    if (parentsHead(snapshot, head)) return fastForward(state, eventId, snapshot, head)
    if (sameParent(snapshot, head) && snapshot.contentHash != head.contentHash) {
        return fork(state, eventId, snapshot)
    }
    val pointer = ParentPointer(snapshot.baseInstallId, snapshot.baseRevision)
    if (pointer in head.ancestors) return markApplied(state, eventId)
    val pending = state.pending + PendingSnapshot(eventId, snapshot)
    return state.copy(pending = pending, appliedEventIds = state.appliedEventIds + eventId)
}

private fun insertUnknown(state: SyncState, eventId: String, snapshot: Snapshot): SyncState {
    val head = headFrom(snapshot, emptySet())
    return remember(state, eventId, head, snapshot)
}

private fun applyAgainstTombstone(
    state: SyncState,
    eventId: String,
    snapshot: Snapshot,
    head: Head,
): SyncState {
    val undelete = parentsHead(snapshot, head) && !snapshot.tombstone
    if (undelete) return fastForward(state, eventId, snapshot, head)
    return markApplied(state, eventId)
}

private fun applyResolution(
    state: SyncState,
    eventId: String,
    snapshot: Snapshot,
    head: Head,
): SyncState {
    if (head.contentHash !in snapshot.resolvesContentHashes) return fork(state, eventId, snapshot)
    val closed = snapshot.resolvesContentHashes.toSet()
    val keptForks = state.forks.filter { it.contentHash !in closed || it.entityId != snapshot.entityId }
    val forwarded = fastForward(state.copy(forks = keptForks), eventId, snapshot, head)
    return forwarded.copy(openConflicts = forwarded.openConflicts - (snapshot.entityType to snapshot.entityId))
}

private fun fastForward(state: SyncState, eventId: String, snapshot: Snapshot, head: Head): SyncState {
    val ancestors = head.ancestors + ParentPointer(head.installId, head.revision)
    return remember(state, eventId, headFrom(snapshot, ancestors), snapshot)
}

private fun fork(state: SyncState, eventId: String, snapshot: Snapshot): SyncState {
    val copy = ForkCopy(snapshot.entityType, snapshot.entityId, snapshot.contentHash, snapshot.content)
    val key = snapshot.entityType to snapshot.entityId
    return markApplied(state, eventId).copy(
        forks = state.forks + copy,
        openConflicts = state.openConflicts + key,
    )
}

private fun remember(state: SyncState, eventId: String, head: Head, snapshot: Snapshot): SyncState {
    val key = head.entityType to head.entityId
    val tags = replaceTags(state.entityTags, snapshot)
    val base = markApplied(state, eventId).copy(
        heads = state.heads + (key to head),
        entityTags = tags,
    )
    return drainPending(base, key)
}

private fun drainPending(state: SyncState, key: Pair<String, String>): SyncState {
    val ready = state.pending.filter { it.snapshot.entityType to it.snapshot.entityId == key }
    if (ready.isEmpty()) return state
    var current = state.copy(pending = state.pending - ready.toSet())
    for (item in ready) {
        current = applySnapshot(
            current.copy(appliedEventIds = current.appliedEventIds - item.eventId),
            item.eventId,
            item.snapshot,
        )
    }
    return current
}

private fun markApplied(state: SyncState, eventId: String): SyncState {
    return state.copy(appliedEventIds = state.appliedEventIds + eventId)
}

private fun headFrom(snapshot: Snapshot, ancestors: Set<ParentPointer>): Head {
    return Head(
        entityType = snapshot.entityType,
        entityId = snapshot.entityId,
        revision = snapshot.revision,
        installId = snapshot.installId,
        baseRevision = snapshot.baseRevision,
        baseInstallId = snapshot.baseInstallId,
        contentHash = snapshot.contentHash,
        tombstone = snapshot.tombstone,
        content = snapshot.content,
        ancestors = ancestors,
    )
}

private fun parentsHead(snapshot: Snapshot, head: Head): Boolean {
    return snapshot.baseInstallId == head.installId && snapshot.baseRevision == head.revision
}

private fun sameParent(snapshot: Snapshot, head: Head): Boolean {
    return snapshot.baseInstallId == head.baseInstallId && snapshot.baseRevision == head.baseRevision
}

private fun replaceTags(existing: Set<Triple<String, String, String>>, snapshot: Snapshot): Set<Triple<String, String, String>> {
    val content = snapshot.content ?: return existing
    val tagIds = content["tagIds"] as? JsonArray ?: return existing
    val without = existing.filter { it.first != snapshot.entityType || it.second != snapshot.entityId }.toSet()
    val added = tagIds.map { element ->
        Triple(snapshot.entityType, snapshot.entityId, element.jsonPrimitive.content)
    }
    return without + added
}

data class Kind5Draft(val kind: Int, val content: String, val eTags: List<String>)

fun buildKind5(contentEventIds: Set<String>, tombstoneEventId: String): Kind5Draft {
    val tags = (contentEventIds - tombstoneEventId).sorted()
    return Kind5Draft(kind = 5, content = "", eTags = tags)
}

enum class HandleKind { APPLIED, QUARANTINED, AWAITING_SIGNER, SETTLED }

data class PagedEvent(
    val id: String,
    val createdAt: Long,
    val author: String,
    val entityId: String,
)

data class PageRequest(val since: Long, val until: Long?, val limit: Int)

data class PagingOutcome(
    val requests: List<PageRequest>,
    val reconciledEntityIds: Set<String>,
    val watermark: Long?,
    val stuck: Boolean,
)

private val HANDLED = setOf(HandleKind.APPLIED, HandleKind.QUARANTINED, HandleKind.SETTLED)

fun syncSince(watermarkSeconds: Long?, manualOrWeekly: Boolean): Long {
    if (manualOrWeekly || watermarkSeconds == null) return 0L
    return watermarkSeconds - 48L * 60L * 60L
}

suspend fun runRelayPages(
    since: Long,
    limit: Int,
    fetch: suspend (PageRequest) -> List<PagedEvent>,
    handle: suspend (PagedEvent) -> HandleKind,
): PagingOutcome {
    val requests = mutableListOf<PageRequest>()
    val seen = mutableSetOf<String>()
    val walked = mutableListOf<PagedEvent>()
    val status = linkedMapOf<String, HandleKind>()
    val reconciled = mutableSetOf<String>()
    var until: Long? = null
    var stuck = false
    var guard = 0
    while (guard < 50) {
        guard += 1
        val request = PageRequest(since, until, limit)
        requests += request
        val page = fetch(request)
        var fresh = 0
        for (event in page) {
            if (event.id in seen) continue
            seen += event.id
            fresh += 1
            walked += event
            val kind = handle(event)
            status[event.id] = kind
            if (kind == HandleKind.APPLIED) reconciled += event.entityId
        }
        if (fresh == 0) {
            stuck = true
            break
        }
        if (page.size < limit) break
        until = page.minOf { it.createdAt }
    }
    val watermark = if (stuck) null else prefixWatermark(walked, status)
    return PagingOutcome(requests, reconciled, watermark, stuck)
}

fun prefixWatermark(walked: List<PagedEvent>, status: Map<String, HandleKind>): Long? {
    val ordered = walked.sortedWith(compareBy({ it.createdAt }, { it.id }))
    var last = null as Long?
    for (event in ordered) {
        val kind = status[event.id]
        if (kind !in HANDLED) break
        val stamp = event.createdAt
        val blocked = ordered.any { other ->
            other.createdAt == stamp && status[other.id] !in HANDLED
        }
        if (!blocked) last = stamp
    }
    return last
}

enum class OutboxState { PENDING, AWAITING_SIGNER, PUBLISHING, ACKNOWLEDGED, FAILED }

data class RelayAttempt(val url: String, val acknowledged: Boolean, val threw: Boolean)

data class PublishResult(
    val state: OutboxState,
    val acknowledgedRelays: Set<String>,
    val deleteOutboxRow: Boolean,
    val socketOpened: Boolean,
    val entityVisible: Boolean,
    val synced: Boolean,
)

fun publishOutbox(syncEnabled: Boolean, relays: List<String>, attempts: List<RelayAttempt>): PublishResult {
    if (!syncEnabled || relays.isEmpty()) {
        return PublishResult(OutboxState.PENDING, emptySet(), false, false, true, false)
    }
    val acked = attempts.filter { it.acknowledged && !it.threw }.map { it.url }.toSet()
    val everyRelay = relays.all { it in acked }
    val state = if (everyRelay) OutboxState.ACKNOWLEDGED else OutboxState.PUBLISHING
    return PublishResult(
        state = state,
        acknowledgedRelays = acked,
        deleteOutboxRow = everyRelay,
        socketOpened = true,
        entityVisible = true,
        synced = acked.isNotEmpty(),
    )
}

const val ANDROID_RESULT_OK: Int = -1

enum class SignerFailure {
    OK,
    NOT_INSTALLED,
    USER_REJECTED,
    ALWAYS_REJECTED,
    FAILED,
    TIMED_OUT,
    MALFORMED,
}

data class Nip55Mapping(val outcome: SignerFailure, val fallbackToIntent: Boolean)

fun mapNip55(
    resultCode: Int,
    rejected: Boolean,
    fromContentResolver: Boolean,
    timedOut: Boolean,
): Nip55Mapping {
    if (timedOut) return Nip55Mapping(SignerFailure.TIMED_OUT, false)
    if (fromContentResolver && rejected) return Nip55Mapping(SignerFailure.USER_REJECTED, false)
    if (resultCode != ANDROID_RESULT_OK) return Nip55Mapping(SignerFailure.FAILED, false)
    if (rejected) return Nip55Mapping(SignerFailure.USER_REJECTED, false)
    return Nip55Mapping(SignerFailure.OK, false)
}

fun rawCleartextSocketAllowed(url: String, userAllowList: Set<String>): Boolean {
    if (!url.startsWith("ws://")) return false
    return url in userAllowList
}

fun endpointUsesRawSocket(endpointId: String): Boolean {
    if (endpointId == "epa" || endpointId == "mimit" || endpointId == "overpass" || endpointId == "tiles") {
        return false
    }
    return false
}

const val SNAPSHOT_KIND: Int = TANKFUL_SNAPSHOT_KIND
