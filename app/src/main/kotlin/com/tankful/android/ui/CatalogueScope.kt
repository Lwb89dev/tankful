package com.tankful.android.ui

import com.tankful.android.data.catalogue.CatalogueScope
import com.tankful.android.model.QuickFuelChoice

fun catalogueScope(choice: QuickFuelChoice, makes: List<String>, models: List<String>): CatalogueScope {
    if (choice == QuickFuelChoice.PETROL) return CatalogueScope(makes, models, "PETROL", "ICE")
    if (choice == QuickFuelChoice.DIESEL) return CatalogueScope(makes, models, "DIESEL", "ICE")
    if (choice == QuickFuelChoice.LPG) return CatalogueScope(makes, models, "LPG")
    if (choice == QuickFuelChoice.CNG) return CatalogueScope(makes, models, "CNG")
    if (choice == QuickFuelChoice.FULL_HYBRID) return CatalogueScope(makes, models, powertrain = "HYBRID")
    if (choice == QuickFuelChoice.PHEV) return CatalogueScope(makes, models, powertrain = "PHEV")
    return CatalogueScope(makes, models, manual = true)
}
