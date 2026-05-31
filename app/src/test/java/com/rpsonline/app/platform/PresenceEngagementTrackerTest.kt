package com.rpsonline.app.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceEngagementTrackerTest {
    @Test
    fun isEngaged_trueWhenRecentlyActiveAndScreenOn() {
        val nowMs = 1_700_000_000_000L
        PresenceEngagementTracker.setScreenInteractive(true)
        PresenceEngagementTracker.recordInteraction(nowMs - 10_000L)

        assertTrue(
            PresenceEngagementTracker.isEngaged(
                nowMs = nowMs,
                idleTimeoutMs = PresenceEngagementTracker.IDLE_TIMEOUT_MS,
            ),
        )
    }

    @Test
    fun isEngaged_falseAfterIdleTimeout() {
        val nowMs = 1_700_000_000_000L
        PresenceEngagementTracker.setScreenInteractive(true)
        PresenceEngagementTracker.recordInteraction(nowMs - PresenceEngagementTracker.IDLE_TIMEOUT_MS - 1L)

        assertFalse(
            PresenceEngagementTracker.isEngaged(
                nowMs = nowMs,
                idleTimeoutMs = PresenceEngagementTracker.IDLE_TIMEOUT_MS,
            ),
        )
    }

    @Test
    fun isEngaged_falseWhenScreenOff() {
        val nowMs = 1_700_000_000_000L
        PresenceEngagementTracker.setScreenInteractive(false)
        PresenceEngagementTracker.recordInteraction(nowMs)

        assertFalse(
            PresenceEngagementTracker.isEngaged(
                nowMs = nowMs,
                idleTimeoutMs = PresenceEngagementTracker.IDLE_TIMEOUT_MS,
            ),
        )
    }
}
