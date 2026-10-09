package com.tankful.android.data.catalogue

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class CatalogueEntry(
    val id: String,
    val source: String,
    val make: String,
    val model: String,
    val variant: String,
    val year: Int,
    val fuel: String,
    val powertrain: String,
    val testCycle: String,
    val measureKind: String,
    val combinedUnit: String,
    val combinedScale: Int,
    val combinedUnscaled: Long,
    val urbanUnscaled: Long?,
    val extraUnscaled: Long?,
    val cityUnscaled: Long?,
    val highwayUnscaled: Long?,
    val co2Unscaled: Long?,
    val co2GramsPerMile: Boolean,
    val powerKw: Int?,
)

data class CatalogueProvenance(
    val builtAt: Long,
    val sourceUrl: String,
    val retrievalProtocol: String,
)

/** Read-only copy of assets/official_catalogue.pack. Not the Room logbook. */
@Singleton
class OfficialCatalogue @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val gate = Mutex()
    private var database: SQLiteDatabase? = null
    private var cachedMeta: Map<String, String>? = null
    private var cachedDirectory: CatalogueDirectory? = null

    suspend fun search(raw: String, narrowing: CatalogueNarrowing = CatalogueNarrowing()): List<CatalogueEntry> {
        return withContext(Dispatchers.IO) {
            val query = catalogueQuery(raw) ?: return@withContext emptyList()
            gate.withLock { queryHits(open(), query, narrowing) }
        }
    }

    suspend fun facets(raw: String): List<CatalogueFacet> = withContext(Dispatchers.IO) {
        val query = catalogueQuery(raw) ?: return@withContext emptyList()
        gate.withLock { readFacets(open(), query) }
    }

    suspend fun byId(id: String): CatalogueEntry? = withContext(Dispatchers.IO) {
        if (id.isBlank()) return@withContext null
        gate.withLock { readOne(open(), id) }
    }

    suspend fun provenance(source: String): CatalogueProvenance? = withContext(Dispatchers.IO) {
        gate.withLock { readProvenance(open(), source) }
    }

    suspend fun directory(): CatalogueDirectory = withContext(Dispatchers.IO) {
        gate.withLock { loadDirectory(open()) }
    }

    suspend fun models(makeFold: String): List<CatalogueName> = withContext(Dispatchers.IO) {
        gate.withLock { loadDirectory(open()).models[makeFold].orEmpty() }
    }

    suspend fun facets(scope: CatalogueScope): List<CatalogueFacet> = withContext(Dispatchers.IO) {
        if (scope.manual) return@withContext emptyList()
        gate.withLock { readScopeFacets(open(), scope) }
    }

    suspend fun search(scope: CatalogueScope, narrowing: CatalogueNarrowing): List<CatalogueEntry> {
        return withContext(Dispatchers.IO) {
            if (scope.manual) return@withContext emptyList()
            gate.withLock { queryScope(open(), scope, narrowing) }
        }
    }

    private fun open(): SQLiteDatabase {
        val current = database
        if (current != null && current.isOpen) return current
        val file = File(context.filesDir, fileName)
        prepare(file)
        val opened = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        database = opened
        return opened
    }

    private fun prepare(file: File) {
        val assetVersion = headerVersion(openCatalogue())
        val localVersion = if (file.isFile) headerVersion(file.inputStream()) else -1
        if (localVersion == assetVersion) return
        database?.close()
        database = null
        cachedMeta = null
        cachedDirectory = null
        copyAsset(file)
    }

    private fun openCatalogue(): InputStream {
        return GZIPInputStream(context.assets.open(assetName))
    }

    private fun copyAsset(destination: File) {
        val temp = File(destination.parentFile, destination.name + ".tmp")
        openCatalogue().use { input ->
            FileOutputStream(temp).use { output -> input.copyTo(output) }
        }
        if (destination.exists()) destination.delete()
        if (temp.renameTo(destination)) return
        temp.copyTo(destination, overwrite = true)
        temp.delete()
    }

    private fun queryHits(db: SQLiteDatabase, query: CatalogueQuery, narrowing: CatalogueNarrowing): List<CatalogueEntry> {
        val bound = bound(query, narrowing)
        val capacity = narrowing.displacementCc
        if (capacity == null) return readLimited(db, bound)
        return readIds(db, idsForDisplacement(db, bound, capacity))
    }

    private fun readFacets(db: SQLiteDatabase, query: CatalogueQuery): List<CatalogueFacet> {
        val bound = bound(query, CatalogueNarrowing())
        val sql = "SELECT year, fuel, variant, power_kw FROM models WHERE ${bound.where}"
        val found = mutableSetOf<CatalogueFacet>()
        db.rawQuery(sql, bound.args).use { cursor ->
            while (cursor.moveToNext()) found += readFacet(cursor)
        }
        return found.toList()
    }

    private fun readLimited(db: SQLiteDatabase, bound: Bound): List<CatalogueEntry> {
        val sql = "$selectSql WHERE ${bound.where} ORDER BY $orderSql LIMIT 40"
        return readAll(db.rawQuery(sql, bound.args))
    }

    private fun idsForDisplacement(db: SQLiteDatabase, bound: Bound, capacity: Int): List<String> {
        val sql = "SELECT id, variant FROM models WHERE ${bound.where} ORDER BY $orderSql"
        val found = mutableListOf<String>()
        db.rawQuery(sql, bound.args).use { cursor ->
            while (cursor.moveToNext() && found.size < 40) keepId(cursor, capacity, found)
        }
        return found
    }

    private fun keepId(cursor: Cursor, capacity: Int, found: MutableList<String>) {
        val variant = if (cursor.isNull(1)) "" else cursor.getString(1)
        if (displacementCc(variant) == capacity) found += cursor.getString(0)
    }

    private fun readIds(db: SQLiteDatabase, ids: List<String>): List<CatalogueEntry> {
        if (ids.isEmpty()) return emptyList()
        val marks = ids.joinToString(",") { "?" }
        val rows = readAll(db.rawQuery("$selectSql WHERE id IN ($marks)", ids.toTypedArray()))
        val byId = rows.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    private fun loadDirectory(db: SQLiteDatabase): CatalogueDirectory {
        val cached = cachedDirectory
        if (cached != null) return cached
        val makes = mutableMapOf<String, Int>()
        val perMake = mutableMapOf<String, MutableMap<String, Int>>()
        val sql = "SELECT make, model, COUNT(*) FROM models GROUP BY make, model"
        db.rawQuery(sql, null).use { cursor -> collectNames(cursor, makes, perMake) }
        val loaded = buildDirectory(makes, perMake)
        cachedDirectory = loaded
        return loaded
    }

    private fun collectNames(cursor: Cursor, makes: MutableMap<String, Int>, perMake: MutableMap<String, MutableMap<String, Int>>) {
        while (cursor.moveToNext()) {
            val make = cursor.getString(0)
            val model = cursor.getString(1)
            val count = cursor.getInt(2)
            makes[make] = (makes[make] ?: 0) + count
            val bucket = perMake.getOrPut(foldCatalogue(make)) { mutableMapOf() }
            bucket[model] = (bucket[model] ?: 0) + count
        }
    }

    private fun buildDirectory(makes: Map<String, Int>, perMake: Map<String, Map<String, Int>>): CatalogueDirectory {
        val makeNames = groupSpellings(makes.entries.map { it.key to it.value })
        val models = makeNames.associate { name ->
            val rows = perMake[name.fold].orEmpty().entries.map { it.key to it.value }
            name.fold to groupSpellings(rows)
        }
        return CatalogueDirectory(makeNames, models)
    }

    private fun readScopeFacets(db: SQLiteDatabase, scope: CatalogueScope): List<CatalogueFacet> {
        val bound = scopeBound(scope, CatalogueNarrowing())
        val sql = "SELECT year, fuel, variant, power_kw FROM models WHERE ${bound.where}"
        val found = mutableSetOf<CatalogueFacet>()
        db.rawQuery(sql, bound.args).use { cursor ->
            while (cursor.moveToNext()) found += readFacet(cursor)
        }
        return found.toList()
    }

    private fun queryScope(db: SQLiteDatabase, scope: CatalogueScope, narrowing: CatalogueNarrowing): List<CatalogueEntry> {
        val bound = scopeBound(scope, narrowing)
        val capacity = narrowing.displacementCc
        if (capacity == null) return readLimited(db, bound)
        return readIds(db, idsForDisplacement(db, bound, capacity))
    }

    private fun scopeBound(scope: CatalogueScope, narrowing: CatalogueNarrowing): Bound {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        include(clauses, args, "make", scope.makeSpellings)
        include(clauses, args, "model", scope.modelSpellings)
        equate(clauses, args, "fuel", scope.fuel)
        equate(clauses, args, "powertrain", scope.powertrain)
        equate(clauses, args, "year", narrowing.year?.toString())
        equate(clauses, args, "fuel", narrowing.fuel)
        equate(clauses, args, "power_kw", narrowing.powerKw?.toString())
        return Bound(clauses.joinToString(" AND "), args.toTypedArray())
    }

    private fun include(clauses: MutableList<String>, args: MutableList<String>, column: String, values: List<String>) {
        if (values.isEmpty()) {
            clauses += "0 = 1"
            return
        }
        clauses += "$column IN (${values.joinToString(",") { "?" }})"
        args += values
    }

    private fun readFacet(cursor: Cursor): CatalogueFacet {
        val variant = if (cursor.isNull(2)) "" else cursor.getString(2)
        val power = if (cursor.isNull(3)) null else cursor.getInt(3)
        return CatalogueFacet(cursor.getInt(0), cursor.getString(1), displacementCc(variant), power)
    }

    private fun bound(query: CatalogueQuery, narrowing: CatalogueNarrowing): Bound {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        for (word in query.words) {
            clauses += "label LIKE ? ESCAPE '\\'"
            args += likePattern(word)
        }
        equate(clauses, args, "year", query.year?.toString())
        equate(clauses, args, "year", narrowing.year?.toString())
        equate(clauses, args, "fuel", narrowing.fuel)
        equate(clauses, args, "power_kw", narrowing.powerKw?.toString())
        return Bound(clauses.joinToString(" AND "), args.toTypedArray())
    }

    private fun equate(clauses: MutableList<String>, args: MutableList<String>, column: String, value: String?) {
        if (value == null) return
        clauses += "$column = ?"
        args += value
    }

    private fun readOne(db: SQLiteDatabase, id: String): CatalogueEntry? {
        db.rawQuery("$selectSql WHERE id = ?", arrayOf(id)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return readEntry(cursor)
        }
    }

    private fun readProvenance(db: SQLiteDatabase, source: String): CatalogueProvenance? {
        val map = loadMeta(db)
        val built = map["built_at"]?.toLongOrNull() ?: return null
        val url = map["url_$source"] ?: return null
        val protocol = map["protocol_$source"] ?: return null
        return CatalogueProvenance(built, url, protocol)
    }

    private fun loadMeta(db: SQLiteDatabase): Map<String, String> {
        val cached = cachedMeta
        if (cached != null) return cached
        val loaded = mutableMapOf<String, String>()
        db.rawQuery("SELECT key, value FROM meta", null).use { cursor ->
            while (cursor.moveToNext()) loaded[cursor.getString(0)] = cursor.getString(1)
        }
        cachedMeta = loaded
        return loaded
    }

    private fun readAll(cursor: Cursor): List<CatalogueEntry> {
        cursor.use { rows ->
            val found = mutableListOf<CatalogueEntry>()
            while (rows.moveToNext()) found += readEntry(rows)
            return found
        }
    }

    private fun readEntry(cursor: Cursor): CatalogueEntry {
        return CatalogueEntry(
            id = cursor.getString(0),
            source = cursor.getString(1),
            make = cursor.getString(2),
            model = cursor.getString(3),
            variant = cursor.getString(4),
            year = cursor.getInt(5),
            fuel = cursor.getString(6),
            powertrain = cursor.getString(7),
            testCycle = cursor.getString(8),
            measureKind = cursor.getString(9),
            combinedUnit = cursor.getString(10),
            combinedScale = cursor.getInt(11),
            combinedUnscaled = cursor.getLong(12),
            urbanUnscaled = optionalLong(cursor, 13),
            extraUnscaled = optionalLong(cursor, 14),
            cityUnscaled = optionalLong(cursor, 15),
            highwayUnscaled = optionalLong(cursor, 16),
            co2Unscaled = optionalLong(cursor, 17),
            co2GramsPerMile = cursor.getInt(18) == 1,
            powerKw = if (cursor.isNull(19)) null else cursor.getInt(19),
        )
    }

    private fun optionalLong(cursor: Cursor, index: Int): Long? {
        if (cursor.isNull(index)) return null
        return cursor.getLong(index)
    }

    private fun headerVersion(input: InputStream): Int {
        input.use { stream ->
            val header = ByteArray(64)
            if (!fill(stream, header)) return 0
            return readVersion(header)
        }
    }

    private fun fill(stream: InputStream, header: ByteArray): Boolean {
        var offset = 0
        while (offset < header.size) {
            val count = stream.read(header, offset, header.size - offset)
            if (count < 0) return false
            offset += count
        }
        return true
    }

    private fun readVersion(header: ByteArray): Int {
        return ((header[60].toInt() and 0xff) shl 24) or
            ((header[61].toInt() and 0xff) shl 16) or
            ((header[62].toInt() and 0xff) shl 8) or
            (header[63].toInt() and 0xff)
    }

    private data class Bound(val where: String, val args: Array<String>)

    private companion object {
        private val assetName = "official_catalogue.pack"
        private val fileName = "official_catalogue.db"
        private val selectSql = """
            SELECT id, source, make, model, variant, year, fuel, powertrain, test_cycle, measure_kind,
                   combined_unit, combined_scale, combined_unscaled,
                   urban_unscaled, extra_unscaled, city_unscaled, highway_unscaled,
                   co2_unscaled, co2_grams_per_mile, power_kw
            FROM models
        """
        private val orderSql = "CASE source WHEN 'ADEME' THEN 0 WHEN 'EEA' THEN 1 WHEN 'EPA' THEN 2 ELSE 3 END, year DESC"
    }
}
