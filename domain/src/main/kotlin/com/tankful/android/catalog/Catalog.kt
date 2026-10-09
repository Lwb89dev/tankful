package com.tankful.android.catalog

import com.tankful.android.model.ReminderKind

data class SystemCategory(val id: String, val parentGroup: String, val sortOrder: Int)

val SYSTEM_CATEGORIES: List<SystemCategory> = listOf(
    SystemCategory("fuel", "fuel", 0),
    SystemCategory("maintenance.scheduled_service", "maintenance", 10),
    SystemCategory("maintenance.oil_change", "maintenance", 11),
    SystemCategory("maintenance.filters", "maintenance", 12),
    SystemCategory("maintenance.fluids", "maintenance", 13),
    SystemCategory("repairs.mechanical", "repairs", 20),
    SystemCategory("repairs.electrical", "repairs", 21),
    SystemCategory("repairs.bodywork", "repairs", 22),
    SystemCategory("repairs.glass", "repairs", 23),
    SystemCategory("consumables.tyres", "consumables", 30),
    SystemCategory("consumables.brake_pads", "consumables", 31),
    SystemCategory("consumables.brake_discs", "consumables", 32),
    SystemCategory("consumables.wipers", "consumables", 33),
    SystemCategory("consumables.battery", "consumables", 34),
    SystemCategory("consumables.bulbs", "consumables", 35),
    SystemCategory("legal.insurance", "legal", 40),
    SystemCategory("legal.vehicle_tax", "legal", 41),
    SystemCategory("legal.registration", "legal", 42),
    SystemCategory("legal.inspection", "legal", 43),
    SystemCategory("legal.tolls", "legal", 44),
    SystemCategory("legal.parking", "legal", 45),
    SystemCategory("cleaning.car_wash", "cleaning", 50),
    SystemCategory("cleaning.detailing", "cleaning", 51),
    SystemCategory("cleaning.products", "cleaning", 52),
    SystemCategory("accessories.parts", "accessories", 60),
    SystemCategory("accessories.electronics", "accessories", 61),
    SystemCategory("accessories.interior", "accessories", 62),
    SystemCategory("accessories.exterior", "accessories", 63),
    SystemCategory("other", "other", 70),
)

val SYSTEM_GROUPS: Set<String> = SYSTEM_CATEGORIES.map { it.parentGroup }.toSet()

data class SystemServiceType(val id: String, val defaultCategoryId: String)

val SYSTEM_SERVICE_TYPES: List<SystemServiceType> = listOf(
    SystemServiceType("oil", "maintenance.oil_change"),
    SystemServiceType("filters", "maintenance.filters"),
    SystemServiceType("coolant", "maintenance.fluids"),
    SystemServiceType("brake_fluid", "maintenance.fluids"),
    SystemServiceType("transmission_fluid", "maintenance.fluids"),
    SystemServiceType("brakes", "consumables.brake_pads"),
    SystemServiceType("tyres", "consumables.tyres"),
    SystemServiceType("battery", "consumables.battery"),
    SystemServiceType("general_service", "maintenance.scheduled_service"),
    SystemServiceType("timing_belt", "repairs.mechanical"),
    SystemServiceType("plugs", "repairs.mechanical"),
    SystemServiceType("clutch", "repairs.mechanical"),
    SystemServiceType("suspension", "repairs.mechanical"),
)

val REMINDER_ENGLISH: Map<ReminderKind, String> = mapOf(
    ReminderKind.INSURANCE to "Insurance renewal",
    ReminderKind.TAX to "Vehicle tax due",
    ReminderKind.INSPECTION to "Inspection due",
    ReminderKind.ROADSIDE_ASSISTANCE to "Roadside assistance renewal",
    ReminderKind.WARRANTY to "Warranty ending",
    ReminderKind.SCHEDULED_SERVICE to "Service due",
    ReminderKind.ODOMETER_PROMPT to "Odometer reading due",
    ReminderKind.CUSTOM to "Reminder due",
)

fun reminderStringHasFormatArgs(value: String): Boolean {
    if ("%s" in value || "%d" in value || "%f" in value) return true
    return Regex("""%(\d+\$)?[sdf]|\{[^{}]+\}""").containsMatchIn(value)
}

fun notificationBody(kind: ReminderKind, customTitle: String?): String {
    return REMINDER_ENGLISH.getValue(kind).also { unused ->
        if (customTitle != null && unused.isEmpty()) error("catalog sentence missing")
    }
}
