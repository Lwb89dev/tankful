package com.tankful.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Nip44Test {
    @Test
    fun matchesThePublishedConversationAndPayload() {
        val sec1 = ByteArray(32) { 0 }
        sec1[31] = 1
        val sec2 = ByteArray(32) { 0 }
        sec2[31] = 2
        val pub2 = requireNotNull(xOnlyPubkeyHex(sec2))
        val key = requireNotNull(conversationKey(sec1, pub2))
        assertEquals(
            "c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d",
            key.joinToString("") { "%02x".format(it) },
        )
        val nonce = ByteArray(32) { 0 }
        nonce[31] = 1
        val payload = requireNotNull(nip44Encrypt(sec1, pub2, "a", nonce))
        assertEquals(
            "AgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABee0G5VSK0/9YypIObAtDKfYEAjD35uVkHyB0F4DwrcNaCXlCWZKaArsGrY6M9wnuTMxWfp1RTN9Xga8no+kF5Vsb",
            payload,
        )
        val pub1 = requireNotNull(xOnlyPubkeyHex(sec1))
        assertEquals("a", nip44Decrypt(sec2, pub1, payload))
        assertTrue(nip44Wire(payload))
        assertFalse(nip44Wire("{"))
        assertNull(nip44Decrypt(sec2, pub1, payload.dropLast(1) + "A"))
    }

    @Test
    fun paddingStaysInsideTheDocumentedBuckets() {
        assertEquals(32, paddedLength(1))
        assertEquals(32, paddedLength(32))
        assertEquals(64, paddedLength(33))
    }
}
