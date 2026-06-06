package com.rpsonline.app.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchClockTickTimingTest {

    @Test
    fun matchClockHapticDelayElapsed_falseBeforeSevenSeconds() {
        assertFalse(matchClockHapticDelayElapsed(anchorElapsedMs = 100L, nowElapsedMs = 7_099L))
    }

    @Test
    fun matchClockHapticDelayElapsed_trueAfterSevenSeconds() {
        assertTrue(matchClockHapticDelayElapsed(anchorElapsedMs = 100L, nowElapsedMs = 7_100L))
    }

    @Test
    fun delayMsUntilNextLobbyAlertBeat_zeroAtBeatBoundary() {
        assertEquals(
            0L,
            delayMsUntilNextLobbyAlertBeat(anchorMs = 1_000L, beatIndex = 0L, nowMs = 1_000L),
        )
    }

    @Test
    fun delayMsUntilNextLobbyAlertBeat_waitsUntilNextHalfSecond() {
        assertEquals(
            200L,
            delayMsUntilNextLobbyAlertBeat(anchorMs = 1_000L, beatIndex = 1L, nowMs = 1_300L),
        )
    }

    @Test
    fun currentLobbyAlertBeatIndex_advancesEveryHalfSecond() {
        assertEquals(0L, currentLobbyAlertBeatIndex(anchorMs = 1_000L, nowMs = 1_200L))
        assertEquals(1L, currentLobbyAlertBeatIndex(anchorMs = 1_000L, nowMs = 1_600L))
    }
}
