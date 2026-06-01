package com.rpsonline.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class MatchSessionQueueRecoveryTest {
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
}
