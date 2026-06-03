package com.rpsonline.app.ui.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchClockTickTimingTest {

    @Test
    fun matchClockHapticDelayElapsed_falseBeforeFiveSeconds() {
        assertFalse(matchClockHapticDelayElapsed(anchorElapsedMs = 100L, nowElapsedMs = 5_099L))
    }

    @Test
    fun matchClockHapticDelayElapsed_trueAfterFiveSeconds() {
        assertTrue(matchClockHapticDelayElapsed(anchorElapsedMs = 100L, nowElapsedMs = 5_100L))
    }
}
