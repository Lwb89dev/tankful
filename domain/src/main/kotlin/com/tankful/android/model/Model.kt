package com.tankful.android.model

/** Provisional. UNREGISTERED. Not a NIP. Recheck the NIPs repository and a public kind registry before the first publish. */
const val TANKFUL_SNAPSHOT_KIND: Int = 8472

const val SETTINGS_ENTITY_ID: String = "00000000-0000-4000-8000-000000000001"

const val EXPORT_SCHEMA: String = "tankful-export-v1"

const val SYNC_SCHEMA: String = "tankful-sync-snapshot-v1"

const val ODOMETER_JUMP_CONFIRM_METRES: Long = 2_000_000L

/** Typo guard: 2_000_000 km, stored as metres. */
const val ODOMETER_HUGE_METRES: Long = 2_000_000_000L

const val NOTE_MAX: Int = 2000

const val TAG_MAX_COUNT: Int = 20

const val TAG_MAX_LENGTH: Int = 40

const val STATION_MAX: Int = 120

const val PLATE_MAX: Int = 16

const val SNAPSHOT_MAX_BYTES: Int = 8 * 1024

enum class Powertrain { ICE, HYBRID, PHEV, MILD_HYBRID, ELECTRIC, HYDROGEN }

enum class FuelKind { PETROL, DIESEL, LPG, CNG, ELECTRIC, HYDROGEN }

enum class EnergyKind { LIQUID, ELECTRIC }

enum class DistanceEntryUnit { KM_WHOLE, KM_TENTH, MI_WHOLE, MI_TENTH }

enum class EconomyPreference { L_PER_100KM, KM_PER_L, KG_PER_100KM, KM_PER_KG, US_MPG, UK_MPG }

enum class Transmission { MANUAL, AUTOMATIC, DCT, CVT, OTHER }

enum class Drivetrain { FWD, RWD, AWD, OTHER }

enum class PowerUnit { KW, PS, HP }

enum class OdometerSource { MANUAL, REFUEL, MAINTENANCE }

enum class OdometerReason { CORRECTION, REPLACEMENT }

enum class ExpenseOrigin { STANDALONE, REFUEL, MAINTENANCE }

enum class TestCycle { WLTP, NEDC, EPA, OTHER }

enum class MeasureKind { LIQUID, WEIGHTED, CHARGE_SUSTAINING, MPGE, UNKNOWN }

enum class SpecSource { MANUAL, EPA_FUELECONOMY, ADEME_CAR_LABELLING, EEA_CO2_MONITORING }

fun specSourceName(code: String): String? {
    if (code == "EPA") return SpecSource.EPA_FUELECONOMY.name
    if (code == "ADEME") return SpecSource.ADEME_CAR_LABELLING.name
    if (code == "EEA") return SpecSource.EEA_CO2_MONITORING.name
    return null
}

enum class ProviderKind {
    MECHANIC,
    DEALERSHIP,
    BODY_SHOP,
    AUTO_ELECTRICIAN,
    TYRE_SHOP,
    INSPECTION_CENTRE,
    OTHER,
}

enum class ReminderKind {
    INSURANCE,
    TAX,
    INSPECTION,
    ROADSIDE_ASSISTANCE,
    WARRANTY,
    SCHEDULED_SERVICE,
    ODOMETER_PROMPT,
    CUSTOM,
}

enum class ConfirmReason {
    AMOUNTS_DISAGREE,
    LABOUR_PARTS_DISAGREE,
    ODOMETER_ORDER,
    ODOMETER_JUMP,
    ODOMETER_HUGE,
    CLOCK_SKEW,
    LARGE_MONEY,
    LARGE_QUANTITY,
}

enum class RejectReason {
    NEGATIVE_MONEY,
    NEGATIVE_OR_ZERO_QUANTITY,
    NEGATIVE_ODOMETER,
    FUTURE_BEYOND_24H,
    MISSING_VEHICLE,
    MISSING_IDENTITY,
    MISSING_MODEL_YEAR,
    MISSING_FUEL,
    BAD_NUMBER,
    CURRENCY_MISMATCH,
    ELECTRIC_ENERGY,
    ELECTRIC_POWERTRAIN,
    PAYLOAD_TOO_LARGE,
    MISSING_PRICE,
    MISSING_TOTAL,
    NOTE_TOO_LONG,
    TOO_MANY_TAGS,
    TAG_TOO_LONG,
    STATION_TOO_LONG,
    PLATE_TOO_LONG,
    SAME_FUEL_TWICE,
    VIN_NOT_SENT,
}

sealed interface SaveOutcome<out T> {
    data class Saved<T>(val value: T) : SaveOutcome<T>

    data class NeedsConfirmation(val reasons: List<ConfirmReason>) : SaveOutcome<Nothing>

    data class Rejected(val reasons: List<RejectReason>) : SaveOutcome<Nothing>
}

enum class QuickFuelChoice {
    PETROL,
    DIESEL,
    LPG,
    CNG,
    FULL_HYBRID,
    MILD_HYBRID,
    PHEV,
    ELECTRIC,
    HYDROGEN,
}

fun pairedSecondary(primary: QuickFuelChoice, also: QuickFuelChoice?): FuelKind? {
    if (primary != QuickFuelChoice.PETROL) return null
    if (also == QuickFuelChoice.LPG) return FuelKind.LPG
    if (also == QuickFuelChoice.CNG) return FuelKind.CNG
    return null
}

fun mapQuickCreate(choice: QuickFuelChoice, liquid: FuelKind?): Pair<Powertrain, FuelKind> {
    if (choice == QuickFuelChoice.FULL_HYBRID) return Powertrain.HYBRID to hybridLiquid(liquid)
    if (choice == QuickFuelChoice.MILD_HYBRID) return Powertrain.MILD_HYBRID to hybridLiquid(liquid)
    if (choice == QuickFuelChoice.PHEV) return Powertrain.PHEV to hybridLiquid(liquid)
    if (choice == QuickFuelChoice.ELECTRIC) return Powertrain.ELECTRIC to FuelKind.ELECTRIC
    if (choice == QuickFuelChoice.HYDROGEN) return Powertrain.HYDROGEN to FuelKind.HYDROGEN
    return Powertrain.ICE to iceFuel(choice)
}

private fun hybridLiquid(liquid: FuelKind?): FuelKind {
    val fuel = liquid ?: FuelKind.PETROL
    require(fuel == FuelKind.PETROL || fuel == FuelKind.DIESEL)
    return fuel
}

private fun iceFuel(choice: QuickFuelChoice): FuelKind {
    if (choice == QuickFuelChoice.DIESEL) return FuelKind.DIESEL
    if (choice == QuickFuelChoice.LPG) return FuelKind.LPG
    if (choice == QuickFuelChoice.CNG) return FuelKind.CNG
    return FuelKind.PETROL
}
