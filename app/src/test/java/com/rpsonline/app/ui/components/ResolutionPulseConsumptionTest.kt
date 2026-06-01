package com.rpsonline.app.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolutionPulseConsumptionTest {

    @Test
    fun consumptionStart_skipsHistoricalTriggers() {
        assertFalse(
            shouldConsumeResolutionPulse(
                lastConsumed = resolutionPulseConsumptionStart(5),
                currentTrigger = 5,
            ),
        )
        assertTrue(
            shouldConsumeResolutionPulse(
                lastConsumed = resolutionPulseConsumptionStart(5),
                currentTrigger = 6,
            ),
        )
    }
}
