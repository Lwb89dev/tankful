package com.tankful.android

import com.tankful.android.catalog.REMINDER_ENGLISH
import com.tankful.android.catalog.notificationBody
import com.tankful.android.catalog.reminderStringHasFormatArgs
import com.tankful.android.json.canonicalJson
import com.tankful.android.ledger.VehicleRow
import com.tankful.android.ledger.replacementVehicleId
import com.tankful.android.model.ReminderKind
import com.tankful.android.providers.publicHttpsHost
import com.tankful.android.sync.RELAY_LIST_CAP
import com.tankful.android.sync.endpointUsesRawSocket
import com.tankful.android.sync.relayUrlAllowed
import java.time.Instant
import com.tankful.android.sync.ANDROID_RESULT_OK
import com.tankful.android.sync.DecryptResult
import com.tankful.android.sync.HandleKind
import com.tankful.android.sync.PagedEvent
import com.tankful.android.sync.ParentPointer
import com.tankful.android.sync.RelayAttempt
import com.tankful.android.sync.SignerFailure
import com.tankful.android.sync.Snapshot
import com.tankful.android.sync.SyncState
import com.tankful.android.sync.buildKind5
import com.tankful.android.sync.mapNip55
import com.tankful.android.sync.prefixWatermark
import com.tankful.android.sync.publishOutbox
import com.tankful.android.sync.rawCleartextSocketAllowed
import com.tankful.android.sync.receiveSnapshot
import com.tankful.android.sync.runRelayPages
import kotlinx.coroutines.runBlocking
import com.tankful.android.transfer.DeletedRef
import com.tankful.android.transfer.ExportDocument
import com.tankful.android.transfer.ImportRowKind
import com.tankful.android.transfer.IntervalJudgementContent
import com.tankful.android.transfer.LocalHead
import com.tankful.android.transfer.RefuelContent
import com.tankful.android.transfer.SettingsContent
import com.tankful.android.transfer.VehicleContent
import com.tankful.android.transfer.encodeExport
import com.tankful.android.transfer.exportOmitsDeviceState
import com.tankful.android.transfer.judgementCanonical
import com.tankful.android.transfer.judgementContentHash
import com.tankful.android.transfer.attachmentFile
import com.tankful.android.transfer.IMPORT_FILE_CAP
import com.tankful.android.transfer.importDocument
import com.tankful.android.transfer.previewImport
import com.tankful.android.transfer.readZipEntry
import com.tankful.android.transfer.safeAttachmentName
import com.tankful.android.transfer.zipBackup
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
class ExportRoundTripTest {
    @Test
    fun exportThenImportRestoresJudgementVehicleAndOmitsDeviceState() {
        val judgement = IntervalJudgementContent(
            id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa:bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            openingRefuelId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            closingRefuelId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            confirmed = true,
        )
        val canonical = judgementCanonical(judgement)
        assertEquals(
            """{"id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa:bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb","openingRefuelId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","closingRefuelId":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb","confirmed":true}""",
            canonical,
        )
        val hash = judgementContentHash(judgement)
        assertEquals(hash, com.tankful.android.json.sha256Hex(canonical))
        assertNotEquals(hash, com.tankful.android.json.sha256Hex("""{"schema":"tankful-sync-snapshot-v1"}"""))
        val vehicle = VehicleContent(
            id = "veh",
            createdAt = "2026-10-03T12:00:00.000Z",
            manufacturer = "Test",
            model = "Car",
            modelYear = 2020,
            powertrain = "ICE",
            primaryFuel = "PETROL",
            tagIds = listOf("tag-1"),
        )
        assertTrue(vehicle.createdAt.endsWith("Z"))
        val refuel = RefuelContent(
            id = "ref",
            vehicleId = "veh",
            timestamp = "2026-10-03T12:00:00.000Z",
            quantityAmount = 42700,
            quantityUnit = "MILLILITRE",
            fuelKind = "PETROL",
            energyKind = "LIQUID",
            totalCurrency = "EUR",
            totalMinor = 7468,
            fullTank = true,
            tagIds = listOf("tag-1"),
        )
        val document = ExportDocument(
            schema = "tankful-export-v1",
            exportedAt = "2026-10-03T12:00:00.000Z",
            appVersion = "1",
            vehicles = listOf(vehicle),
            refuels = listOf(refuel),
            intervalJudgements = listOf(judgement),
            categories = emptyList(),
            settings = SettingsContent(com.tankful.android.model.SETTINGS_ENTITY_ID, "EUR"),
            deleted = listOf(DeletedRef("interval_judgement", "gone")),
        )
        val json = encodeExport(document)
        assertTrue(exportOmitsDeviceState(json))
        assertFalse(json.contains("tagLinks"))
        assertTrue(json.contains("createdAt"))
        assertFalse(json.contains("fuel\""))
        val photo = byteArrayOf(1, 2, 3, 9)
        val zip = zipBackup(json, mapOf("photo.jpg" to photo))
        assertFalse(json.contains("\\u0001"))
        assertTrue(readZipEntry(zip, "attachments/photo.jpg")!!.contentEquals(photo))
        val restored = canonicalJson.decodeFromString(ExportDocument.serializer(), json)
        assertEquals(true, restored.intervalJudgements.single().confirmed)
        assertEquals(judgement.id, restored.intervalJudgements.single().id)
        assertEquals(listOf("tag-1"), restored.refuels.single().tagIds)
        assertTrue(restored.deleted.any { it.id == "gone" })
        val preview = previewImport(json, mapOf("gone" to LocalHead("hash", tombstoned = true)))
        assertEquals(ImportRowKind.STAY_DELETED, preview.rows.single { it.id == "gone" }.kind)
    }
}

class ImportConflictPreviewTest {
    @Test
    fun malformedSiblingSurvivesAndConflictsAreClassified() {
        val json = """
            {"schema":"tankful-export-v1","exportedAt":"2026-10-03T12:00:00.000Z","appVersion":"1",
            "refuels":[
              {"id":"bad","vehicleId":"v","timestamp":"2026-10-03T12:00:00.000Z","quantityAmount":1,
               "quantityUnit":"MILLILITRE","fuelKind":"PETROL","energyKind":"ELECTRIC","totalCurrency":"EUR",
               "totalMinor":1,"fullTank":true,"tagIds":[]},
              {"id":"good","vehicleId":"v","timestamp":"2026-10-03T12:00:00.000Z","quantityAmount":1,
               "quantityUnit":"MILLILITRE","fuelKind":"PETROL","energyKind":"LIQUID","totalCurrency":"EUR",
               "totalMinor":1,"fullTank":true,"tagIds":[]}
            ],
            "intervalJudgements":[
              {"id":"same","openingRefuelId":"a","closingRefuelId":"b","confirmed":true},
              {"id":"diff","openingRefuelId":"c","closingRefuelId":"d","confirmed":false}
            ],
            "deleted":[{"entityType":"interval_judgement","id":"dead"}]}
        """.trimIndent()
        val same = IntervalJudgementContent("same", "a", "b", true)
        val local = mapOf(
            "same" to LocalHead(judgementContentHash(same), false),
            "diff" to LocalHead("other-hash", false),
            "dead" to LocalHead("x", true),
        )
        val preview = previewImport(json, local)
        assertNull(preview.refused)
        assertEquals(ImportRowKind.MALFORMED, preview.rows.single { it.id == "bad" }.kind)
        assertEquals(ImportRowKind.INSERT, preview.rows.single { it.id == "good" }.kind)
        assertEquals(ImportRowKind.AUTO_SKIP, preview.rows.single { it.id == "same" }.kind)
        assertEquals(ImportRowKind.CONFLICT, preview.rows.single { it.id == "diff" }.kind)
        assertEquals(ImportRowKind.STAY_DELETED, preview.rows.single { it.id == "dead" }.kind)
        assertEquals("unknown field", previewImport("""{"schema":"tankful-export-v1","nope":1}""", emptyMap()).refused)
        assertEquals("unknown schema", previewImport("""{"schema":"tankful-export-v9"}""", emptyMap()).refused)
    }
}

class SnapshotReconcileTest {
    private val pubkey = "ab".repeat(32)

    @Test
    fun duplicateHashForkTombstoneGapAndTags() {
        var calls = 0
        val create = snapshot("refuel", "r", 1L, null, null, "hash-1", false, contentWithTag("t1"))
        var state = SyncState(pubkey)
        state = receiveSnapshot(state, "e1", pubkey) { calls += 1; DecryptResult.Plain(create) }
        assertEquals(1, calls)
        state = receiveSnapshot(state, "e1", pubkey) { calls += 1; DecryptResult.Plain(create) }
        assertEquals(1, calls)
        val same = create.copy(revision = 2L)
        state = receiveSnapshot(state, "e2", pubkey) { DecryptResult.Plain(same) }
        assertEquals(1L, state.heads.getValue("refuel" to "r").revision)
        val forward = snapshot("refuel", "r", 2L, 1L, "install-a", "hash-2", false, contentWithTag("t1"))
        state = receiveSnapshot(state, "e3", pubkey) { DecryptResult.Plain(forward) }
        assertEquals(2L, state.heads.getValue("refuel" to "r").revision)
        assertEquals(setOf(Triple("refuel", "r", "t1")), state.entityTags)
        val sibling = snapshot("refuel", "r", 3L, 1L, "install-a", "hash-3", false, contentWithTag("t9"))
        val before = state.heads.getValue("refuel" to "r").contentHash
        state = receiveSnapshot(state, "e4", pubkey) { DecryptResult.Plain(sibling) }
        assertEquals(before, state.heads.getValue("refuel" to "r").contentHash)
        assertTrue(state.forks.any { it.contentHash == "hash-3" })
        val resolution = snapshot(
            "refuel", "r", 4L, 2L, "install-a", "hash-4", false, contentWithTag("t1"),
            resolves = listOf(before, "hash-3"),
        )
        state = receiveSnapshot(state, "e5", pubkey) { DecryptResult.Plain(resolution) }
        assertEquals("hash-4", state.heads.getValue("refuel" to "r").contentHash)
        val tombstone = snapshot("refuel", "r", 5L, 4L, "install-a", "hash-t", true, null)
        state = receiveSnapshot(state, "e6", pubkey) { DecryptResult.Plain(tombstone) }
        assertTrue(state.heads.getValue("refuel" to "r").tombstone)
        state = receiveSnapshot(state, "e7", pubkey) { DecryptResult.Plain(create) }
        assertTrue(state.heads.getValue("refuel" to "r").tombstone)
        val undelete = snapshot("refuel", "r", 6L, 5L, "install-a", "hash-u", false, contentWithTag("t1"))
        state = receiveSnapshot(state, "e8", pubkey) { DecryptResult.Plain(undelete) }
        assertFalse(state.heads.getValue("refuel" to "r").tombstone)
    }

    @Test
    fun staleGapUnknownQuarantineAndKind5() {
        val create = snapshot("refuel", "r", 1L, null, null, "h1", false, null)
        val second = snapshot("refuel", "r", 2L, 1L, "install-a", "h2", false, null)
        val third = snapshot("refuel", "r", 3L, 2L, "install-a", "h3", false, null)
        var state = SyncState(pubkey)
        state = receiveSnapshot(state, "a", pubkey) { DecryptResult.Plain(create) }
        state = receiveSnapshot(state, "b", pubkey) { DecryptResult.Plain(second) }
        state = receiveSnapshot(state, "c", pubkey) { DecryptResult.Plain(third) }
        val stale = snapshot("refuel", "r", 9L, 1L, "install-a", "stale", false, null)
        val headBefore = state.heads.getValue("refuel" to "r")
        state = receiveSnapshot(state, "stale-event", pubkey) { DecryptResult.Plain(stale) }
        assertEquals(headBefore, state.heads.getValue("refuel" to "r"))
        assertTrue("stale-event" in state.appliedEventIds)
        val gap = snapshot("refuel", "r", 8L, 6L, "install-a", "gap", false, null)
        state = receiveSnapshot(state, "gap-event", pubkey) { DecryptResult.Plain(gap) }
        assertEquals(headBefore.contentHash, state.heads.getValue("refuel" to "r").contentHash)
        assertTrue(state.pending.any { it.eventId == "gap-event" })
        val filler = snapshot("refuel", "r", 6L, 3L, "install-a", "h6", false, null)
        state = receiveSnapshot(state, "fill6", pubkey) { DecryptResult.Plain(filler) }
        assertEquals("gap", state.heads.getValue("refuel" to "r").contentHash)
        assertTrue(state.pending.none { it.eventId == "gap-event" })
        val foreign = receiveSnapshot(state, "other", "ff".repeat(32)) { error("decrypt") }
        assertEquals(state.quarantinedEventIds, foreign.quarantinedEventIds)
        var decrypts = 0
        val bad = receiveSnapshot(state, "bad", pubkey) {
            decrypts += 1
            DecryptResult.Plain(create.copy(schema = "other-schema", entityId = "x"))
        }
        assertTrue("bad" in bad.quarantinedEventIds)
        val again = receiveSnapshot(bad, "bad", pubkey) {
            decrypts += 1
            DecryptResult.Plain(create)
        }
        assertEquals(1, decrypts)
        assertEquals(again.quarantinedEventIds, bad.quarantinedEventIds)
        val fresh = snapshot("widget", "w", 4L, 3L, "install-b", "hw", false, null)
        val inserted = receiveSnapshot(SyncState(pubkey), "new-entity", pubkey) { DecryptResult.Plain(fresh) }
        assertEquals("hw", inserted.heads.getValue("widget" to "w").contentHash)
        val judgement = buildJsonObject {
            put("id", "a:b")
            put("openingRefuelId", "a")
            put("closingRefuelId", "b")
            put("confirmed", true)
        }
        val canonical = canonicalJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), judgement)
        val judgementSnapshot = snapshot(
            "interval_judgement",
            "a:b",
            1L,
            null,
            null,
            com.tankful.android.json.sha256Hex(canonical),
            false,
            judgement,
        )
        val judged = receiveSnapshot(SyncState(pubkey), "j", pubkey) { DecryptResult.Plain(judgementSnapshot) }
        val head = judged.heads.getValue("interval_judgement" to "a:b")
        assertEquals("a:b", head.entityId)
        assertEquals("true", (head.content?.get("confirmed") as JsonPrimitive).content)
        val kind5 = buildKind5(setOf("content-1", "tombstone-event"), "tombstone-event")
        assertEquals("", kind5.content)
        assertFalse(kind5.eTags.contains("tombstone-event"))
        assertEquals(listOf("content-1"), kind5.eTags)
    }

    private fun contentWithTag(tag: String) = buildJsonObject {
        put("tagIds", JsonArray(listOf(JsonPrimitive(tag))))
    }

    private fun snapshot(
        type: String,
        id: String,
        revision: Long,
        baseRevision: Long?,
        baseInstall: String?,
        hash: String,
        tombstone: Boolean,
        content: kotlinx.serialization.json.JsonObject?,
        resolves: List<String> = emptyList(),
    ): Snapshot {
        return Snapshot(
            schema = "tankful-sync-snapshot-v1",
            entityType = type,
            entityId = id,
            revision = revision,
            installId = "install-a",
            baseRevision = baseRevision,
            baseInstallId = if (baseRevision == null) null else baseInstall,
            contentHash = hash,
            tombstone = tombstone,
            resolvesContentHashes = resolves,
            content = content,
        )
    }
}

private fun pageOf(events: List<PagedEvent>, request: com.tankful.android.sync.PageRequest): List<PagedEvent> {
    val pool = if (request.until == null) events else events.filter { it.createdAt <= request.until }
    return pool.take(request.limit)
}

private fun handlePage(event: PagedEvent): HandleKind {
    if (event.id == "id-20") return HandleKind.AWAITING_SIGNER
    return HandleKind.APPLIED
}

class RelayPagingTest {
    @Test
    fun twoHundredLimitWalksOlderPageAndWatermarkStopsAtSigner() {
        val events = (1..250).map { index ->
            PagedEvent("id-$index", index.toLong(), "pub", "entity-$index")
        }.sortedByDescending { it.createdAt }
        val outcome = runBlocking {
            runRelayPages(since = 0L, limit = 200, fetch = { request -> pageOf(events, request) }) { event ->
                handlePage(event)
            }
        }
        assertEquals(null, outcome.requests[0].until)
        assertEquals(51L, outcome.requests[1].until)
        assertTrue(outcome.reconciledEntityIds.contains("entity-10"))
        assertTrue(outcome.requests.size >= 2)
        val newest = events.maxOf { it.createdAt }
        assertNotEquals(newest, outcome.watermark)
        assertTrue(outcome.watermark == null || outcome.watermark < 20L)
        val status = mapOf("id-1" to HandleKind.APPLIED, "id-2" to HandleKind.AWAITING_SIGNER)
        val walked = listOf(
            PagedEvent("id-1", 1L, "pub", "e"),
            PagedEvent("id-2", 2L, "pub", "e"),
            PagedEvent("id-3", 3L, "pub", "e"),
        )
        assertEquals(1L, prefixWatermark(walked, status))
    }
}

class OutboxRelayDownTest {
    @Test
    fun relayFailureDoesNotDeleteTheRow() {
        val down = publishOutbox(true, listOf("wss://a"), listOf(RelayAttempt("wss://a", false, true)))
        assertTrue(down.entityVisible)
        assertFalse(down.deleteOutboxRow)
        assertTrue(down.socketOpened)
        val none = publishOutbox(true, emptyList(), emptyList())
        assertEquals(com.tankful.android.sync.OutboxState.PENDING, none.state)
        assertFalse(none.socketOpened)
        val off = publishOutbox(false, listOf("wss://a"), emptyList())
        assertFalse(off.socketOpened)
        val mixed = publishOutbox(
            true,
            listOf("wss://a", "wss://b"),
            listOf(RelayAttempt("wss://a", true, false), RelayAttempt("wss://b", false, true)),
        )
        assertTrue(mixed.synced)
        assertFalse(mixed.deleteOutboxRow)
        assertTrue(mixed.entityVisible)
    }
}

class Nip55ResultMappingTest {
    @Test
    fun mapsResultCodesWithoutFallingBackFromTheResolver() {
        val rejected = mapNip55(ANDROID_RESULT_OK, rejected = true, fromContentResolver = true, timedOut = false)
        assertEquals(SignerFailure.USER_REJECTED, rejected.outcome)
        assertFalse(rejected.fallbackToIntent)
        val failed = mapNip55(0, rejected = false, fromContentResolver = false, timedOut = false)
        assertEquals(SignerFailure.FAILED, failed.outcome)
        val timed = mapNip55(ANDROID_RESULT_OK, rejected = false, fromContentResolver = false, timedOut = true)
        assertEquals(SignerFailure.TIMED_OUT, timed.outcome)
    }
}

class CleartextPolicyTest {
    @Test
    fun cleartextSocketIsAllowListOnly() {
        assertFalse(rawCleartextSocketAllowed("ws://relay.example:80", emptySet()))
        assertTrue(rawCleartextSocketAllowed("ws://relay.example:80", setOf("ws://relay.example:80")))
        assertFalse(endpointUsesRawSocket("epa"))
        assertFalse(endpointUsesRawSocket("mimit"))
        assertTrue(com.tankful.android.providers.EPA_BASE_URL.startsWith("https://"))
        assertTrue(com.tankful.android.providers.MIMIT_DATASET_URL.startsWith("https://"))
    }
}

class VehicleSelectionTest {
    @Test
    fun earliestCreatedAtThenLowestIdAndPurchaseDateIsIgnored() {
        val early = VehicleRow("b", Instant.parse("2020-01-01T00:00:00Z"), "A", "One", 2020, "2019-01-01")
        val later = VehicleRow("a", Instant.parse("2024-01-01T00:00:00Z"), "A", "Two", 2024, "2010-01-01")
        val tieLow = VehicleRow("a", Instant.parse("2020-01-01T00:00:00Z"), "A", "Low", 2020, "2022-01-01")
        val tieHigh = VehicleRow("b", Instant.parse("2020-01-01T00:00:00Z"), "A", "High", 2020, "2001-01-01")
        assertEquals("b", replacementVehicleId(listOf(later, early), "selected", "selected"))
        assertEquals("a", replacementVehicleId(listOf(tieHigh, tieLow), "gone", "gone"))
        assertNull(replacementVehicleId(listOf(early.copy(deletedAt = 1L)), "b", "b"))
        val exported = encodeExport(
            ExportDocument(
                schema = "tankful-export-v1",
                exportedAt = "2026-10-03T12:00:00.000Z",
                appVersion = "1",
                vehicles = listOf(
                    VehicleContent(
                        "b",
                        "2020-01-01T00:00:00.000Z",
                        "A",
                        "One",
                        2020,
                        "ICE",
                        "PETROL",
                    ),
                ),
            ),
        )
        assertTrue(exported.contains("createdAt"))
        assertFalse(exported.contains("selectedVehicleId"))
    }
}

class ReminderCopyTest {
    @Test
    fun englishCatalogHasNoFormatArgumentsAndIgnoresAPlateTitle() {
        for (kind in ReminderKind.entries) {
            val sentence = REMINDER_ENGLISH.getValue(kind)
            assertFalse(reminderStringHasFormatArgs(sentence))
        }
        assertEquals("Odometer reading due", REMINDER_ENGLISH.getValue(ReminderKind.ODOMETER_PROMPT))
        val body = notificationBody(ReminderKind.ODOMETER_PROMPT, "AB123CD service")
        assertEquals("Odometer reading due", body)
        assertFalse(body.contains("AB123CD"))
    }
}

@Suppress("unused")
private val ancestorHint = ParentPointer("install-a", 1L)

class RelayUrlTest {
    @Test
    fun allowsAHostAndRejectsUserinfo() {
        assertTrue(relayUrlAllowed("wss://relay.damus.io"))
        assertTrue(relayUrlAllowed("ws://192.168.1.20:8080"))
        assertTrue(relayUrlAllowed("  ws://relay.local/path  "))
        assertFalse(relayUrlAllowed("wss://user:pass@relay.damus.io"))
        assertFalse(relayUrlAllowed("ws://foo bar"))
        assertFalse(relayUrlAllowed("http://relay.example"))
        assertEquals(8, RELAY_LIST_CAP)
    }
}

class PublicHostTest {
    @Test
    fun staysOnTheOfficialHosts() {
        assertTrue(publicHttpsHost("www.fueleconomy.gov"))
        assertTrue(publicHttpsHost("www.mimit.gov.it"))
        assertTrue(publicHttpsHost("overpass-api.de"))
        assertFalse(publicHttpsHost("evil.example"))
        assertFalse(publicHttpsHost("mimit.gov.it.evil.com"))
    }
}

class ImportDocumentTest {
    @Test
    fun readsExportJsonFromTheZipAndSkipsKeyFiles() {
        val json = """{"schema":"tankful-export-v1"}"""
        val zip = zipBackup(json, mapOf("photo.jpg" to byteArrayOf(1), "../signer-secret.bin" to byteArrayOf(2)))
        assertEquals(json, importDocument(zip))
        assertNull(safeAttachmentName("../signer-secret.bin"))
        assertNull(safeAttachmentName("db-key.bin"))
        assertEquals("attachments/photo.jpg", safeAttachmentName("photo.jpg"))
        val root = java.nio.file.Files.createTempDirectory("tankful-export").toFile()
        try {
            java.io.File(root, "signer-secret.bin").writeBytes(byteArrayOf(1))
            assertNull(attachmentFile(root, "signer-secret.bin"))
            assertNull(attachmentFile(root, "../signer-secret.bin"))
            val note = java.io.File(root, "note.txt")
            note.writeBytes(byteArrayOf(2))
            assertEquals(note.canonicalFile, attachmentFile(root, "note.txt")!!.canonicalFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsAnOversizedPlainFile() {
        val huge = ByteArray(IMPORT_FILE_CAP + 1)
        assertNull(importDocument(huge))
    }
}
