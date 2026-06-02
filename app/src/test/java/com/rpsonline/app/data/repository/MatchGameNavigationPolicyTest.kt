package com.rpsonline.app.data.repository

import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchGameNavigationPolicyTest {

    @Test
    fun openPendingWhenSessionMatchNotPublishedYet() {
        assertTrue(
            shouldOpenPendingGameScreen(
                pendingMatchId = "m2",
                sessionMatch = null,
            ),
        )
    }

    @Test
    fun openPendingForLobbyOrActive() {
        val lobby = Match(id = "m1", status = MatchStatus.LOBBY, player1 = "p1", player2 = "p2")
        assertTrue(shouldOpenPendingGameScreen("m1", lobby))
        val active = lobby.copy(status = MatchStatus.ACTIVE)
        assertTrue(shouldOpenPendingGameScreen("m1", active))
    }

    @Test
    fun waitWhenSessionShowsDifferentMatch() {
        val other = Match(id = "m1", status = MatchStatus.ACTIVE, player1 = "p1", player2 = "p2")
        assertFalse(shouldOpenPendingGameScreen("m2", other))
    }

    @Test
    fun dropPendingForCompletedMatch() {
        val completed = Match(id = "m1", status = MatchStatus.COMPLETED, player1 = "p1", player2 = "p2")
        assertTrue(shouldDropPendingGameNavigation("m1", completed))
    }
}
