package com.tankful.android

import com.tankful.android.ui.ProfileCardFiles
import com.tankful.android.ui.ProfileFetch
import com.tankful.android.ui.StoredProfile
import com.tankful.android.ui.profileFetchPlan
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileCardTest {
    @Test
    fun aSavedProfileIsShownWithoutAnotherFetch() {
        val pubkey = "ab".repeat(32)
        val saved = StoredProfile(pubkey, "Ada", "https://cdn.example/a.png", byteArrayOf(1))
        assertEquals(ProfileFetch.SHOW, profileFetchPlan(saved))
        assertEquals(ProfileFetch.PICTURE, profileFetchPlan(saved.copy(pictureBytes = null)))
        assertEquals(ProfileFetch.METADATA, profileFetchPlan(null))
        assertEquals(ProfileFetch.SHOW, profileFetchPlan(saved.copy(pictureUrl = null, pictureBytes = null)))
    }

    @Test
    fun profileCardRoundTripKeepsTheNameAndPicture() {
        val dir = File(System.getProperty("java.io.tmpdir"), "tankful-profile-card-test")
        dir.deleteRecursively()
        val files = ProfileCardFiles(dir)
        val pubkey = "cd".repeat(32)
        files.write(pubkey, "Ada \"fuel\"", "https://cdn.example/a.png", null)
        val partial = files.read(pubkey)
        assertEquals("Ada \"fuel\"", partial?.name)
        assertEquals("https://cdn.example/a.png", partial?.pictureUrl)
        assertNull(partial?.pictureBytes)
        files.writePicture(pubkey, byteArrayOf(9, 8))
        assertArrayEquals(byteArrayOf(9, 8), files.read(pubkey)?.pictureBytes)
        assertNull(files.read("ef".repeat(32)))
        files.clear()
        assertNull(files.read(pubkey))
        dir.deleteRecursively()
    }
}
