package com.tankful.android.quantity

import com.tankful.android.model.DistanceEntryUnit
import com.tankful.android.model.FuelKind
import com.tankful.android.money.TankfulRounding
import java.math.BigDecimal
import java.util.Locale

enum class QuantityUnit { MILLILITRE, GRAM }

class Quantity private constructor(
    val unit: QuantityUnit,
    val amount: Long,
) {
    companion object {
        fun of(unit: QuantityUnit, amount: Long): Quantity {
            require(amount >= 0L) { "negative quantity" }
            return Quantity(unit, amount)
        }
    }

    override fun equals(other: Any?): Boolean {
        return other is Quantity && other.unit == unit && other.amount == amount
    }

    override fun hashCode(): Int = 31 * unit.hashCode() + amount.hashCode()
}

data class DistanceMetres(
    val metres: Long,
    val entryUnit: DistanceEntryUnit,
    val entryUnscaled: Long,
    val entryScale: Int,
) {
    init {
        require(metres >= 0L) { "negative distance" }
    }

    companion object {
        fun ofMetres(metres: Long): DistanceMetres {
            return DistanceMetres(metres, DistanceEntryUnit.KM_WHOLE, metres, 0)
        }
    }
}

object FuelUnits {
    val usGallonLitres: BigDecimal
        get() = BigDecimal("3.785411784")

    val ukGallonLitres: BigDecimal
        get() = BigDecimal("4.54609")

    val mileKilometres: BigDecimal
        get() = BigDecimal("1.609344")

    fun litresPer100km(volume: Quantity, distance: DistanceMetres): BigDecimal {
        require(volume.unit == QuantityUnit.MILLILITRE)
        return perHundred(volume.amount, distance.metres)
    }

    fun kmPerLitre(volume: Quantity, distance: DistanceMetres): BigDecimal {
        require(volume.unit == QuantityUnit.MILLILITRE)
        return perMajor(volume.amount, distance.metres)
    }

    fun kgPer100km(mass: Quantity, distance: DistanceMetres): BigDecimal {
        require(mass.unit == QuantityUnit.GRAM)
        return perHundred(mass.amount, distance.metres)
    }

    fun kmPerKg(mass: Quantity, distance: DistanceMetres): BigDecimal {
        require(mass.unit == QuantityUnit.GRAM)
        return perMajor(mass.amount, distance.metres)
    }

    /** Not a v1 display path. Locked by UnitConversionTest. */
    fun usMilesPerGallon(volume: Quantity, distance: DistanceMetres): BigDecimal {
        return kmPerLitre(volume, distance)
            .multiply(usGallonLitres)
            .divide(mileKilometres, 16, TankfulRounding.mode)
    }

    fun ukMilesPerGallon(volume: Quantity, distance: DistanceMetres): BigDecimal {
        return kmPerLitre(volume, distance)
            .multiply(ukGallonLitres)
            .divide(mileKilometres, 16, TankfulRounding.mode)
    }
}

private fun perHundred(smallest: Long, metres: Long): BigDecimal {
    require(metres > 0L)
    return BigDecimal.valueOf(smallest)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(metres), 16, TankfulRounding.mode)
}

private fun perMajor(smallest: Long, metres: Long): BigDecimal {
    require(metres > 0L)
    require(smallest > 0L)
    return BigDecimal.valueOf(metres)
        .divide(BigDecimal.valueOf(smallest), 16, TankfulRounding.mode)
}

fun displayOneDecimal(value: BigDecimal): BigDecimal = value.setScale(1, TankfulRounding.mode)

fun displayTwoDecimals(value: BigDecimal): BigDecimal = value.setScale(2, TankfulRounding.mode)

fun quantityUnitFor(fuel: FuelKind): QuantityUnit {
    if (fuel == FuelKind.CNG) return QuantityUnit.GRAM
    return QuantityUnit.MILLILITRE
}

/**
 * One separator is the decimal mark. `.` and `,` are both accepted when only one appears.
 * Thousands separators are rejected. [locale] is accepted so callers can pass the device
 * locale; it does not choose the separator.
 */
fun parseDecimal(raw: String, maxFractionDigits: Int, locale: Locale): BigDecimal? {
    val unused = locale.language
    if (unused.isEmpty() && raw.isEmpty()) return null
    val text = openDecimal(raw.trim())
    if (text.isEmpty()) return null
    val dots = text.count { it == '.' }
    val commas = text.count { it == ',' }
    if (dots + commas > 1) return null
    val normalized = text.replace(',', '.')
    if (!normalized.matches(Regex("""\d+(\.\d+)?"""))) return null
    val fraction = normalized.substringAfter('.', "")
    if (fraction.length > maxFractionDigits) return null
    return BigDecimal(normalized)
}

/** A trailing `.` or `,` is still being typed, so it does not erase the digits already entered. */
private fun openDecimal(text: String): String {
    if (text.length < 2) return text
    val mark = text.last()
    if (mark != '.' && mark != ',') return text
    val body = text.dropLast(1)
    if (body.any { it == '.' || it == ',' || !it.isDigit() }) return text
    return body
}

fun parseUnitPriceUnscaled(raw: String, locale: Locale, scale: Int = 3): Long? {
    val parsed = parseDecimal(raw, scale, locale) ?: return null
    return parsed.movePointRight(scale).setScale(0, TankfulRounding.mode).longValueExact()
}

fun parseToSmallest(raw: String, locale: Locale, maxFractionDigits: Int, perMajor: Int): Long? {
    val parsed = parseDecimal(raw, maxFractionDigits, locale) ?: return null
    return parsed.multiply(BigDecimal.valueOf(perMajor.toLong()))
        .setScale(0, TankfulRounding.mode)
        .longValueExact()
}

fun millilitresFromEntry(raw: String, locale: Locale): Long? = parseToSmallest(raw, locale, 3, 1000)

fun gramsFromKilograms(raw: String, locale: Locale): Long? = parseToSmallest(raw, locale, 3, 1000)

fun millilitresFromGallons(gallonsUnscaled: Long, scale: Int, unitedStates: Boolean): Long {
    val gallons = BigDecimal.valueOf(gallonsUnscaled).movePointLeft(scale)
    val factor = if (unitedStates) FuelUnits.usGallonLitres else FuelUnits.ukGallonLitres
    return gallons.multiply(factor)
        .multiply(BigDecimal("1000"))
        .setScale(0, TankfulRounding.mode)
        .longValueExact()
}

fun metresFromEntry(unit: DistanceEntryUnit, unscaled: Long, scale: Int): Long {
    require(unscaled >= 0L)
    val major = BigDecimal.valueOf(unscaled).movePointLeft(scale)
    val metres = when (unit) {
        DistanceEntryUnit.KM_WHOLE, DistanceEntryUnit.KM_TENTH ->
            major.multiply(BigDecimal("1000"))
        DistanceEntryUnit.MI_WHOLE, DistanceEntryUnit.MI_TENTH ->
            major.multiply(FuelUnits.mileKilometres).multiply(BigDecimal("1000"))
    }
    return metres.setScale(0, TankfulRounding.mode).longValueExact()
}

const val LARGE_LIQUID_ML: Long = 2_000_000L

const val LARGE_MASS_G: Long = 2_000_000L
