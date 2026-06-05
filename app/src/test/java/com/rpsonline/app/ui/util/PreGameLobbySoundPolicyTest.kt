package com.rpsonline.app.ui.util

import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus
import com.rpsonline.app.platform.JoinMatchNotificationState
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreGameLobbySoundPolicyTest {

    @After
    fun tearDown() {
        JoinMatchNotificationState.clear()
    }

    @Test
    fun shouldRunMatchFoundLobbyAlert_trueBeforeGameScreenOpens() {
        val match = lobbyMatch("m1")
        assertTrue(
            PreGameLobbySoundPolicy.shouldRunMatchFoundLobbyAlert(
                match = match,
                uid = "p1",
                visibleMatchScreenId = null,
            ),
        )
    }

    @Test
    fun shouldRunMatchFoundLobbyAlert_falseOnWaitingForOpponentScreen() {
        val match = lobbyMatch("m1")
        assertFalse(
            PreGameLobbySoundPolicy.shouldRunMatchFoundLobbyAlert(
                match = match,
                uid = "p1",
                visibleMatchScreenId = "m1",
            ),
        )
    }

    @Test
    fun shouldRunMatchFoundLobbyAlert_trueDuringNotificationAlertPhase() {
        val match = lobbyMatch("m1")
        JoinMatchNotificationState.beginLobbyAlertPhase(match)
        assertTrue(
            PreGameLobbySoundPolicy.shouldRunMatchFoundLobbyAlert(
                match = match,
                uid = "p1",
                visibleMatchScreenId = null,
            ),
        )
    }

    @Test
    fun shouldRunMatchFoundLobbyAlert_falseOnGameScreenEvenDuringAlertPhase() {
        val match = lobbyMatch("m1")
        JoinMatchNotificationState.beginLobbyAlertPhase(match)
        assertFalse(
            PreGameLobbySoundPolicy.shouldRunMatchFoundLobbyAlert(
                match = match,
                uid = "p1",
                visibleMatchScreenId = "m1",
            ),
        )
    }

    private fun lobbyMatch(id: String): Match = Match(
        id = id,
        status = MatchStatus.LOBBY,
        player1 = "p1",
        player2 = "p2",
    )
}
