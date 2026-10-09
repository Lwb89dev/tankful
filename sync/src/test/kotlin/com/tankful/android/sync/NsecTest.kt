package com.tankful.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NsecTest {
    @Test
    fun decodesAKnownNsec() {
        val secret = decodeNsec("nsec1qqqsyqcyq5rqwzqfpg9scrgwpugpzysnzs23v9ccrydpk8qarc0s8zu3a4")
        requireNotNull(secret)
        assertEquals(32, secret.size)
        for (index in secret.indices) assertEquals(index.toByte(), secret[index])
    }

    @Test
    fun rejectsAnythingThatIsNotAnNsec() {
        assertNull(decodeNsec("npub1qqqsyqcyq5rqwzqfpg9scrgwpugpzysnzs23v9ccrydpk8qarc0s8zu3a4"))
        assertNull(decodeNsec("nsec1qqqsyqcyq5rqwzqfpg9scrgwpugpzysnzs23v9ccrydpk8qarc0s8zu3a5"))
        assertNull(decodeNsec(""))
        assertNull(decodeNsec("nsec"))
    }
}
