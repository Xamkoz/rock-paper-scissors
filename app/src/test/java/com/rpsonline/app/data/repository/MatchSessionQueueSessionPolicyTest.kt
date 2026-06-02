package com.rpsonline.app.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchSessionQueueSessionPolicyTest {

    @Test
    fun endsMatchmakingWhenTrackedMatchCompletesEvenWithStaleQueueEntry() {
        assertTrue(
            shouldEndMatchmakingOnTerminalMatch(
                terminalMatchId = "m1",
                trackedMatchId = "m1",
                listeningMatchId = null,
                hasQueueEntry = true,
                matchmakingInProgress = true,
            ),
        )
    }

    @Test
    fun keepsMatchmakingWhenOldTerminalSnapshotArrivesDuringNewQueue() {
        assertFalse(
            shouldEndMatchmakingOnTerminalMatch(
                terminalMatchId = "old",
                trackedMatchId = "new",
                listeningMatchId = "new",
                hasQueueEntry = true,
                matchmakingInProgress = true,
            ),
        )
    }

    @Test
    fun endsMatchmakingWhenTerminalSnapshotHasNoQueueEntry() {
        assertTrue(
            shouldEndMatchmakingOnTerminalMatch(
                terminalMatchId = "old",
                trackedMatchId = "new",
                listeningMatchId = "new",
                hasQueueEntry = false,
                matchmakingInProgress = false,
            ),
        )
    }
}
