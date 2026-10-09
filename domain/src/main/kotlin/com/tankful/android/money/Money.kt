package com.tankful.android.money

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

object TankfulRounding {
    val mode: RoundingMode
        get() = RoundingMode.HALF_UP
}

class Money private constructor(
    val currency: String,
    val minorUnits: Long,
) {
    operator fun plus(other: Money): Money {
        require(currency == other.currency) { "mixed currencies" }
        return of(currency, minorUnits + other.minorUnits)
    }

    override fun equals(other: Any?): Boolean {
        return other is Money && other.currency == currency && other.minorUnits == minorUnits
    }

    override fun hashCode(): Int = 31 * currency.hashCode() + minorUnits.hashCode()

    override fun toString(): String = "$minorUnits $currency"

    companion object {
        fun of(currency: String, minorUnits: Long): Money {
            require(minorUnits >= 0L) { "negative money" }
            val code = normalizeCurrency(currency)
            return Money(code, minorUnits)
        }

        fun zero(currency: String): Money = of(currency, 0L)
    }
}

val MAJOR_CURRENCIES: List<String> = listOf(
    "USD", "EUR", "JPY", "GBP", "CNY", "AUD", "CAD", "CHF", "HKD", "SGD",
    "SEK", "KRW", "NOK", "NZD", "INR", "MXN", "TWD", "ZAR", "BRL", "DKK",
)

fun normalizeCurrency(currency: String): String {
    val code = currency.trim().uppercase()
    Currency.getInstance(code)
    return code
}

fun currencyMinorScale(currency: String): Int {
    return Currency.getInstance(normalizeCurrency(currency)).defaultFractionDigits
}

fun sumByCurrency(amounts: Iterable<Money>): Map<String, Long> {
    val totals = linkedMapOf<String, Long>()
    for (amount in amounts) {
        totals[amount.currency] = (totals[amount.currency] ?: 0L) + amount.minorUnits
    }
    return totals
}

/**
 * minorUnits = HALF_UP(quantitySmallest * unitPriceUnscaled / 10^(priceScale + 3 - currencyMinorScale))
 */
fun minorUnitsFromUnitPrice(
    quantitySmallest: Long,
    unitPriceUnscaled: Long,
    priceScale: Int,
    currencyMinorScale: Int,
): Long {
    val exponent = priceScale + 3 - currencyMinorScale
    val product = BigDecimal.valueOf(quantitySmallest).multiply(BigDecimal.valueOf(unitPriceUnscaled))
    val divisor = BigDecimal.TEN.pow(exponent)
    return product.divide(divisor, 0, TankfulRounding.mode).longValueExact()
}

fun deriveUnitPriceUnscaled(
    minorUnits: Long,
    quantitySmallest: Long,
    priceScale: Int,
    currencyMinorScale: Int,
): Long {
    require(quantitySmallest > 0L)
    val numerator = BigDecimal.valueOf(minorUnits)
        .multiply(BigDecimal.TEN.pow(priceScale))
        .multiply(BigDecimal.valueOf(1000))
    val denominator = BigDecimal.TEN.pow(currencyMinorScale)
        .multiply(BigDecimal.valueOf(quantitySmallest))
    return numerator.divide(denominator, 0, TankfulRounding.mode).longValueExact()
}

class UnitPrice private constructor(
    val currency: String,
    val scale: Int,
    val unscaledAmount: Long,
    val derived: Boolean,
) {
    fun toPlain(): String {
        return BigDecimal.valueOf(unscaledAmount).movePointLeft(scale).toPlainString()
    }

    override fun equals(other: Any?): Boolean {
        return other is UnitPrice &&
            other.currency == currency &&
            other.scale == scale &&
            other.unscaledAmount == unscaledAmount &&
            other.derived == derived
    }

    override fun hashCode(): Int {
        var hash = currency.hashCode()
        hash = 31 * hash + scale
        hash = 31 * hash + unscaledAmount.hashCode()
        hash = 31 * hash + derived.hashCode()
        return hash
    }

    companion object {
        fun of(currency: String, scale: Int, unscaledAmount: Long, derived: Boolean): UnitPrice {
            require(scale >= 0)
            require(unscaledAmount >= 0L) { "negative unit price" }
            return UnitPrice(normalizeCurrency(currency), scale, unscaledAmount, derived)
        }
    }
}

/** True when the typed total and the total implied by the unit price differ by more than one minor unit. */
fun amountsDisagree(computedMinor: Long, enteredMinor: Long): Boolean {
    val gap = computedMinor - enteredMinor
    val abs = if (gap < 0) -gap else gap
    return abs > 1L
}

fun costPer100KmMinor(totalMinor: Long, spanMetres: Long): Long {
    require(spanMetres > 0L)
    return BigDecimal.valueOf(totalMinor)
        .multiply(BigDecimal.valueOf(100_000L))
        .divide(BigDecimal.valueOf(spanMetres), 0, TankfulRounding.mode)
        .longValueExact()
}

fun costPer100MiMinor(totalMinor: Long, spanMetres: Long): Long {
    require(spanMetres > 0L)
    return BigDecimal.valueOf(totalMinor)
        .multiply(BigDecimal.valueOf(100L))
        .multiply(BigDecimal("1609.344"))
        .divide(BigDecimal.valueOf(spanMetres), 0, TankfulRounding.mode)
        .longValueExact()
}

/** 1_000_000 major units, expressed in minor units. A confirmation, not a hard reject. */
fun largeMoneyThreshold(currency: String): Long {
    val scale = currencyMinorScale(currency)
    return BigDecimal.valueOf(1_000_000L)
        .movePointRight(scale)
        .longValueExact()
}
