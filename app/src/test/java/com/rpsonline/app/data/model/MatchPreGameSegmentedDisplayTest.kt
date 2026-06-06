package com.rpsonline.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchPreGameSegmentedDisplayTest {

    @Test
    fun isPreGameSegmentedDisplayPhase_trueForLobby() {
        val match = lobbyMatch()
        assertTrue(match.isPreGameSegmentedDisplayPhase(userId = "p1"))
    }

    @Test
    fun isPreGameSegmentedDisplayPhase_trueWhileWaitingForOpponentReady() {
        val match = lobbyMatch().copy(
            player1Ready = true,
            player2Ready = false,
        )
        assertTrue(match.isPreGameSegmentedDisplayPhase(userId = "p1"))
    }

    @Test
    fun isPreGameSegmentedDisplayPhase_trueForActiveBeforeRoundStarts() {
        val match = lobbyMatch().copy(
            status = MatchStatus.ACTIVE,
            player1Ready = true,
            player2Ready = true,
            rounds = listOf(RoundResult(roundNumber = 1)),
        )
        assertTrue(match.isPreGameSegmentedDisplayPhase(userId = "p1"))
    }

    @Test
    fun isPreGameSegmentedDisplayPhase_trueWhileWaitingForBothReadyOnActive() {
        val match = lobbyMatch().copy(
            status = MatchStatus.ACTIVE,
            player1Ready = true,
            player2Ready = false,
        )
        assertTrue(match.isPreGameSegmentedDisplayPhase(userId = "p1"))
    }

    @Test
    fun segmentedMatchElapsedAnchorMs_nullUntilGameplayStarts() {
        val match = lobbyMatch().copy(
            status = MatchStatus.ACTIVE,
            player1Ready = true,
            player2Ready = true,
            rounds = listOf(RoundResult(roundNumber = 1)),
        )
        assertFalse(match.hasGameplayStarted())
        assertNull(match.segmentedMatchElapsedAnchorMs())
    }

    @Test
    fun segmentedMatchElapsedAnchorMs_usesFirstRoundStartedAt() {
        val match = lobbyMatch().copy(
            status = MatchStatus.ACTIVE,
            player1Ready = true,
            player2Ready = true,
            rounds = listOf(
                RoundResult(roundNumber = 1, startedAt = 500L),
                RoundResult(roundNumber = 2, startedAt = 2_000L),
            ),
        )
        assertEquals(500L, match.segmentedMatchElapsedAnchorMs())
    }

    @Test
    fun bothPlayersReady_requiresBothFlags() {
        val oneReady = lobbyMatch().copy(player1Ready = true, player2Ready = false)
        val bothReady = lobbyMatch().copy(player1Ready = true, player2Ready = true)
        assertFalse(oneReady.bothPlayersReady())
        assertTrue(bothReady.bothPlayersReady())
    }

    @Test
    fun isPreGameSegmentedDisplayPhase_falseOnceRoundStarts() {
        val match = lobbyMatch().copy(
            status = MatchStatus.ACTIVE,
            player1Ready = true,
            player2Ready = true,
            rounds = listOf(
                RoundResult(
                    roundNumber = 1,
                    startedAt = 100L,
                ),
            ),
        )
        assertFalse(match.isPreGameSegmentedDisplayPhase(userId = "p1"))
    }

    @Test
    fun hasGameplayStarted_falseForLobbyPlaceholderRound() {
        val match = lobbyMatch().copy(
            rounds = listOf(RoundResult(roundNumber = 1)),
        )
        assertFalse(match.hasGameplayStarted())
    }

    @Test
    fun hasGameplayStarted_trueOnceRoundHasStartedAt() {
        val match = lobbyMatch().copy(
            rounds = listOf(
                RoundResult(
                    roundNumber = 1,
                    startedAt = 100L,
                ),
            ),
        )
        assertTrue(match.hasGameplayStarted())
    }

    private fun lobbyMatch(): Match = Match(
        id = "m1",
        status = MatchStatus.LOBBY,
        player1 = "p1",
        player2 = "p2",
    )
}
