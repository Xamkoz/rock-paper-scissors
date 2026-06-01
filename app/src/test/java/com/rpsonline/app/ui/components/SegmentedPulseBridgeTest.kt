package com.rpsonline.app.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentedPulseBridgeTest {

    @Test
    fun bridgePulseSlots_areMiddleThreeOnly() {
        assertFalse(isBridgePulseSlot(3))
        assertTrue(isBridgePulseSlot(4))
        assertTrue(isBridgePulseSlot(5))
        assertTrue(isBridgePulseSlot(6))
        assertFalse(isBridgePulseSlot(7))
    }
}
