package com.tankful.android.transfer

import com.tankful.android.json.canonicalJson
import com.tankful.android.json.sha256Hex
import com.tankful.android.model.EXPORT_SCHEMA
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class IntervalJudgementContent(
    val id: String,
    val openingRefuelId: String,
    val closingRefuelId: String,
    val confirmed: Boolean,
)

@Serializable
data class VehicleContent(
    val id: String,
    val createdAt: String,
    val manufacturer: String,
    val model: String,
    val modelYear: Int,
    val powertrain: String,
    val primaryFuel: String,
    val secondaryFuel: String? = null,
    val plate: String? = null,
    val vin: String? = null,
    val purchaseDate: String? = null,
    val purchasePriceMinor: Long? = null,
    val purchaseCurrency: String? = null,
    val tagIds: List<String> = emptyList(),
    val nickname: String? = null,
)

@Serializable
data class RefuelContent(
    val id: String,
    val vehicleId: String,
    val timestamp: String,
    val quantityAmount: Long,
    val quantityUnit: String,
    val fuelKind: String,
    val energyKind: String,
    val totalCurrency: String,
    val totalMinor: Long,
    val fullTank: Boolean,
    val tagIds: List<String> = emptyList(),
)

@Serializable
data class DeletedRef(val entityType: String, val id: String)

@Serializable
data class AttachmentMeta(
    val id: String,
    val relativePath: String,
    val filename: String,
    val mime: String,
)

@Serializable
data class SettingsContent(
    val id: String,
    val defaultCurrency: String,
    val relayUrls: List<String> = emptyList(),
    val defaultLeadTimeDays: Int = 7,
)

@Serializable
data class ExportDocument(
    val schema: String,
    val exportedAt: String,
    val appVersion: String,
    val vehicles: List<VehicleContent> = emptyList(),
    val specifications: List<JsonObject> = emptyList(),
    val refuels: List<RefuelContent> = emptyList(),
    val odometerReadings: List<JsonObject> = emptyList(),
    val expenses: List<JsonObject> = emptyList(),
    val categories: List<JsonObject> = emptyList(),
    val maintenanceRecords: List<JsonObject> = emptyList(),
    val maintenanceItems: List<JsonObject> = emptyList(),
    val serviceProviders: List<JsonObject> = emptyList(),
    val serviceTypes: List<JsonObject> = emptyList(),
    val reminders: List<JsonObject> = emptyList(),
    val tags: List<JsonObject> = emptyList(),
    val intervalJudgements: List<IntervalJudgementContent> = emptyList(),
    val attachments: List<AttachmentMeta> = emptyList(),
    val settings: SettingsContent? = null,
    val deleted: List<DeletedRef> = emptyList(),
)

private val TOP_LEVEL = setOf(
    "schema",
    "exportedAt",
    "appVersion",
    "vehicles",
    "specifications",
    "refuels",
    "odometerReadings",
    "expenses",
    "categories",
    "maintenanceRecords",
    "maintenanceItems",
    "serviceProviders",
    "serviceTypes",
    "reminders",
    "tags",
    "intervalJudgements",
    "attachments",
    "settings",
    "deleted",
)

fun encodeExport(document: ExportDocument): String = canonicalJson.encodeToString(document)

fun judgementCanonical(content: IntervalJudgementContent): String {
    return canonicalJson.encodeToString(content)
}

fun judgementContentHash(content: IntervalJudgementContent): String {
    return sha256Hex(judgementCanonical(content))
}

fun zipBackup(exportJson: String, files: Map<String, ByteArray>): ByteArray {
    val buffer = ByteArrayOutputStream()
    ZipOutputStream(buffer).use { zip ->
        zip.putNextEntry(ZipEntry("export.json"))
        zip.write(exportJson.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        for ((path, bytes) in files) {
            zip.putNextEntry(ZipEntry("attachments/$path"))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return buffer.toByteArray()
}

fun readZipEntry(zipBytes: ByteArray, name: String): ByteArray? {
    ZipInputStream(zipBytes.inputStream()).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            if (entry.name == name) return zip.readBytes()
            entry = zip.nextEntry
        }
    }
    return null
}

enum class ImportRowKind { INSERT, AUTO_SKIP, CONFLICT, MALFORMED, STAY_DELETED }

data class ImportRow(val id: String, val kind: ImportRowKind, val detail: String)

data class ImportPreview(val refused: String?, val rows: List<ImportRow>)

data class LocalHead(val contentHash: String, val tombstoned: Boolean)

fun previewImport(json: String, local: Map<String, LocalHead>): ImportPreview {
    val root = try {
        canonicalJson.parseToJsonElement(json).jsonObject
    } catch (exception: Exception) {
        return ImportPreview("unreadable", emptyList())
    }
    val unknown = root.keys.filter { it !in TOP_LEVEL }
    if (unknown.isNotEmpty()) return ImportPreview("unknown field", emptyList())
    val schema = root["schema"]?.toString()?.trim('"')
    if (schema != EXPORT_SCHEMA) return ImportPreview("unknown schema", emptyList())
    val document = try {
        canonicalJson.decodeFromString(ExportDocument.serializer(), json)
    } catch (exception: Exception) {
        return ImportPreview("unreadable", emptyList())
    }
    val deletedIds = document.deleted.map { it.id }.toSet()
    val rows = mutableListOf<ImportRow>()
    for (refuel in document.refuels) {
        rows += classify(refuel.id, refuel.energyKind, null, deletedIds, local)
    }
    for (judgement in document.intervalJudgements) {
        val hash = judgementContentHash(judgement)
        rows += classify(judgement.id, "LIQUID", hash, deletedIds, local)
    }
    for (vehicle in document.vehicles) {
        rows += classify(vehicle.id, "LIQUID", null, deletedIds, local)
    }
    val seen = rows.map { it.id }.toSet()
    for (deleted in document.deleted) {
        if (deleted.id !in seen) rows += ImportRow(deleted.id, ImportRowKind.STAY_DELETED, "deleted")
    }
    return ImportPreview(null, rows)
}

private fun classify(
    id: String,
    energyKind: String,
    hash: String?,
    deletedIds: Set<String>,
    local: Map<String, LocalHead>,
): ImportRow {
    if (energyKind == "ELECTRIC") return ImportRow(id, ImportRowKind.MALFORMED, "ELECTRIC")
    if (id in deletedIds) return ImportRow(id, ImportRowKind.STAY_DELETED, "deleted")
    val head = local[id]
    if (head != null && head.tombstoned) return ImportRow(id, ImportRowKind.STAY_DELETED, "local tombstone")
    if (head != null && hash != null && head.contentHash == hash) {
        return ImportRow(id, ImportRowKind.AUTO_SKIP, "same hash")
    }
    if (head != null && hash != null && head.contentHash != hash) {
        return ImportRow(id, ImportRowKind.CONFLICT, "different hash")
    }
    return ImportRow(id, ImportRowKind.INSERT, "new")
}

fun exportOmitsDeviceState(json: String): Boolean {
    return "selectedVehicleId" !in json &&
        "\"theme\"" !in json &&
        "installId" !in json &&
        "tagLinks" !in json
}
