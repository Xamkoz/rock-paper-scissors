package com.rpsonline.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchSessionQueueRecoveryTest {

    @Test
    fun shouldBumpQueueNetworkActivity_requiresServerJoinedAtOnce() {
        assertFalse(
            shouldBumpQueueNetworkActivity(
                joinedAtMs = null,
                fromCache = false,
                hasPendingWrites = false,
                lastBumpedJoinedAtMs = null,
            ),
        )
        assertFalse(
            shouldBumpQueueNetworkActivity(
                joinedAtMs = 1_000L,
                fromCache = true,
                hasPendingWrites = false,
                lastBumpedJoinedAtMs = null,
            ),
        )
        assertTrue(
            shouldBumpQueueNetworkActivity(
                joinedAtMs = 1_000L,
                fromCache = false,
                hasPendingWrites = false,
                lastBumpedJoinedAtMs = null,
            ),
        )
        assertFalse(
            shouldBumpQueueNetworkActivity(
                joinedAtMs = 1_000L,
                fromCache = false,
                hasPendingWrites = false,
                lastBumpedJoinedAtMs = 1_000L,
            ),
        )
    }

    @Test
    fun skipWhenNotMatchmaking() {
        assertEquals(
            QueueRecoveryStep.SKIP,
            resolveQueueRecoveryStep(
                matchmakingInProgress = false,
                queueEntryPending = false,
                serverQueueExists = false,
            ),
        )
    }

    @Test
    fun skipWhileJoinStillPending() {
        assertEquals(
            QueueRecoveryStep.SKIP,
            resolveQueueRecoveryStep(
                matchmakingInProgress = true,
                queueEntryPending = true,
                serverQueueExists = false,
            ),
        )
    }

    @Test
    fun syncWhenServerQueueExists() {
        assertEquals(
            QueueRecoveryStep.SYNC,
            resolveQueueRecoveryStep(
                matchmakingInProgress = true,
                queueEntryPending = false,
                serverQueueExists = true,
            ),
        )
    }

    @Test
    fun retryLaterOnTransientReadFailure() {
        assertEquals(
            QueueRecoveryStep.RETRY_LATER,
            resolveQueueRecoveryStep(
                matchmakingInProgress = true,
                queueEntryPending = false,
                serverQueueExists = null,
            ),
        )
    }

    @Test
    fun rejoinWhenServerQueueMissing() {
        assertEquals(
            QueueRecoveryStep.REJOIN,
            resolveQueueRecoveryStep(
                matchmakingInProgress = true,
                queueEntryPending = false,
                serverQueueExists = false,
            ),
        )
    }

    @Test
    fun shouldSkipQueueRecovery_onlyWhenServerConfirmsDoc() {
        assertTrue(
            shouldSkipQueueRecovery(
                hasQueueEntry = true,
                queueJoinedAtMs = 1_000L,
                serverQueueExists = true,
            ),
        )
        assertFalse(
            shouldSkipQueueRecovery(
                hasQueueEntry = true,
                queueJoinedAtMs = 1_000L,
                serverQueueExists = false,
            ),
        )
        assertFalse(
            shouldSkipQueueRecovery(
                hasQueueEntry = true,
                queueJoinedAtMs = 1_000L,
                serverQueueExists = null,
            ),
        )
    }
}
