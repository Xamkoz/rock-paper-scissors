package com.rpsonline.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentedPulseBridgeTest {

    @Test
    fun networkBurstSlots_coverAllDigitSlotsExceptColonAndSpinner() {
        assertEquals(10, TopBarNetworkBurstSlotIndices.size)
        assertFalse(isNetworkBurstSlot(TopBarColonSlotIndex))
        assertFalse(isNetworkBurstSlot(TopBarSpinnerDigitSlotIndex))
        TopBarNetworkActivitySlotIndices.forEach { slot ->
            if (slot == TopBarSpinnerDigitSlotIndex) {
                assertFalse(isNetworkBurstSlot(slot))
            } else {
                assertTrue(isNetworkBurstSlot(slot))
            }
        }
    }

    @Test
    fun networkOverlaySpansCountBridgeAndTimerRegions() {
        assertTrue(TopBarOnlineCountSlotEnd in TopBarNetworkActivitySlotIndices)
        assertTrue(4 in TopBarNetworkActivitySlotIndices)
        assertTrue(TopBarTimerDigitsSlotStart in TopBarNetworkActivitySlotIndices)
        assertTrue((TopBarSegmentedSlotCount - 1) in TopBarNetworkActivitySlotIndices)
    }
}
