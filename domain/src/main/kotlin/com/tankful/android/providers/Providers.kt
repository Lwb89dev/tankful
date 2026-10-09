package com.tankful.android.providers

import com.tankful.android.model.FuelKind
import com.tankful.android.model.MeasureKind
import com.tankful.android.model.TestCycle
import com.tankful.android.money.TankfulRounding
import com.tankful.android.money.UnitPrice
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.LocalDate
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val EPA_BASE_URL: String = "https://www.fueleconomy.gov/ws/rest/vehicle/"

// www.mise.gov.it answers 301 to http://www.mimit.gov.it, and Android refuses that cleartext hop.
const val MIMIT_DATASET_URL: String =
    "https://www.mimit.gov.it/it/open-data/elenco-dataset/carburanti-prezzi-praticati-e-anagrafica-degli-impianti"
const val OVERPASS_INTERPRETER_URL: String = "https://overpass-api.de/api/interpreter"
const val DEFAULT_MAP_STYLE_URL: String = "https://tiles.openfreemap.org/styles/liberty"
const val TANKFUL_USER_AGENT: String = "Tankful Android/1"

const val STATION_QUERY_RADIUS_METRES: Int = 8000
const val STATION_QUERY_RETRY_METRES: Int = 15000
const val STATION_QUERY_MAX_METRES: Int = 200_000
const val STATION_RESULT_LIMIT: Int = 200
const val STATION_CACHE_TTL_MILLIS: Long = 7L * 24L * 60L * 60L * 1000L

const val PRICE_MATCH_METRES: Double = 300.0

data class EpaOption(val label: String, val remoteId: String)

data class EpaVehicleDraft(
    val remoteId: String,
    val fuelKind: FuelKind?,
    val measureKind: MeasureKind,
    val testCycle: TestCycle,
    val cityUnscaled: Long?,
    val highwayUnscaled: Long?,
    val combinedUnscaled: Long?,
    val co2GramsPerMile: Long?,
    val retrievalProtocol: String,
)

fun parseEpaMenu(xml: String): List<EpaOption> {
    val document = parseXml(xml)
    val nodes = document.getElementsByTagName("menuItem")
    val options = mutableListOf<EpaOption>()
    var index = 0
    while (index < nodes.length) {
        val element = nodes.item(index) as Element
        val text = childText(element, "text")
        val value = childText(element, "value")
        if (text != null && value != null) options += EpaOption(text, value)
        index += 1
    }
    return options
}

fun parseEpaVehicle(xml: String): EpaVehicleDraft {
    val document = parseXml(xml)
    val root = document.documentElement
    val remoteId = childText(root, "id").orEmpty()
    val combE = childText(root, "combE")?.toDoubleOrNull() ?: 0.0
    val comb = childText(root, "comb08")?.toLongOrNull()
    val onlyMpge = (comb == null || comb == 0L) && combE > 0.0
    val fuel = if (onlyMpge) null else mapEpaFuel(childText(root, "fuelType1"))
    val measure = if (onlyMpge) MeasureKind.MPGE else MeasureKind.LIQUID
    return EpaVehicleDraft(
        remoteId = remoteId,
        fuelKind = fuel,
        measureKind = measure,
        testCycle = TestCycle.EPA,
        cityUnscaled = if (onlyMpge) null else childText(root, "city08")?.toLongOrNull(),
        highwayUnscaled = if (onlyMpge) null else childText(root, "highway08")?.toLongOrNull(),
        combinedUnscaled = if (onlyMpge) null else comb,
        co2GramsPerMile = childText(root, "co2TailpipeGpm")?.toDoubleOrNull()?.toLong(),
        retrievalProtocol = "fueleconomy.gov/ws/rest",
    )
}

fun mapEpaFuel(fuelType1: String?): FuelKind? {
    val text = fuelType1?.lowercase().orEmpty()
    if ("gasoline" in text) return FuelKind.PETROL
    if ("diesel" in text) return FuelKind.DIESEL
    if ("natural gas" in text || text == "cng") return FuelKind.CNG
    if ("propane" in text || "lpg" in text) return FuelKind.LPG
    return null
}

data class PriceSuggestion(
    val label: String,
    val unitPrice: UnitPrice,
    val matchedStationName: String,
)

sealed interface PriceRead {
    data class Found(val suggestion: PriceSuggestion) : PriceRead
    data class Unavailable(val reason: String) : PriceRead
}

fun suggestMimitPrice(
    priceCsv: String,
    stationCsv: String,
    stationName: String,
    latitude: Double,
    longitude: Double,
    fuel: FuelKind,
    publishedOn: LocalDate,
): PriceRead {
    if (fuel == FuelKind.ELECTRIC || fuel == FuelKind.HYDROGEN) return PriceRead.Unavailable("no price")
    if (!inItaly(latitude, longitude)) return PriceRead.Unavailable("outside Italy")
    val stations = parsePipe(stationCsv)
    val station = stations.firstOrNull { row ->
        row["Nome Impianto"]?.equals(stationName, ignoreCase = true) == true
    } ?: return PriceRead.Unavailable("no station")
    val stationId = station["idImpianto"] ?: return PriceRead.Unavailable("no station")
    val prices = parsePipe(priceCsv)
    if (prices.isEmpty()) return PriceRead.Unavailable("empty price file")
    val header = prices.first().keys
    val dateColumn = mimitDateColumn(header) ?: return PriceRead.Unavailable("unexpected MIMIT file format")
    val fuelName = mimitFuelName(fuel)
    val row = prices.firstOrNull { item ->
        item["idImpianto"] == stationId && item["descCarburante"].equals(fuelName, ignoreCase = true)
    } ?: return PriceRead.Unavailable("no price")
    val date = row[dateColumn] ?: publishedOn.minusDays(1).toString()
    val prezzo = row["prezzo"] ?: return PriceRead.Unavailable("no price")
    val unscaled = prezzo.replace(',', '.').toBigDecimalOrNull()?.movePointRight(3)
        ?.setScale(0, TankfulRounding.mode)?.longValueExact()
        ?: return PriceRead.Unavailable("bad price")
    val suggestion = PriceSuggestion(
        label = "MIMIT price as of 08:00 on $date",
        unitPrice = UnitPrice.of("EUR", 3, unscaled, derived = false),
        matchedStationName = stationName,
    )
    return PriceRead.Found(suggestion)
}

fun mimitDateColumn(header: Set<String>): String? {
    val named = header.firstOrNull { it.equals("Data di estrazione", ignoreCase = true) }
    if (named != null) return named
    val dateColumns = header.filter { column ->
        val lower = column.lowercase()
        "data" in lower || "dtcomu" in lower
    }
    if (dateColumns.size == 1) return dateColumns.first()
    if (dateColumns.isEmpty()) return ""
    return null
}

private fun mimitFuelName(fuel: FuelKind): String {
    return when (fuel) {
        FuelKind.PETROL -> "Benzina"
        FuelKind.DIESEL -> "Gasolio"
        FuelKind.LPG -> "GPL"
        FuelKind.CNG -> "Metano"
        FuelKind.ELECTRIC -> ""
        FuelKind.HYDROGEN -> ""
    }
}

private fun inItaly(latitude: Double, longitude: Double): Boolean {
    return latitude in 35.0..47.5 && longitude in 6.0..19.0
}

data class MimitCsvUrls(val prices: String, val stations: String?)

fun mimitCsvUrls(pageHtml: String, pageUrl: String): MimitCsvUrls? {
    val links = resolveMimitCsvLinks(pageHtml, pageUrl)
    val prices = links.firstOrNull { "prezz" in it.lowercase() } ?: return null
    val stations = links.firstOrNull { link ->
        val lower = link.lowercase()
        link != prices && ("anagraf" in lower || "impiant" in lower)
    }
    return MimitCsvUrls(prices, stations)
}

fun resolveMimitCsvLinks(pageHtml: String, pageUrl: String): List<String> {
    val origin = httpsOrigin(pageUrl) ?: return emptyList()
    return MIMIT_CSV_LINK.findAll(pageHtml).mapNotNull { match ->
        absoluteHttps(match.groupValues[1].trim(), origin)
    }.toList()
}

private fun httpsOrigin(pageUrl: String): String? {
    if (!pageUrl.startsWith("https://")) return null
    val host = pageUrl.removePrefix("https://").substringBefore('/').substringBefore('?')
    if (host.isEmpty()) return null
    return "https://$host"
}

private fun absoluteHttps(href: String, origin: String): String? {
    if (href.startsWith("https://")) return href
    if (href.startsWith("/")) return origin + href
    return null
}

private fun parsePipe(csv: String): List<Map<String, String>> {
    val lines = csv.trim().removePrefix("\uFEFF").lines().filter { it.isNotBlank() }
    val headerAt = lines.indexOfFirst { '|' in it }
    if (headerAt < 0) return emptyList()
    val header = lines[headerAt].split('|').map { it.trim() }
    return lines.drop(headerAt + 1).map { line ->
        val cells = line.split('|')
        header.mapIndexed { index, name -> name to cells.getOrElse(index) { "" }.trim() }.toMap()
    }
}

private val MIMIT_CSV_LINK = Regex("""href="([^"]+\.csv[^"]*)"""", RegexOption.IGNORE_CASE)

data class PostedPrice(
    val fuel: FuelKind,
    val euros: String,
    val asOf: String,
)

data class PricedStation(
    val station: OsmStation,
    val prices: List<PostedPrice>,
)

fun fuelsOnCard(station: OsmStation, prices: List<PostedPrice>): List<FuelKind> {
    val tagged = station.fuelsSold.mapNotNull { osmFuelKind(it) }.toSet()
    val posted = prices.map { it.fuel }.toSet()
    return FuelKind.entries.filter { it in posted || it in tagged }
}

class MimitBoard private constructor(
    private val places: List<PumpPlace>,
    private val rows: Map<String, List<Map<String, String>>>,
    private val asOf: String,
) {
    fun pricesAt(latitude: Double, longitude: Double): List<PostedPrice> {
        val place = nearestPlace(places, latitude, longitude) ?: return emptyList()
        val found = rows[place.id].orEmpty()
        return FuelKind.entries.mapNotNull { fuel -> postedPrice(fuel, found, asOf) }
    }

    companion object {
        fun read(priceCsv: String, stationCsv: String, publishedOn: LocalDate): MimitBoard {
            val stations = parsePipe(stationCsv)
            val prices = parsePipe(priceCsv)
            val header = prices.firstOrNull()?.keys.orEmpty()
            val dateColumn = mimitDateColumn(header)
            val stamped = prices.firstNotNullOfOrNull { row -> dateColumn?.let { row[it] }?.takeIf { it.isNotBlank() } }
            val asOf = stamped ?: publishedOn.toString()
            val places = stations.mapNotNull { placeOf(it) }
            return MimitBoard(places, prices.groupBy { it["idImpianto"].orEmpty() }, asOf)
        }
    }
}

private data class PumpPlace(val id: String, val latitude: Double, val longitude: Double)

fun readMimitBoard(priceCsv: String, stationCsv: String, publishedOn: LocalDate): MimitBoard {
    return MimitBoard.read(priceCsv, stationCsv, publishedOn)
}

fun osmFuelKind(key: String): FuelKind? {
    if (key == "fuel:lpg" || key == "fuel:propane") return FuelKind.LPG
    if (key == "fuel:cng" || key == "fuel:lng") return FuelKind.CNG
    if (key == "fuel:diesel" || key == "fuel:hgv_diesel" || key == "fuel:biodiesel") return FuelKind.DIESEL
    if (key == "fuel:electricity" || key == "fuel:electric") return FuelKind.ELECTRIC
    if (key == "fuel:hydrogen" || key == "fuel:h2") return FuelKind.HYDROGEN
    if (key.startsWith("fuel:octane") || key == "fuel:e10" || key == "fuel:e5") return FuelKind.PETROL
    return null
}

private fun tagText(tags: JsonObject?, key: String): String? {
    val element = tags?.get(key) ?: return null
    return runCatching { element.jsonPrimitive.content }.getOrNull()
}

private fun coordinateOf(direct: JsonElement?, center: JsonObject?, name: String): Double? {
    val own = direct?.jsonPrimitive?.content?.toDoubleOrNull()
    if (own != null) return own
    return center?.get(name)?.jsonPrimitive?.content?.toDoubleOrNull()
}

/** A missing Overpass coordinate used to become 0,0, which is the Gulf of Guinea. */
fun stationOnMap(latitude: Double, longitude: Double): Boolean {
    if (latitude == 0.0 && longitude == 0.0) return false
    if (!latitude.isFinite() || latitude !in -90.0..90.0) return false
    return longitude.isFinite() && longitude in -180.0..180.0
}

private fun placeOf(row: Map<String, String>): PumpPlace? {
    val id = row["idImpianto"] ?: return null
    val latitude = coordinate(row["Latitudine"]) ?: return null
    val longitude = coordinate(row["Longitudine"]) ?: return null
    return PumpPlace(id, latitude, longitude)
}

private fun coordinate(raw: String?): Double? {
    if (raw.isNullOrBlank()) return null
    return raw.trim().replace(',', '.').toDoubleOrNull()
}

private fun nearestPlace(places: List<PumpPlace>, latitude: Double, longitude: Double): PumpPlace? {
    var best: PumpPlace? = null
    var bestMetres = PRICE_MATCH_METRES
    for (place in places) {
        val metres = metresBetween(latitude, longitude, place.latitude, place.longitude)
        if (metres > bestMetres) continue
        best = place
        bestMetres = metres
    }
    return best
}

private fun metresBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val dPhi = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dPhi / 2) * sin(dPhi / 2) + cos(phi1) * cos(phi2) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * 6_371_000.0 * asin(sqrt(a))
}

private fun postedPrice(fuel: FuelKind, rows: List<Map<String, String>>, asOf: String): PostedPrice? {
    val matches = rows.filter { fuelOfMimit(it["descCarburante"].orEmpty()) == fuel }
    val row = matches.firstOrNull { it["isSelf"] == "1" } ?: matches.firstOrNull() ?: return null
    val unscaled = row["prezzo"]?.replace(',', '.')?.toBigDecimalOrNull()
        ?.movePointRight(3)?.setScale(0, TankfulRounding.mode)?.longValueExact() ?: return null
    return PostedPrice(fuel, BigDecimal.valueOf(unscaled, 3).toPlainString(), asOf)
}

private fun fuelOfMimit(name: String): FuelKind? {
    val text = name.trim().lowercase()
    if (text == "benzina") return FuelKind.PETROL
    if (text == "gasolio") return FuelKind.DIESEL
    if (text == "gpl") return FuelKind.LPG
    if (text == "metano") return FuelKind.CNG
    if (text.startsWith("elettr")) return FuelKind.ELECTRIC
    if ("idrogen" in text) return FuelKind.HYDROGEN
    return null
}

data class OsmStation(
    val osmType: String,
    val osmId: Long,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val fuelsSold: Set<String>,
    val fuelUnknown: Boolean,
    val address: String = "",
)

/** Street and town from OpenStreetMap addr tags. An empty string means the node has none. */
fun stationAddress(full: String?, street: String?, housenumber: String?, postcode: String?, city: String?): String {
    val written = full?.trim().orEmpty()
    if (written.isNotEmpty()) return written
    val road = listOf(street, housenumber).map { it?.trim().orEmpty() }.filter { it.isNotEmpty() }.joinToString(" ")
    val town = listOf(postcode, city).map { it?.trim().orEmpty() }.filter { it.isNotEmpty() }.joinToString(" ")
    return listOf(road, town).filter { it.isNotEmpty() }.joinToString(", ")
}

fun parseOverpass(json: String): List<OsmStation> {
    val root = Json.parseToJsonElement(json).jsonObject
    val elements = root["elements"]?.jsonArray ?: return emptyList()
    return elements.mapNotNull { element -> stationFrom(element.jsonObject) }
}

private fun stationFrom(objectNode: JsonObject): OsmStation? {
    val tags = objectNode["tags"]?.jsonObject
    val center = objectNode["center"]?.jsonObject
    val latitude = coordinateOf(objectNode["lat"], center, "lat") ?: return null
    val longitude = coordinateOf(objectNode["lon"], center, "lon") ?: return null
    if (!stationOnMap(latitude, longitude)) return null
    val amenity = tags?.get("amenity")?.jsonPrimitive?.content
    val fuelKeys = tags?.keys?.filter { key ->
        key.startsWith("fuel:") && tags[key]?.jsonPrimitive?.content == "yes"
    }.orEmpty().toMutableSet()
    if (amenity == "charging_station") fuelKeys += "fuel:electricity"
    val title = tags?.get("brand")?.jsonPrimitive?.content
        ?: tags?.get("operator")?.jsonPrimitive?.content
        ?: tags?.get("name")?.jsonPrimitive?.content
        ?: "Fuel"
    return OsmStation(
        osmType = objectNode["type"]?.jsonPrimitive?.content ?: "node",
        osmId = objectNode["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
        name = title,
        latitude = latitude,
        longitude = longitude,
        fuelsSold = fuelKeys,
        fuelUnknown = fuelKeys.isEmpty(),
        address = stationAddress(
            tagText(tags, "addr:full"),
            tagText(tags, "addr:street"),
            tagText(tags, "addr:housenumber"),
            tagText(tags, "addr:postcode"),
            tagText(tags, "addr:city"),
        ),
    )
}

fun shouldQueryStations(explicitSearch: Boolean, cacheAgeMillis: Long?): Boolean {
    if (!explicitSearch) return false
    if (cacheAgeMillis == null) return true
    return cacheAgeMillis >= STATION_CACHE_TTL_MILLIS
}

fun nextStationRadius(previousMetres: Int?): Int {
    if (previousMetres == null) return STATION_QUERY_RADIUS_METRES
    if (previousMetres < STATION_QUERY_RETRY_METRES) return STATION_QUERY_RETRY_METRES
    return STATION_QUERY_RETRY_METRES
}

fun stationQueryRadius(metres: Int): Int {
    return metres.coerceIn(STATION_QUERY_RADIUS_METRES, STATION_QUERY_MAX_METRES)
}

/**
 * Radius that covers one visible corner. A tight view stays at 8 km.
 * A zoomed-out view grows with the map, up to 200 km.
 */
fun visibleSearchRadiusMetres(
    centreLatitude: Double,
    centreLongitude: Double,
    cornerLatitude: Double,
    cornerLongitude: Double,
): Int {
    if (!centreLatitude.isFinite() || !centreLongitude.isFinite()) return STATION_QUERY_RADIUS_METRES
    if (!cornerLatitude.isFinite() || !cornerLongitude.isFinite()) return STATION_QUERY_RADIUS_METRES
    if (cornerLatitude !in -85.0..85.0 || cornerLongitude !in -180.0..180.0) return STATION_QUERY_MAX_METRES
    val metres = metresBetween(centreLatitude, centreLongitude, cornerLatitude, cornerLongitude).toInt()
    return stationQueryRadius(metres)
}

private fun parseXml(xml: String): org.w3c.dom.Document {
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    val builder = factory.newDocumentBuilder()
    return builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
}

private fun childText(element: Element, name: String): String? {
    val children = element.childNodes
    var index = 0
    while (index < children.length) {
        val node = children.item(index)
        if (node is Element && node.tagName == name) return node.textContent
        index += 1
    }
    return null
}

private fun String.toBigDecimalOrNull(): BigDecimal? = try {
    BigDecimal(this)
} catch (exception: NumberFormatException) {
    null
}
