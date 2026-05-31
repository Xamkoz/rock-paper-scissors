package com.rpsonline.app.platform

import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchSessionBackgroundPolicyTest {
    private val uid = "player-1"

    @Test
    fun queueWaiting_needsBackgroundService() {
        assertTrue(
            computeSessionNeedsBackgroundService(
                uid = uid,
                match = null,
                hasQueueEntry = true,
                queueJoinedAtMs = 1_000L,
            ),
        )
    }

    @Test
    fun lobbyMatch_needsBackgroundService() {
        val match = Match(
            player1 = uid,
            player2 = "player-2",
            status = MatchStatus.LOBBY,
        )
        assertTrue(
            computeSessionNeedsBackgroundService(
                uid = uid,
                match = match,
                hasQueueEntry = false,
                queueJoinedAtMs = null,
            ),
        )
    }

    @Test
    fun activeMatch_needsBackgroundService() {
        val match = Match(
            player1 = uid,
            player2 = "player-2",
            status = MatchStatus.ACTIVE,
        )
        assertTrue(
            computeSessionNeedsBackgroundService(
                uid = uid,
                match = match,
                hasQueueEntry = false,
                queueJoinedAtMs = null,
            ),
        )
    }

    @Test
    fun idleHome_doesNotNeedBackgroundService() {
        assertFalse(
            computeSessionNeedsBackgroundService(
                uid = uid,
                match = null,
                hasQueueEntry = false,
                queueJoinedAtMs = null,
            ),
        )
    }
}
