package com.tankful.android

import com.tankful.android.ui.startDestination
import org.junit.Assert.assertEquals
import org.junit.Test

class FirstRunTest {
    @Test
    fun emptyGarageOpensTheWelcomeRoute() {
        assertEquals("first-run", startDestination(0))
        assertEquals("home", startDestination(1))
    }
}
