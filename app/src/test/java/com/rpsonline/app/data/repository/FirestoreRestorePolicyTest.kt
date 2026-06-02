package com.rpsonline.app.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirestoreRestorePolicyTest {

    @Test
    fun softRestoreThrottledUntilIntervalElapses() {
        assertFalse(
            FirestoreRestorePolicy.shouldSoftRestore(
                lastSoftRestoreMs = 1_000L,
                nowMs = 2_000L,
            ),
        )
        assertTrue(
            FirestoreRestorePolicy.shouldSoftRestore(
                lastSoftRestoreMs = 1_000L,
                nowMs = 5_000L,
            ),
        )
    }

    @Test
    fun softRestoreBypassesThrottleWhenForced() {
        assertTrue(
            FirestoreRestorePolicy.shouldSoftRestore(
                lastSoftRestoreMs = 9_000L,
                nowMs = 9_500L,
                bypassThrottle = true,
            ),
        )
    }

    @Test
    fun hardResetOnlyAfterOfflinePreferenceAndCooldown() {
        assertFalse(
            FirestoreRestorePolicy.shouldHardReset(
                preferHardReset = false,
                lastHardRestoreMs = 0L,
                nowMs = 200_000L,
            ),
        )
        assertFalse(
            FirestoreRestorePolicy.shouldHardReset(
                preferHardReset = true,
                lastHardRestoreMs = 0L,
                nowMs = 30_000L,
            ),
        )
        assertTrue(
            FirestoreRestorePolicy.shouldHardReset(
                preferHardReset = true,
                lastHardRestoreMs = 0L,
                nowMs = 130_000L,
            ),
        )
    }
}
