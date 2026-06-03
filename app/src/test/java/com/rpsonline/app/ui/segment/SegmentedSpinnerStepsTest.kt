package com.rpsonline.app.ui.segment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentedSpinnerStepsTest {

    @Test
    fun stepIndex_advancesOnAnchorAlignedHalfSeconds() {
        val anchor = 2_000_000L
        val style = SegmentedSpinnerStyle.QUEUE
        assertEquals(0, SegmentedSpinnerSteps.stepIndex(style, anchor, anchor))
        assertEquals(0, SegmentedSpinnerSteps.stepIndex(style, anchor + 499, anchor))
        assertEquals(1, SegmentedSpinnerSteps.stepIndex(style, anchor + 500, anchor))
        assertEquals(2, SegmentedSpinnerSteps.stepIndex(style, anchor + 1_000, anchor))
    }

    @Test
    fun stepIndex_matchesColonOnBoundary() {
        val anchor = 0L
        val style = SegmentedSpinnerStyle.MATCH
        assertTrue(SevenSegmentColonBlink.isLit(true, anchor, anchor))
        val stepAtSecondStart = SegmentedSpinnerSteps.stepIndex(style, anchor, anchor)
        assertFalse(SevenSegmentColonBlink.isLit(true, anchor, anchor + 500))
        val stepAtHalfSecond = SegmentedSpinnerSteps.stepIndex(style, anchor + 500, anchor)
        assertEquals(1, stepAtHalfSecond - stepAtSecondStart)
    }
}
