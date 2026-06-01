package com.rpsonline.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class MatchFormatTest {
    @Test
    fun formatEloDeltaOneDecimal_positiveNegativeAndZero() {
        assertEquals("+2.7", formatEloDeltaOneDecimal(2.67))
        assertEquals("-1.3", formatEloDeltaOneDecimal(-1.34))
        assertEquals("+0.0", formatEloDeltaOneDecimal(0.0))
        assertEquals("+4.0", formatEloDeltaOneDecimal(4.0))
    }
}
