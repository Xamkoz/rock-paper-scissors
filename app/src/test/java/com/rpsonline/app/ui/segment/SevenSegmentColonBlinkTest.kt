package com.rpsonline.app.ui.segment

import com.rpsonline.app.ui.util.queueElapsedSecondsFromAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SevenSegmentColonBlinkTest {

    @Test
    fun colonLit_firstHalfOfEachSecondFromAnchor() {
        val anchor = 1_000_000L
        assertTrue(SevenSegmentColonBlink.isLit(true, anchor, anchor))
        assertTrue(SevenSegmentColonBlink.isLit(true, anchor, anchor + 499))
        assertFalse(SevenSegmentColonBlink.isLit(true, anchor, anchor + 500))
        assertFalse(SevenSegmentColonBlink.isLit(true, anchor, anchor + 999))
        assertTrue(SevenSegmentColonBlink.isLit(true, anchor, anchor + 1_000))
    }

    @Test
    fun colonOffWhenTimerHidden() {
        assertFalse(SevenSegmentColonBlink.isLit(false, 0L, 0L))
    }

    @Test
    fun delayUntilToggle_matchesPhase() {
        val anchor = 0L
        assertEquals(500L, SevenSegmentColonBlink.delayUntilToggle(anchor, nowMs = 0L))
        assertEquals(1L, SevenSegmentColonBlink.delayUntilToggle(anchor, nowMs = 499L))
        assertEquals(500L, SevenSegmentColonBlink.delayUntilToggle(anchor, nowMs = 500L))
    }

    @Test
    fun secondBoundary_alignsWithColonOnPhase() {
        val anchor = 1_000_000L
        val nowMs = anchor + 37
        assertTrue(SevenSegmentColonBlink.isLit(true, anchor, nowMs))
        assertEquals(963L, SevenSegmentColonBlink.delayMsUntilNextSecondBoundary(anchor, nowMs))
        val boundaryMs = anchor + 1_000
        assertTrue(SevenSegmentColonBlink.isLit(true, anchor, boundaryMs))
        assertEquals(1L, queueElapsedSecondsFromAnchor(anchor, boundaryMs))
        assertEquals(0L, queueElapsedSecondsFromAnchor(anchor, boundaryMs - 1))
    }
}
