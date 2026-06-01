package com.rpsonline.app.ui.leaderboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LeaderboardSpectrumColorTest {
    @Test
    fun eloRatingSpectrumPercent_maps900To1250Spread() {
        assertEquals(0f, eloRatingSpectrumPercent(900), 0.01f)
        assertEquals(33.333f, eloRatingSpectrumPercent(1000), 0.01f)
        assertEquals(100f, eloRatingSpectrumPercent(1250), 0.01f)
    }

    @Test
    fun eloRatingSpectrumPercent_clampsOutsidePopulationRange() {
        assertEquals(eloRatingSpectrumPercent(900), eloRatingSpectrumPercent(850), 0.01f)
        assertEquals(eloRatingSpectrumPercent(1250), eloRatingSpectrumPercent(1300), 0.01f)
    }

    @Test
    fun eloRatingSpectrumPercent_increasesWithRating() {
        assertTrue(eloRatingSpectrumPercent(980) < eloRatingSpectrumPercent(1040))
        assertTrue(eloRatingSpectrumPercent(1040) < eloRatingSpectrumPercent(1160))
    }
}
