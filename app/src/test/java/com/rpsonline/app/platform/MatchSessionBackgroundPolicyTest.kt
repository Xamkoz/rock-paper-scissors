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

    @Test
    fun staleQueueTimestampAfterMatch_doesNotNeedBackgroundService() {
        assertFalse(
            computeSessionNeedsBackgroundService(
                uid = uid,
                match = null,
                hasQueueEntry = false,
                queueJoinedAtMs = 1_000L,
                matchmakingInProgress = false,
            ),
        )
    }

    @Test
    fun completedMatch_doesNotNeedBackgroundService() {
        val match = Match(
            player1 = uid,
            player2 = "player-2",
            status = MatchStatus.COMPLETED,
        )
        assertFalse(
            computeSessionNeedsBackgroundService(
                uid = uid,
                match = match,
                hasQueueEntry = false,
                queueJoinedAtMs = 1_000L,
                matchmakingInProgress = false,
            ),
        )
    }

    @Test
    fun matchmakingInProgress_keepsBackgroundServiceWhenListenerCleared() {
        assertTrue(
            computeSessionNeedsBackgroundService(
                uid = uid,
                match = null,
                hasQueueEntry = false,
                queueJoinedAtMs = null,
                matchmakingInProgress = true,
            ),
        )
    }

    @Test
    fun foregroundEngagedIdle_needsPresenceHeartbeat() {
        assertTrue(
            computeSessionNeedsPresenceHeartbeat(
                appInForeground = true,
                userEngaged = true,
                uid = uid,
                match = null,
                hasQueueEntry = false,
                queueJoinedAtMs = null,
            ),
        )
    }

    @Test
    fun foregroundUnattended_doesNotNeedPresenceHeartbeat() {
        assertFalse(
            computeSessionNeedsPresenceHeartbeat(
                appInForeground = true,
                userEngaged = false,
                uid = uid,
                match = null,
                hasQueueEntry = false,
                queueJoinedAtMs = null,
            ),
        )
    }

    @Test
    fun backgroundIdle_doesNotNeedPresenceHeartbeat() {
        assertFalse(
            computeSessionNeedsPresenceHeartbeat(
                appInForeground = false,
                userEngaged = false,
                uid = uid,
                match = null,
                hasQueueEntry = false,
                queueJoinedAtMs = null,
            ),
        )
    }

    @Test
    fun backgroundQueue_needsPresenceHeartbeat() {
        assertTrue(
            computeSessionNeedsPresenceHeartbeat(
                appInForeground = false,
                userEngaged = false,
                uid = uid,
                match = null,
                hasQueueEntry = true,
                queueJoinedAtMs = 1_000L,
                matchmakingInProgress = true,
            ),
        )
    }

    @Test
    fun screenLockedQueue_matchmakingFlag_keepsPresenceHeartbeat() {
        assertTrue(
            computeSessionNeedsPresenceHeartbeat(
                appInForeground = false,
                userEngaged = false,
                uid = uid,
                match = null,
                hasQueueEntry = false,
                queueJoinedAtMs = 1_000L,
                matchmakingInProgress = true,
            ),
        )
    }

    @Test
    fun firstResume_alwaysSyncs() {
        assertTrue(
            computeShouldSyncFromServerOnResume(
                nowMs = 60_000L,
                lastSyncAtMs = 0L,
                forceServerSync = false,
            ),
        )
    }

    @Test
    fun recentResume_isThrottled() {
        assertFalse(
            computeShouldSyncFromServerOnResume(
                nowMs = 50_000L,
                lastSyncAtMs = 10_000L,
                forceServerSync = false,
            ),
        )
    }

    @Test
    fun staleResume_syncsAgain() {
        assertTrue(
            computeShouldSyncFromServerOnResume(
                nowMs = 100_000L,
                lastSyncAtMs = 10_000L,
                forceServerSync = false,
            ),
        )
    }

    @Test
    fun forcedResume_bypassesThrottle() {
        assertTrue(
            computeShouldSyncFromServerOnResume(
                nowMs = 11_000L,
                lastSyncAtMs = 10_000L,
                forceServerSync = true,
            ),
        )
    }
}
