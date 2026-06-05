package com.rpsonline.app.data.repository

import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchReconnectNavigationPolicyTest {

    private fun liveActiveMatch(): Match = Match(
        id = "m1",
        status = MatchStatus.ACTIVE,
        player1 = "p1",
        player2 = "p2",
        lastActivityAt = System.currentTimeMillis(),
    )

    @Test
    fun shouldAutoNavigateToLiveMatch_onColdLaunchReconnect() {
        val match = liveActiveMatch()
        assertTrue(
            shouldAutoNavigateToLiveMatch(
                match = match,
                userId = "p1",
                fromCache = false,
                matchmakingInProgress = false,
                autoNavigationSuppressed = false,
                resumingFromQueueOrJoin = false,
            ),
        )
    }

    @Test
    fun shouldAutoNavigateToLiveMatch_falseWhenSuppressed() {
        assertFalse(
            shouldAutoNavigateToLiveMatch(
                match = liveActiveMatch(),
                userId = "p1",
                fromCache = false,
                matchmakingInProgress = false,
                autoNavigationSuppressed = true,
                resumingFromQueueOrJoin = false,
            ),
        )
    }

    @Test
    fun shouldAutoNavigateToLiveMatch_falseWhileInQueue() {
        assertFalse(
            shouldAutoNavigateToLiveMatch(
                match = liveActiveMatch(),
                userId = "p1",
                fromCache = false,
                matchmakingInProgress = false,
                autoNavigationSuppressed = false,
                resumingFromQueueOrJoin = true,
            ),
        )
    }

    @Test
    fun shouldAutoNavigateToLiveMatch_trueDuringMatchmaking() {
        assertTrue(
            shouldAutoNavigateToLiveMatch(
                match = liveActiveMatch(),
                userId = "p1",
                fromCache = false,
                matchmakingInProgress = true,
                autoNavigationSuppressed = false,
                resumingFromQueueOrJoin = true,
            ),
        )
    }

    @Test
    fun shouldAutoNavigateToLiveMatch_falseWhenBackgroundedWithoutExplicitLaunch() {
        assertFalse(
            shouldAutoNavigateToLiveMatch(
                match = liveActiveMatch(),
                userId = "p1",
                fromCache = false,
                matchmakingInProgress = true,
                autoNavigationSuppressed = false,
                resumingFromQueueOrJoin = true,
                backgroundUsageEnabled = true,
                appInForeground = false,
                explicitLaunchMatchId = null,
            ),
        )
    }

    @Test
    fun shouldAutoNavigateToLiveMatch_trueWhenBackgroundedWithNotificationTap() {
        assertTrue(
            shouldAutoNavigateToLiveMatch(
                match = liveActiveMatch(),
                userId = "p1",
                fromCache = false,
                matchmakingInProgress = true,
                autoNavigationSuppressed = false,
                resumingFromQueueOrJoin = true,
                backgroundUsageEnabled = true,
                appInForeground = false,
                explicitLaunchMatchId = "m1",
            ),
        )
    }

    @Test
    fun shouldAutoNavigateToLiveMatch_trueWhenForegroundRestoredDuringMatchmaking() {
        assertTrue(
            shouldAutoNavigateToLiveMatch(
                match = liveActiveMatch(),
                userId = "p1",
                fromCache = false,
                matchmakingInProgress = true,
                autoNavigationSuppressed = false,
                resumingFromQueueOrJoin = true,
                backgroundUsageEnabled = true,
                appInForeground = true,
                explicitLaunchMatchId = null,
            ),
        )
    }

    @Test
    fun shouldAllowPassiveMatchJoin_falseWhenBackgroundedWithoutExplicitLaunch() {
        assertFalse(
            shouldAllowPassiveMatchJoinWhenBackgrounded(
                backgroundUsageEnabled = true,
                appInForeground = false,
                explicitLaunchMatchId = null,
                matchId = "m1",
            ),
        )
    }

    @Test
    fun shouldAllowPassiveMatchJoin_trueWhenBackgroundedWithNotificationTap() {
        assertTrue(
            shouldAllowPassiveMatchJoinWhenBackgrounded(
                backgroundUsageEnabled = true,
                appInForeground = false,
                explicitLaunchMatchId = "m1",
                matchId = "m1",
            ),
        )
    }
}
