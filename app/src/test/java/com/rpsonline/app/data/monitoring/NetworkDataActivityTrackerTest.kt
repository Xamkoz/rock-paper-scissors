package com.rpsonline.app.data.monitoring

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NetworkDataActivityTrackerTest {

    @Before
    fun resetTracker() {
        NetworkDataActivityTracker.resetForTest()
    }

    @Test
    fun bump_setsActiveKindThenDecays() = runBlocking {
        NetworkDataActivityTracker.bump(NetworkDataActivityKind.Queue, durationMs = 80L)
        assertTrue(NetworkDataActivityKind.Queue in NetworkDataActivityTracker.activeKinds.value)
        assertTrue(NetworkDataActivityTracker.isActive.value)
        delay(120L)
        withTimeout(500L) {
            while (NetworkDataActivityTracker.isActive.value) {
                delay(20L)
            }
        }
        assertFalse(NetworkDataActivityTracker.isActive.first())
        assertEquals(emptySet<NetworkDataActivityKind>(), NetworkDataActivityTracker.activeKinds.first())
    }

    @Test
    fun bump_tracksKindsIndependently() = runBlocking {
        NetworkDataActivityTracker.bump(NetworkDataActivityKind.Match, durationMs = 300L)
        NetworkDataActivityTracker.bump(NetworkDataActivityKind.Presence, durationMs = 50L)
        assertEquals(
            setOf(NetworkDataActivityKind.Match, NetworkDataActivityKind.Presence),
            NetworkDataActivityTracker.activeKinds.value,
        )
        delay(100L)
        NetworkDataActivityTracker.refreshActiveKindsForTest()
        assertEquals(
            setOf(NetworkDataActivityKind.Match),
            NetworkDataActivityTracker.activeKinds.value,
        )
    }
}
