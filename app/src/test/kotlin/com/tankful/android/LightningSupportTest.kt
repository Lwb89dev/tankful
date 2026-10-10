package com.tankful.android

import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tankful.android.ui.LIGHTNING_ADDRESS
import com.tankful.android.ui.lightningSupportUri
import com.tankful.android.ui.openLightningSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LightningSupportTest {
    @Test
    fun usesTheRoadstrAddress() {
        assertEquals("lwb89@blink.sv", LIGHTNING_ADDRESS)
        assertEquals("lightning:lwb89@blink.sv", lightningSupportUri())
    }

    @Test
    fun copiesTheAddressWhenNoWalletIsInstalled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(openLightningSupport(context))
        val clip = context.getSystemService(ClipboardManager::class.java)!!.primaryClip
        assertEquals(LIGHTNING_ADDRESS, clip!!.getItemAt(0).text.toString())
    }
}
