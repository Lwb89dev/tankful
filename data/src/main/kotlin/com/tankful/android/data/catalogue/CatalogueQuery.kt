package com.tankful.android.data.catalogue

import java.text.Normalizer
import java.util.Locale

data class CatalogueQuery(val year: Int?, val words: List<String>)

data class CatalogueName(val label: String, val fold: String, val spellings: List<String>)

data class CatalogueDirectory(val makes: List<CatalogueName>, val models: Map<String, List<CatalogueName>>)

/** A chosen make and model. manual means the fuel has no official rows. */
data class CatalogueScope(
    val makeSpellings: List<String>,
    val modelSpellings: List<String>,
    val fuel: String? = null,
    val powertrain: String? = null,
    val manual: Boolean = false,
)

/** One row per folded name. The label is the spelling that appears most often. */
fun groupSpellings(counts: List<Pair<String, Int>>): List<CatalogueName> {
    val grouped = linkedMapOf<String, MutableList<Pair<String, Int>>>()
    for (pair in counts) {
        val fold = foldCatalogue(pair.first)
        if (fold.isEmpty()) continue
        grouped.getOrPut(fold) { mutableListOf() }.add(pair)
    }
    return grouped.map { (fold, pairs) -> spellingName(fold, pairs) }.sortedBy { it.fold }
}

private fun spellingName(fold: String, pairs: List<Pair<String, Int>>): CatalogueName {
    val best = pairs.maxWith(compareBy<Pair<String, Int>> { it.second }.thenByDescending { it.first.length })
    return CatalogueName(best.first, fold, pairs.map { it.first }.distinct())
}

/** Fold the same way as the catalogue builder: NFKD, drop marks, lowercase. */
fun foldCatalogue(raw: String): String {
    val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKD)
    val kept = StringBuilder(normalized.length)
    for (ch in normalized) {
        if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) kept.append(ch)
    }
    return kept.toString().lowercase(Locale.ROOT)
}

/**
 * A 4-digit token is a year filter. At least one other token of length 2 or more is required.
 * Returns null when the text cannot be a search.
 */
fun catalogueQuery(raw: String): CatalogueQuery? {
    val tokens = foldCatalogue(raw).split(Regex("\\s+")).filter { it.isNotEmpty() }
    var year: Int? = null
    val words = mutableListOf<String>()
    for (token in tokens) {
        if (year == null && token.length == 4 && token.all(Char::isDigit)) {
            year = token.toInt()
            continue
        }
        if (token.length >= 2) words += token
    }
    if (words.isEmpty()) return null
    return CatalogueQuery(year, words)
}

fun likePattern(token: String): String {
    val escaped = token.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    return "%$escaped%"
}

data class CatalogueFacet(
    val year: Int,
    val fuel: String,
    val displacementCc: Int?,
    val powerKw: Int?,
)

/** Null on a set field means the user chose every value of that field. */
data class CatalogueNarrowing(
    val year: Int? = null,
    val fuel: String? = null,
    val displacementCc: Int? = null,
    val powerKw: Int? = null,
)

data class CataloguePick(
    val yearSet: Boolean = false,
    val year: Int? = null,
    val fuelSet: Boolean = false,
    val fuel: String? = null,
    val displacementSet: Boolean = false,
    val displacementCc: Int? = null,
    val powerSet: Boolean = false,
    val powerKw: Int? = null,
) {
    val ready: Boolean = yearSet && fuelSet && displacementSet && powerSet

    fun narrowing() = CatalogueNarrowing(year, fuel, displacementCc, powerKw)

    fun chooseYear(value: Int?) = CataloguePick(yearSet = true, year = value)

    fun chooseFuel(value: String?) = copy(
        fuelSet = true,
        fuel = value,
        displacementSet = false,
        displacementCc = null,
        powerSet = false,
        powerKw = null,
    )

    fun chooseDisplacement(value: Int?) = copy(
        displacementSet = true,
        displacementCc = value,
        powerSet = false,
        powerKw = null,
    )

    fun choosePower(value: Int?) = copy(powerSet = true, powerKw = value)
}

data class CatalogueChoices(
    val years: List<Int>,
    val fuels: List<String>,
    val displacementsCc: List<Int>,
    val powersKw: List<Int>,
)

private val fuelOrder = listOf("PETROL", "DIESEL", "LPG", "CNG")
private val cubicPrefix = Regex("""^(\d{3,4}) cm3\b""")
private val litrePrefix = Regex("""^(\d+(?:\.\d+)?) L\b""")
private val cubicToken = Regex("""\b(\d{3,4})\b""")
private val litreToken = Regex("""\b(\d\.\d)\b""")

/**
 * Published displacement, in cubic centimetres.
 * EEA rows start with cm3. EPA rows start with litres. ADEME puts either a
 * cubic-centimetre token or a one-decimal litre token in the commercial name.
 */
fun displacementCc(variant: String): Int? {
    val text = variant.trim()
    if (text.isEmpty()) return null
    cubicPrefix.find(text)?.let { return it.groupValues[1].toInt() }
    litrePrefix.find(text)?.let { return litresToCc(it.groupValues[1]) }
    val token = firstCubicToken(text)
    if (token != null) return token
    val litre = litreToken.find(text) ?: return null
    return litresToCc(litre.groupValues[1])
}

fun catalogueChoices(rows: List<CatalogueFacet>, pick: CataloguePick): CatalogueChoices {
    val years = rows.map { it.year }.distinct().sortedDescending()
    val fuels = orderedFuels(applyPick(rows, pick.copy(fuelSet = false), false).map { it.fuel }.toSet())
    val displacements = applyPick(rows, pick.copy(displacementSet = false), false)
        .mapNotNull { it.displacementCc }
        .distinct()
        .sorted()
    val powers = applyPick(rows, pick, true).mapNotNull { it.powerKw }.distinct().sorted()
    return CatalogueChoices(years, fuels, displacements, powers)
}

/** Fill a step that has only one published value, and skip a step that has none. */
fun resolvePick(rows: List<CatalogueFacet>, pick: CataloguePick): CataloguePick {
    if (rows.isEmpty()) return pick
    var current = pick
    val years = rows.map { it.year }.distinct()
    if (!current.yearSet && years.size == 1) current = current.chooseYear(years.first())
    if (!current.yearSet) return current
    current = resolveFuel(rows, current)
    if (!current.fuelSet) return current
    current = resolveDisplacement(rows, current)
    if (!current.displacementSet) return current
    return resolvePower(rows, current)
}

private fun resolveFuel(rows: List<CatalogueFacet>, pick: CataloguePick): CataloguePick {
    val fuels = applyPick(rows, pick.copy(fuelSet = false), false).map { it.fuel }.distinct()
    if (!pick.fuelSet && fuels.size == 1) return pick.chooseFuel(fuels.first())
    return pick
}

private fun resolveDisplacement(rows: List<CatalogueFacet>, pick: CataloguePick): CataloguePick {
    val capacities = applyPick(rows, pick.copy(displacementSet = false), false)
        .mapNotNull { it.displacementCc }
        .distinct()
    if (!pick.displacementSet && capacities.isEmpty()) return pick.copy(displacementSet = true, displacementCc = null)
    if (!pick.displacementSet && capacities.size == 1) return pick.chooseDisplacement(capacities.first())
    return pick
}

private fun resolvePower(rows: List<CatalogueFacet>, pick: CataloguePick): CataloguePick {
    val powers = applyPick(rows, pick, true).mapNotNull { it.powerKw }.distinct()
    if (!pick.powerSet && powers.isEmpty()) return pick.copy(powerSet = true, powerKw = null)
    if (!pick.powerSet && powers.size == 1) return pick.choosePower(powers.first())
    return pick
}

private fun applyPick(rows: List<CatalogueFacet>, pick: CataloguePick, displacement: Boolean): List<CatalogueFacet> {
    val byYear = if (pick.yearSet && pick.year != null) rows.filter { it.year == pick.year } else rows
    val byFuel = if (pick.fuelSet && pick.fuel != null) byYear.filter { it.fuel == pick.fuel } else byYear
    if (!displacement || !pick.displacementSet || pick.displacementCc == null) return byFuel
    return byFuel.filter { it.displacementCc == pick.displacementCc }
}

private fun orderedFuels(fuels: Set<String>): List<String> {
    val known = fuelOrder.filter { it in fuels }
    return known + fuels.filter { it !in fuelOrder }.sorted()
}

private fun firstCubicToken(text: String): Int? {
    for (match in cubicToken.findAll(text)) {
        val value = match.groupValues[1].toInt()
        if (value in 600..10000) return value
    }
    return null
}

private fun litresToCc(raw: String): Int? {
    val litres = raw.toBigDecimalOrNull() ?: return null
    if (litres < java.math.BigDecimal("0.6") || litres > java.math.BigDecimal("9.0")) return null
    val cc = litres.multiply(java.math.BigDecimal(1000)).setScale(0, java.math.RoundingMode.HALF_UP)
    return cc.intValueExact()
}
