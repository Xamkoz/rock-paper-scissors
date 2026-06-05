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

    @Test
    fun shouldRunMatchFoundLobbyAlert_falseWhenMatchAbandoned() {
        val match = lobbyMatch("m1").copy(status = MatchStatus.ABANDONED)
        assertFalse(
            PreGameLobbySoundPolicy.shouldRunMatchFoundLobbyAlert(
                match = match,
                uid = "p1",
                visibleMatchScreenId = null,
            ),
        )
    }

    @Test
    fun shouldRunMatchFoundLobbyAlert_falseWhenAlertPhaseStickyLobbyIsAbandoned() {
        val lobby = lobbyMatch("m1")
        JoinMatchNotificationState.beginLobbyAlertPhase(lobby)
        val abandoned = lobby.copy(status = MatchStatus.ABANDONED)
        JoinMatchNotificationState.bindLobby(abandoned)
        assertFalse(
            PreGameLobbySoundPolicy.shouldRunMatchFoundLobbyAlert(
                match = null,
                uid = "p1",
                visibleMatchScreenId = null,
            ),
        )
    }

    @Test
    fun shouldRunMatchFoundLobbyAlert_trueWhileActiveBeforeGameScreenOpens() {
        val active = lobbyMatch("m1").copy(
            status = MatchStatus.ACTIVE,
            lastActivityAt = System.currentTimeMillis(),
        )
        JoinMatchNotificationState.beginLobbyAlertPhase(active)
        assertTrue(
            PreGameLobbySoundPolicy.shouldRunMatchFoundLobbyAlert(
                match = active,
                uid = "p1",
                visibleMatchScreenId = null,
            ),
        )
    }

    @Test
    fun shouldRunMatchFoundLobbyAlert_keepsAlertPhaseWhenMatchBecomesActive() {
        val lobby = lobbyMatch("m1")
        JoinMatchNotificationState.beginLobbyAlertPhase(lobby)
        val active = lobby.copy(
            status = MatchStatus.ACTIVE,
            lastActivityAt = System.currentTimeMillis(),
        )
        JoinMatchNotificationState.bindLobby(active)
        assertTrue(JoinMatchNotificationState.isLobbyAlertPhase())
        assertTrue(
            PreGameLobbySoundPolicy.shouldRunMatchFoundLobbyAlert(
                match = null,
                uid = "p1",
                visibleMatchScreenId = null,
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
