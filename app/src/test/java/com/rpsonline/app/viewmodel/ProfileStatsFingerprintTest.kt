package com.rpsonline.app.viewmodel

import com.rpsonline.app.data.model.UserProfile
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileStatsFingerprintTest {
    @Test
    fun fingerprint_changesWhenPostMatchStatsChange() {
        val before = UserProfile(uid = "a", elo = 1000, wins = 5, losses = 3)
        val after = before.copy(wins = 6, elo = 1016)
        assertNotEquals(before.postMatchStatsFingerprint(), after.postMatchStatsFingerprint())
    }

    @Test
    fun fingerprint_stableForUnchangedStats() {
        val profile = UserProfile(uid = "a", elo = 1000, wins = 5)
        assertEquals(profile.postMatchStatsFingerprint(), profile.postMatchStatsFingerprint())
    }
}
