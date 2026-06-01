package com.rpsonline.app.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundTimeoutRequestDeduperTest {

    @Test
    fun wasSent_afterMarkSent() {
        RoundTimeoutRequestDeduper.clearSent("m1", 2)
        assertFalse(RoundTimeoutRequestDeduper.wasSent("m1", 2))
        RoundTimeoutRequestDeduper.markSent("m1", 2)
        assertTrue(RoundTimeoutRequestDeduper.wasSent("m1", 2))
    }

    @Test
    fun clearSent_allowsAnotherWriteForSameRound() {
        RoundTimeoutRequestDeduper.markSent("m1", 3)
        RoundTimeoutRequestDeduper.clearSent("m1", 3)
        assertFalse(RoundTimeoutRequestDeduper.wasSent("m1", 3))
    }
}
