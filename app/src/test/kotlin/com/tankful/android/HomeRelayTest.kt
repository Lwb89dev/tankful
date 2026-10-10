package com.tankful.android

import com.tankful.android.ui.homeRelayOk
import com.tankful.android.ui.relayAddressOk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRelayTest {
    @Test
    fun acceptsAHomeSocket() {
        assertTrue(homeRelayOk("ws://192.168.1.20:8080"))
        assertTrue(homeRelayOk("ws://localhost:8080"))
        assertTrue(homeRelayOk("ws://relay.local"))
        assertTrue(homeRelayOk("  ws://relay.local/path  "))
    }

    @Test
    fun rejectsPublicAndBlankAddresses() {
        assertFalse(homeRelayOk(""))
        assertFalse(homeRelayOk("ws://"))
        assertFalse(homeRelayOk("wss://relay.damus.io"))
        assertFalse(homeRelayOk("ws://.hidden"))
        assertFalse(homeRelayOk("ws://foo bar"))
        assertFalse(homeRelayOk("ws://:8080"))
    }

    @Test
    fun acceptsPublicAndHomeRelays() {
        assertTrue(relayAddressOk("wss://relay.damus.io"))
        assertTrue(relayAddressOk("ws://192.168.1.20:8080"))
        assertFalse(relayAddressOk("http://relay.example"))
        assertFalse(relayAddressOk("wss://"))
        assertFalse(relayAddressOk(""))
        assertFalse(relayAddressOk("wss://user:pass@relay.damus.io"))
        assertFalse(homeRelayOk("ws://user:pass@192.168.1.20:8080"))
    }
}
