package com.tankful.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayFrameTest {
    @Test
    fun acknowledgesOnlyAMatchingOkArray() {
        val id = "ab"
        assertTrue(relayAccepted("""["OK","ab",true]""", id))
        assertFalse(relayAccepted("""["OK","ab",false,"duplicate"]""", id))
        assertFalse(relayAccepted("""["OK","other",true]""", id))
        assertFalse(relayAccepted("not json but \"OK\" true", id))
        assertTrue(relayPageEnded("""["EOSE","tankful"]""", "tankful"))
        assertFalse(relayPageEnded("""["EOSE","other"]""", "tankful"))
        assertTrue(probeAlive("""["EOSE","tankful-ping"]"""))
        assertTrue(probeAlive("""["EVENT","tankful-ping",{"id":"a"}]"""))
        assertFalse(probeAlive("""["CLOSED","tankful-ping"]"""))
        assertFalse(probeAlive("not json"))
    }

    @Test
    fun clientFrameIsMaskedTextAndRejectsAHugePayload() {
        val frame = requireNotNull(clientFrame("hi"))
        assertEquals(0x81.toByte(), frame[0])
        assertTrue(frame[1].toInt() and 0x80 != 0)
        assertNull(clientFrame("x".repeat(300_000)))
    }

    @Test
    fun handshakeAcceptMatchesTheGuid() {
        val key = "dGhlIHNhbXBsZSBub25jZQ=="
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", websocketAccept(key))
        val header = "HTTP/1.1 101 Switching Protocols\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n"
        assertTrue(headerAccepts(header, key))
        assertFalse(headerAccepts(header, "other"))
        assertNotNull(websocketAccept(key))
    }
}
