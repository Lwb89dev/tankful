package com.tankful.android.ui

import com.tankful.android.consumption.OfficialFigure
import com.tankful.android.consumption.scaledOneDecimal
import com.tankful.android.consumption.toFuelPerDistance
import com.tankful.android.data.catalogue.CatalogueEntry
import com.tankful.android.model.EconomyPreference
import com.tankful.android.model.FuelKind
import java.math.BigDecimal
import java.math.RoundingMode

/** Metric horsepower from the published kilowatt figure. 1 CV = 0.73549875 kW. */
fun metricHorsepower(kilowatts: Int): Int {
    val ps = BigDecimal(kilowatts).divide(BigDecimal("0.73549875"), 0, RoundingMode.HALF_UP)
    return ps.intValueExact()
}

fun cataloguePower(kilowatts: Int): String = "$kilowatts kW · ${metricHorsepower(kilowatts)} CV"

fun catalogueNumbers(entry: CatalogueEntry): String {
    val combined = formatStored(entry.combinedUnit, entry.combinedScale, entry.combinedUnscaled)
    val litres = litresBeside(entry)
    val head = if (litres == null) combined else "$combined · $litres"
    val co2 = co2Text(entry) ?: return head
    return "$head · $co2"
}

private fun litresBeside(entry: CatalogueEntry): String? {
    if (entry.combinedUnit == EconomyPreference.L_PER_100KM.name) return null
    val fuel = runCatching { FuelKind.valueOf(entry.fuel) }.getOrNull() ?: return null
    val unit = runCatching { EconomyPreference.valueOf(entry.combinedUnit) }.getOrNull() ?: return null
    val figure = OfficialFigure(unit, entry.combinedScale, entry.combinedUnscaled)
    val litres = toFuelPerDistance(fuel, figure) ?: return null
    val scaled = scaledOneDecimal(litres)
    return formatStored(EconomyPreference.L_PER_100KM.name, scaled.scale, scaled.unscaled)
}

private fun co2Text(entry: CatalogueEntry): String? {
    val grams = entry.co2Unscaled ?: return null
    val unit = if (entry.co2GramsPerMile) "g/mi" else "g/km"
    return "$grams $unit"
}

private fun formatStored(unit: String, scale: Int, unscaled: Long): String {
    val text = BigDecimal.valueOf(unscaled).movePointLeft(scale).toPlainString()
    return "$text ${unitSymbol(unit)}"
}

private fun unitSymbol(unit: String): String {
    if (unit == EconomyPreference.L_PER_100KM.name) return "L/100 km"
    if (unit == EconomyPreference.KG_PER_100KM.name) return "kg/100 km"
    if (unit == EconomyPreference.US_MPG.name || unit == EconomyPreference.UK_MPG.name) return "mpg"
    return unit
}
