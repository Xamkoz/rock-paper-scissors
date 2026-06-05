package com.rpsonline.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchLobbyNotificationTimingTest {

    @Test
    fun isWithinAlertWindow_trueBeforeTwentySeconds() {
        val startedAtMs = 1_000L
        assertTrue(
            MatchLobbyNotificationTiming.isWithinAlertWindow(
                startedAtMs = startedAtMs,
                nowMs = startedAtMs + 19_999L,
            ),
        )
    }

    @Test
    fun isWithinAlertWindow_falseAtTwentySeconds() {
        val startedAtMs = 1_000L
        assertFalse(
            MatchLobbyNotificationTiming.isWithinAlertWindow(
                startedAtMs = startedAtMs,
                nowMs = startedAtMs + 20_000L,
            ),
        )
    }

    @Test
    fun remainingAlertSeconds_countsDownFromTwenty() {
        val startedAtMs = 1_000L
        assertEquals(
            20L,
            MatchLobbyNotificationTiming.remainingAlertSeconds(startedAtMs, startedAtMs),
        )
        assertEquals(
            19L,
            MatchLobbyNotificationTiming.remainingAlertSeconds(startedAtMs, startedAtMs + 1_000L),
        )
        assertEquals(
            0L,
            MatchLobbyNotificationTiming.remainingAlertSeconds(startedAtMs, startedAtMs + 20_000L),
        )
    }
}
