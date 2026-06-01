package com.rpsonline.app.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentedPulseBridgeTest {

    @Test
    fun networkActivitySlots_matchDigitsFiveSixSeven() {
        assertFalse(isBridgePulseSlot(3))
        assertTrue(isBridgePulseSlot(4))
        assertTrue(isBridgePulseSlot(5))
        assertTrue(isBridgePulseSlot(6))
        assertFalse(isBridgePulseSlot(7))
    }

    @Test
    fun layoutRegions_doNotOverlap() {
        assertTrue(TopBarOnlineCountSlotEnd < TopBarNetworkActivitySlotIndices.min())
        assertTrue(TopBarNetworkActivitySlotIndices.max() < TopBarTimerDigitsSlotStart)
    }
}
