package com.tankful.android

import com.tankful.android.ui.previousOnboardingStep
import com.tankful.android.ui.startDestination
import com.tankful.android.ui.vehicleSaveNotice
import org.junit.Assert.assertEquals
import org.junit.Test

class FirstRunTest {
    @Test
    fun emptyGarageOpensTheWelcomeRoute() {
        assertEquals("first-run", startDestination(0))
        assertEquals("home", startDestination(1))
    }

    @Test
    fun saveExplainsAMissingFuelOrListedVehicle() {
        assertEquals("fuel", vehicleSaveNotice(hasFuel = false, listed = true, selected = false))
        assertEquals("vehicle", vehicleSaveNotice(hasFuel = true, listed = true, selected = false))
        assertEquals("", vehicleSaveNotice(hasFuel = true, listed = true, selected = true))
        assertEquals("", vehicleSaveNotice(hasFuel = true, listed = false, selected = false))
    }

    @Test
    fun backMovesToThePreviousOnboardingStep() {
        assertEquals("relays", previousOnboardingStep("vehicle"))
        assertEquals("login", previousOnboardingStep("relays"))
        assertEquals("intro", previousOnboardingStep("login"))
        assertEquals("intro", previousOnboardingStep("intro"))
    }
}
