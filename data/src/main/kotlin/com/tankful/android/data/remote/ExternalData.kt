package com.tankful.android.data.remote

import android.content.Context
import com.tankful.android.data.BuildConfig
import com.tankful.android.data.db.StationCacheEntity
import com.tankful.android.data.db.TankfulDatabase
import com.tankful.android.model.FuelKind
import com.tankful.android.money.TankfulRounding
import com.tankful.android.providers.EPA_BASE_URL
import com.tankful.android.providers.EpaOption
import com.tankful.android.providers.EpaVehicleDraft
import com.tankful.android.providers.MIMIT_DATASET_URL
import com.tankful.android.providers.mimitCsvUrls
import com.tankful.android.providers.OVERPASS_INTERPRETER_URL
import com.tankful.android.providers.publicHttpsHost
import com.tankful.android.transfer.readCapped
import com.tankful.android.providers.OsmStation
import com.tankful.android.providers.PricedStation
import com.tankful.android.providers.PriceRead
import com.tankful.android.providers.readMimitBoard
import com.tankful.android.providers.parseEpaMenu
import com.tankful.android.providers.parseEpaVehicle
import com.tankful.android.providers.parseOverpass
import com.tankful.android.providers.shouldQueryStations
import com.tankful.android.providers.stationOnMap
import com.tankful.android.providers.stationQueryRadius
import com.tankful.android.providers.STATION_QUERY_RETRY_METRES
import com.tankful.android.providers.STATION_RESULT_LIMIT
import com.tankful.android.providers.suggestMimitPrice
import com.tankful.android.sync.endpointUsesRawSocket
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.io.File
import java.math.BigDecimal
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody

@Singleton
class ExternalData @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: TankfulDatabase,
    private val http: OkHttpClient,
) {
    fun epaYears(): List<EpaOption> = parseEpaMenu(get(EPA_BASE_URL + "menu/year"))

    fun epaMakes(year: String): List<EpaOption> {
        return parseEpaMenu(get(EPA_BASE_URL + "menu/make?year=" + encode(year)))
    }

    fun epaModels(year: String, make: String): List<EpaOption> {
        val url = EPA_BASE_URL + "menu/model?year=" + encode(year) + "&make=" + encode(make)
        return parseEpaMenu(get(url))
    }

    fun epaOptions(year: String, make: String, model: String): List<EpaOption> {
        val url = EPA_BASE_URL + "menu/options?year=" + encode(year) +
            "&make=" + encode(make) + "&model=" + encode(model)
        return parseEpaMenu(get(url))
    }

    fun epaVehicle(id: String): EpaVehicleDraft = parseEpaVehicle(get(EPA_BASE_URL + encode(id)))

    fun mimitSuggestion(
        stationName: String,
        latitude: Double,
        longitude: Double,
        fuel: FuelKind,
    ): PriceRead {
        val files = loadMimit() ?: return PriceRead.Unavailable("no price file")
        return suggestMimitPrice(
            priceCsv = files.prices,
            stationCsv = files.stations,
            stationName = stationName,
            latitude = latitude,
            longitude = longitude,
            fuel = fuel,
            publishedOn = files.publishedOn,
        )
    }

    suspend fun stations(latitude: Double, longitude: Double, radiusMetres: Int): List<OsmStation> {
        val radius = stationQueryRadius(radiusMetres)
        val centre = round2(latitude) to round2(longitude)
        val cached = database.stations().around(centre.first, centre.second)
        val newest = cached.maxOfOrNull { it.fetchedAt }
        val age = newest?.let { System.currentTimeMillis() - it }
        val fresh = cached.isNotEmpty() && !shouldQueryStations(explicitSearch = true, cacheAgeMillis = age)
        if (fresh && cacheCovers(cached, radius)) return cached.map { it.toStation() }
        if (endpointUsesRawSocket("overpass")) error("overpass has no raw socket")
        val first = fetchStations(latitude, longitude, radius, centre)
        if (first.isNotEmpty() || radius >= STATION_QUERY_RETRY_METRES) return first
        return fetchStations(latitude, longitude, STATION_QUERY_RETRY_METRES, centre)
    }

    private suspend fun fetchStations(
        latitude: Double,
        longitude: Double,
        radius: Int,
        centre: Pair<Double, Double>,
    ): List<OsmStation> {
        // Way lookups time out on the public interpreter and fail the search even when fuel nodes exist.
        val query = """
            [out:json][timeout:25];
            (
              node["amenity"="fuel"](around:$radius,$latitude,$longitude);
              node["amenity"="charging_station"](around:$radius,$latitude,$longitude);
            );
            out body $STATION_RESULT_LIMIT;
        """.trimIndent()
        val body = query.toRequestBody("text/plain".toMediaType())
        val request = Request.Builder()
            .url(OVERPASS_INTERPRETER_URL)
            .header("User-Agent", userAgent())
            .post(body)
            .build()
        val json = execute(request)
        val stations = parseOverpass(json)
        val now = System.currentTimeMillis()
        database.stations().clearAround(centre.first, centre.second)
        database.stations().insert(stations.map { station -> station.toEntity(centre, now, radius) })
        return stations
    }

    fun attachPrices(stations: List<OsmStation>): List<PricedStation> {
        val files = loadMimit() ?: return stations.map { PricedStation(it, emptyList()) }
        val board = readMimitBoard(files.prices, files.stations, files.publishedOn)
        return stations.map { station -> PricedStation(station, board.pricesAt(station.latitude, station.longitude)) }
    }

    private fun loadMimit(): MimitFiles? {
        val remembered = rememberedBoard()
        if (remembered != null) return remembered
        val page = readHttps(MIMIT_DATASET_URL) ?: return null
        val urls = mimitCsvUrls(page.text, page.finalUrl) ?: return null
        val prices = downloadCached(urls.prices) ?: return null
        val stationUrl = urls.stations
        val stations = stationText(stationUrl, prices.text) ?: return null
        rememberUrl("prices.url", urls.prices)
        if (stationUrl != null) rememberUrl("stations.url", stationUrl)
        return MimitFiles(prices.text, stations, prices.publishedOn)
    }

    private fun rememberedBoard(): MimitFiles? {
        val pricesUrl = rememberedUrl("prices.url") ?: return null
        val prices = downloadCached(pricesUrl) ?: return null
        val stationUrl = rememberedUrl("stations.url")
        val stations = if (stationUrl == null) prices else downloadCached(stationUrl)
        if (stations == null) return null
        return MimitFiles(prices.text, stations.text, prices.publishedOn)
    }

    private fun downloadCached(url: String): CachedText? {
        if (!httpsUrl(url)) return null
        val dir = File(context.filesDir, "mimit").apply { mkdirs() }
        val file = File(dir, sha(url) + ".csv")
        val meta = File(dir, sha(url) + ".date")
        val fetched = File(dir, sha(url) + ".fetched")
        val today = LocalDate.now().toString()
        if (file.isFile && meta.isFile && fetched.isFile && fetched.readText().trim() == today) {
            val published = runCatching { LocalDate.parse(meta.readText().trim()) }.getOrNull()
            if (published != null) return CachedText(file.readText(), published)
        }
        val request = Request.Builder().url(url).header("User-Agent", userAgent()).build()
        val response = openPublic(request) ?: return null
        response.use { open ->
            if (!open.isSuccessful) return null
            val text = readBody(open.body, CSV_BODY_CAP) ?: return null
            if (!text.contains('|')) return null
            val published = publicationDate(url, open.header("Last-Modified"))
                ?: extractionDate(text)
                ?: return null
            file.writeText(text)
            meta.writeText(published.toString())
            fetched.writeText(today)
            return CachedText(text, published)
        }
    }

    private fun stationText(stationUrl: String?, priceText: String): String? {
        if (stationUrl == null) return priceText
        return downloadCached(stationUrl)?.text
    }

    private fun readHttps(url: String): HttpsBody? {
        if (!httpsUrl(url)) return null
        if (endpointUsesRawSocket(url)) return null
        val request = Request.Builder().url(url).header("User-Agent", userAgent()).build()
        val response = openPublic(request) ?: return null
        response.use { open ->
            if (!open.isSuccessful) return null
            val text = readBody(open.body, PUBLIC_BODY_CAP) ?: return null
            return HttpsBody(text, open.request.url.toString())
        }
    }

    private fun get(url: String): String {
        if (!httpsUrl(url)) error("https required")
        if (endpointUsesRawSocket(url)) error("raw socket refused")
        val request = Request.Builder().url(url).header("User-Agent", userAgent()).build()
        return execute(request)
    }

    private fun execute(request: Request): String {
        val response = openPublic(request) ?: error("https required")
        response.use { open ->
            if (!open.isSuccessful) error("http ${open.code}")
            return readBody(open.body, PUBLIC_BODY_CAP) ?: error("http body")
        }
    }

    private fun rememberedUrl(name: String): String? {
        val file = File(File(context.filesDir, "mimit"), name)
        if (!file.isFile) return null
        val url = file.readText().trim()
        if (!httpsUrl(url)) return null
        return url
    }

    private fun rememberUrl(name: String, url: String) {
        if (!httpsUrl(url)) return
        val dir = File(context.filesDir, "mimit").apply { mkdirs() }
        File(dir, name).writeText(url)
    }

    private fun openPublic(request: Request): Response? {
        var current = request
        var redirects = 0
        while (redirects <= 3) {
            val response = followOne(current) ?: return null
            if (response.code !in 300..399) return response
            val next = redirected(current, response)
            response.close()
            if (next == null) return null
            current = next
            redirects += 1
        }
        return null
    }

    private fun followOne(request: Request): Response? {
        if (!httpsAllowed(request.url)) return null
        return http.newCall(request).execute()
    }

    private fun redirected(current: Request, response: Response): Request? {
        val location = response.header("Location") ?: return null
        val resolved = current.url.resolve(location) ?: return null
        if (!httpsAllowed(resolved)) return null
        return current.newBuilder().url(resolved).build()
    }

    private fun httpsAllowed(url: HttpUrl): Boolean {
        if (url.scheme != "https") return false
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        return publicHttpsHost(url.host)
    }

    private fun httpsUrl(url: String): Boolean {
        if (url.length > 500) return false
        val parsed = url.toHttpUrlOrNull() ?: return false
        return httpsAllowed(parsed)
    }

    private fun readBody(body: ResponseBody?, cap: Int): String? {
        if (body == null) return null
        val bytes = body.byteStream().use { readCapped(it, cap) } ?: return null
        return bytes.toString(Charsets.UTF_8)
    }

    private fun userAgent(): String = "Tankful Android/" + BuildConfig.APP_VERSION

    private data class MimitFiles(val prices: String, val stations: String, val publishedOn: LocalDate)

    private data class CachedText(val text: String, val publishedOn: LocalDate)

    private data class HttpsBody(val text: String, val finalUrl: String)
}

private const val PUBLIC_BODY_CAP = 2 * 1024 * 1024
private const val CSV_BODY_CAP = 16 * 1024 * 1024

private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

private fun sha(value: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }.take(16)
}

private fun publicationDate(url: String, lastModified: String?): LocalDate? {
    val inUrl = DATE_IN_TEXT.find(url)?.value
    if (inUrl != null) return LocalDate.parse(inUrl)
    if (lastModified == null) return null
    val parsed = runCatching {
        java.time.ZonedDateTime.parse(lastModified, DateTimeFormatter.RFC_1123_DATE_TIME)
    }.getOrNull() ?: return null
    return parsed.toLocalDate()
}

private fun extractionDate(text: String): LocalDate? {
    val line = text.lineSequence().map { it.trim().removePrefix("\uFEFF") }.firstOrNull { it.isNotEmpty() }
        ?: return null
    if ('|' in line) return null
    val found = DATE_IN_TEXT.find(line)?.value ?: return null
    return runCatching { LocalDate.parse(found) }.getOrNull()
}

private fun round2(value: Double): Double {
    return BigDecimal.valueOf(value).setScale(2, TankfulRounding.mode).toDouble()
}

private fun OsmStation.toEntity(centre: Pair<Double, Double>, fetchedAt: Long, radius: Int): StationCacheEntity {
    val tags = buildJsonObject {
        put("name", name)
        put("fuels", fuelsSold.joinToString(","))
        put("unknown", fuelUnknown)
        put("address", address)
        put("radius", radius)
    }
    return StationCacheEntity(
        osmType = osmType,
        osmId = osmId,
        name = name,
        latitude = latitude,
        longitude = longitude,
        tagsJson = tags.toString(),
        centreLat = centre.first,
        centreLon = centre.second,
        fetchedAt = fetchedAt,
    )
}

private fun StationCacheEntity.toStation(): OsmStation {
    val tags = runCatching { Json.parseToJsonElement(tagsJson).jsonObject }.getOrNull()
    val fuels = tags?.get("fuels")?.jsonPrimitive?.content.orEmpty().split(',').filter { it.isNotBlank() }.toSet()
    val unknown = tags?.get("unknown")?.jsonPrimitive?.booleanOrNull ?: fuels.isEmpty()
    val address = tags?.get("address")?.jsonPrimitive?.content.orEmpty()
    return OsmStation(
        osmType = osmType,
        osmId = osmId,
        name = name,
        latitude = latitude,
        longitude = longitude,
        fuelsSold = fuels,
        fuelUnknown = unknown,
        address = address,
    )
}

/**
 * A row is reusable when it has an address, a real position, and a radius at
 * least as wide as the map the user is looking at.
 */
private fun cacheCovers(rows: List<StationCacheEntity>, radius: Int): Boolean {
    if (!rows.all { row -> "\"address\"" in row.tagsJson && stationOnMap(row.latitude, row.longitude) }) return false
    val stored = cachedRadius(rows.first().tagsJson) ?: return false
    return stored >= radius
}

private fun cachedRadius(tagsJson: String): Int? {
    val marker = "\"radius\":"
    val start = tagsJson.indexOf(marker)
    if (start < 0) return null
    val digits = tagsJson.substring(start + marker.length).takeWhile { it.isDigit() }
    return digits.toIntOrNull()
}

private val DATE_IN_TEXT = Regex("""\d{4}-\d{2}-\d{2}""")
