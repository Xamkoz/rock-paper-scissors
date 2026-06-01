package com.rpsonline.app.data.monitoring

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkDataActivityTrackerTest {

    @Test
    fun bump_setsActiveThenDecays() = runBlocking {
        NetworkDataActivityTracker.bump(durationMs = 80L)
        assertTrue(NetworkDataActivityTracker.isActive.value)
        delay(120L)
        withTimeout(500L) {
            while (NetworkDataActivityTracker.isActive.value) {
                delay(20L)
            }
        }
        assertFalse(NetworkDataActivityTracker.isActive.first())
    }
}
