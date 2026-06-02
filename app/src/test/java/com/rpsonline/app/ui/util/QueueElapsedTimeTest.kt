package com.rpsonline.app.ui.util

import com.rpsonline.app.data.repository.normalizeQueueAnchorMs
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueElapsedTimeTest {

    @Test
    fun elapsed_countsFromAnchorInPast() {
        assertEquals(12L, queueElapsedSecondsFromAnchor(anchorMs = 1_000L, nowMs = 13_000L))
    }

    @Test
    fun normalize_clampsFutureServerAnchorToDeviceNow() {
        val nowMs = 10_000L
        val futureAnchor = nowMs + 60_000L
        val normalized = normalizeQueueAnchorMs(futureAnchor, nowMs)
        assertEquals(nowMs, normalized)
        assertEquals(5L, queueElapsedSecondsFromAnchor(normalized, nowMs + 5_000L))
    }
}
