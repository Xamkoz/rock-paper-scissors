package com.rpsonline.app.data.repository

import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchSessionResumePolicyTest {

    @Test
    fun deferHome_falseForCompletedLaunchTarget() {
        val completed = Match(id = "m1", status = MatchStatus.COMPLETED)
        assertFalse(
            shouldDeferHomeForGameLaunch(
                pendingLaunchMatchId = "m1",
                matchmakingInProgress = false,
                sessionMatch = completed,
            ),
        )
    }

    @Test
    fun deferHome_trueForLiveLaunchTarget() {
        val active = Match(id = "m1", status = MatchStatus.ACTIVE)
        assertTrue(
            shouldDeferHomeForGameLaunch(
                pendingLaunchMatchId = "m1",
                matchmakingInProgress = false,
                sessionMatch = active,
            ),
        )
    }

    @Test
    fun clearStaleQueueUi_whenHomeStillShowsInQueue() {
        assertTrue(
            shouldClearStaleQueueUiOnResume(
                monitorMatchmaking = false,
                hasQueueEntry = false,
                queueJoinedAtMs = null,
                queueAnchorMs = null,
                isInQueue = true,
                isJoiningQueue = false,
            ),
        )
    }

    @Test
    fun reconcileQueue_falseWhenOnlyStaleHomeInQueue() {
        assertFalse(
            shouldReconcileQueueSessionOnResume(
                isInQueue = true,
                isJoiningQueue = false,
                monitorMatchmaking = false,
                hasQueueEntry = false,
                queueJoinedAtMs = null,
                queueAnchorMs = null,
            ),
        )
    }
}
