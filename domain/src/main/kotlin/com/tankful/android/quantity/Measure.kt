package com.tankful.android.quantity

import com.tankful.android.model.DistanceEntryUnit
import com.tankful.android.model.FuelKind
import com.tankful.android.money.MAJOR_CURRENCIES
import com.tankful.android.money.TankfulRounding
import com.tankful.android.money.currencyMinorScale
import com.tankful.android.money.deriveUnitPriceUnscaled
import java.math.BigDecimal
import java.util.Locale

const val MEASURE_METRIC: String = "METRIC"
const val MEASURE_IMPERIAL: String = "IMPERIAL"

fun measureForLocale(locale: Locale): String {
    if (locale.country == "US") return MEASURE_IMPERIAL
    return MEASURE_METRIC
}

fun currencyForLocale(locale: Locale): String {
    val code = runCatching { java.util.Currency.getInstance(locale).currencyCode }.getOrNull()
    if (code != null && code in MAJOR_CURRENCIES) return code
    if (locale.country == "US") return "USD"
    return "EUR"
}

data class StoredFill(
    val smallest: Long,
    val unit: QuantityUnit,
    val priceUnscaled: Long,
    val totalMinor: Long,
)

fun fillTotalText(quantityRaw: String, priceRaw: String, currency: String, locale: Locale): String {
    val minor = productMinor(quantityRaw, priceRaw, currency, locale) ?: return ""
    val scale = currencyMinorScale(currency)
    return BigDecimal.valueOf(minor).movePointLeft(scale).toPlainString()
}

fun storedFill(
    quantityRaw: String,
    priceRaw: String,
    fuel: FuelKind,
    currency: String,
    imperial: Boolean,
    locale: Locale,
): StoredFill? {
    val totalMinor = productMinor(quantityRaw, priceRaw, currency, locale) ?: return null
    val smallest = smallestQuantity(quantityRaw, fuel, imperial, locale) ?: return null
    if (smallest <= 0L) return null
    val scale = currencyMinorScale(currency)
    val priceUnscaled = deriveUnitPriceUnscaled(totalMinor, smallest, 3, scale)
    return StoredFill(smallest, quantityUnitFor(fuel), priceUnscaled, totalMinor)
}

fun smallestQuantity(raw: String, fuel: FuelKind, imperial: Boolean, locale: Locale): Long? {
    if (fuel == FuelKind.CNG) return gramsFromKilograms(raw, locale)
    if (!imperial) return millilitresFromEntry(raw, locale)
    val parsed = parseDecimal(raw, 3, locale) ?: return null
    val unscaled = parsed.movePointRight(3).setScale(0, TankfulRounding.mode).longValueExact()
    return millilitresFromGallons(unscaled, 3, unitedStates = true)
}

fun odometerMetres(raw: String, imperial: Boolean, locale: Locale): Long? {
    val parsed = parseDecimal(raw, 0, locale) ?: return null
    val unit = if (imperial) DistanceEntryUnit.MI_WHOLE else DistanceEntryUnit.KM_WHOLE
    return runCatching { metresFromEntry(unit, parsed.longValueExact(), 0) }.getOrNull()
}

fun odometerText(metres: Long, imperial: Boolean): String {
    val major = if (!imperial) {
        BigDecimal.valueOf(metres).divide(BigDecimal("1000"), 0, TankfulRounding.mode)
    } else {
        val mile = FuelUnits.mileKilometres.multiply(BigDecimal("1000"))
        BigDecimal.valueOf(metres).divide(mile, 0, TankfulRounding.mode)
    }
    val unit = if (imperial) "mi" else "km"
    return "${major.toPlainString()} $unit"
}

fun quantityEntryText(smallest: Long, fuel: FuelKind, imperial: Boolean): String {
    if (smallest < 0L) return ""
    if (fuel == FuelKind.CNG || !imperial) return plainAmount(BigDecimal.valueOf(smallest).movePointLeft(3))
    val litres = BigDecimal.valueOf(smallest).movePointLeft(3)
    val gallons = litres.divide(FuelUnits.usGallonLitres, 3, TankfulRounding.mode)
    return plainAmount(gallons)
}

fun priceEntryText(
    totalMinor: Long,
    smallest: Long,
    fuel: FuelKind,
    currency: String,
    imperial: Boolean,
): String {
    val quantity = parseDecimal(quantityEntryText(smallest, fuel, imperial), 3, Locale.US) ?: return ""
    if (quantity.signum() == 0) return ""
    val total = BigDecimal.valueOf(totalMinor).movePointLeft(currencyMinorScale(currency))
    return total.divide(quantity, 3, TankfulRounding.mode).toPlainString()
}

fun odometerEntryText(metres: Long, imperial: Boolean): String {
    return odometerText(metres, imperial).substringBefore(' ')
}

fun distancePerMoney(spanMetres: Long, minor: Long, currency: String, imperial: Boolean): String? {
    if (spanMetres <= 0L || minor <= 0L) return null
    val scale = currencyMinorScale(currency)
    val money = BigDecimal.valueOf(minor).movePointLeft(scale)
    if (money.signum() == 0) return null
    val distance = if (imperial) {
        val mile = FuelUnits.mileKilometres.multiply(BigDecimal("1000"))
        BigDecimal.valueOf(spanMetres).divide(mile, 6, TankfulRounding.mode)
    } else {
        BigDecimal.valueOf(spanMetres).movePointLeft(3)
    }
    val ratio = distance.divide(money, 1, TankfulRounding.mode)
    val unit = if (imperial) "mi" else "km"
    return "${ratio.toPlainString()} $unit/$currency"
}

private fun plainAmount(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

private fun productMinor(quantityRaw: String, priceRaw: String, currency: String, locale: Locale): Long? {
    val quantity = parseDecimal(quantityRaw, 3, locale) ?: return null
    val price = parseDecimal(priceRaw, 3, locale) ?: return null
    if (quantity.signum() <= 0 || price.signum() < 0) return null
    val scale = currencyMinorScale(currency)
    return runCatching {
        quantity.multiply(price).movePointRight(scale).setScale(0, TankfulRounding.mode).longValueExact()
    }.getOrNull()
}
