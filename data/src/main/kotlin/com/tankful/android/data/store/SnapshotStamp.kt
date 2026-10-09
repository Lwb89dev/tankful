package com.tankful.android.data.store

import com.tankful.android.data.db.OutboxEntity
import com.tankful.android.json.canonicalJson
import com.tankful.android.json.sha256Hex
import com.tankful.android.json.toCanonicalString
import com.tankful.android.ledger.JudgementRow
import com.tankful.android.ledger.Logbook
import com.tankful.android.ledger.RefuelRow
import com.tankful.android.ledger.VehicleRow
import com.tankful.android.model.SYNC_SCHEMA
import com.tankful.android.transfer.IntervalJudgementContent
import com.tankful.android.transfer.RefuelContent
import com.tankful.android.transfer.VehicleContent
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
    stampJudgements(book, sync, installId, updatedAt, outbox)
    return outbox
}

private fun stampVehicles(
    book: Logbook,
    sync: Boolean,
    installId: String,
    updatedAt: String,
    outbox: MutableList<OutboxEntity>,
) {
    for ((id, row) in book.vehicles.toList()) {
        val encoded = canonicalJson.encodeToString(vehicleContent(row))
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
        val encoded = canonicalJson.encodeToString(refuelContent(row))
        val next = stamped(row.contentHash, row.revision, row.installId, row.deletedAt != null, encoded, installId, sync)
        if (next == null) continue
        book.refuels[id] = row.copy(contentHash = next.hash, installId = next.installId)
        if (sync) outbox += next.toOutbox("refuel", id, updatedAt, encoded)
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

private fun vehicleContent(row: VehicleRow): VehicleContent {
    return VehicleContent(
        id = row.id,
        createdAt = row.createdAt.toCanonicalString(),
        manufacturer = row.manufacturer,
        model = row.model,
        modelYear = row.modelYear,
        powertrain = row.powertrain,
        primaryFuel = row.primaryFuel,
        secondaryFuel = row.secondaryFuel,
        plate = row.plate,
        vin = row.vin,
        purchaseDate = row.purchaseDate,
        purchasePriceMinor = row.purchasePriceMinor,
        purchaseCurrency = row.purchaseCurrency,
        tagIds = row.tagIds.sorted(),
        nickname = row.nickname,
    )
}

private fun refuelContent(row: RefuelRow): RefuelContent {
    return RefuelContent(
        id = row.id,
        vehicleId = row.vehicleId,
        timestamp = Instant.ofEpochMilli(row.timestampEpochMs).toCanonicalString(),
        quantityAmount = row.quantity.amount,
        quantityUnit = row.quantity.unit.name,
        fuelKind = row.fuelKind.name,
        energyKind = row.energyKind.name,
        totalCurrency = row.total.currency,
        totalMinor = row.total.minorUnits,
        fullTank = row.fullTank,
        tagIds = row.tagIds.sorted(),
    )
}
