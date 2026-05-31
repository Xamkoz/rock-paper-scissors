package com.rpsonline.app.domain

import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus
import com.rpsonline.app.data.model.RoundResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EloRatingTest {
    @Test
    fun inferOpponentPreMatchElo_reversesCalculateElo() {
        val myPre = 987
        val opponentPre = 1031
        val myDelta = 18
        val score = 1.0
        val inferred = inferOpponentPreMatchElo(myPre, myDelta, score)
        assertEquals(opponentPre, inferred)
    }

    @Test
    fun inferOpponentPreMatchElo_returnsNullForInvalidExpectedScore() {
        assertEquals(null, inferOpponentPreMatchElo(1000, 32, 1.0))
    }

    @Test
    fun calculateMatchElo_scalesBo3WinToTwentyPercent() {
        val result = calculateMatchElo(
            ratingA = 1000,
            ratingB = 1000,
            scoreA = 1.0,
            matchMode = MatchMode.BO3,
            winnerId = "p1",
            player1 = "p1",
            player2 = "p2",
            player1Wins = 2,
            player2Wins = 1,
        )
        assertEquals(3, result.deltaA)
        assertEquals(-3, result.deltaB)
    }

    @Test
    fun calculateMatchElo_doublesShutoutSwing() {
        val result = calculateMatchElo(
            ratingA = 1000,
            ratingB = 1000,
            scoreA = 1.0,
            matchMode = MatchMode.BO3,
            winnerId = "p1",
            player1 = "p1",
            player2 = "p2",
            player1Wins = 2,
            player2Wins = 0,
        )
        assertEquals(6, result.deltaA)
        assertEquals(-6, result.deltaB)
    }

    @Test
    fun liveEloPreview_mapsViewerWinDeltas() {
        val match = Match(
            id = "m1",
            player1 = "me",
            player2 = "opp",
            status = MatchStatus.ACTIVE,
            matchMode = MatchMode.BO10,
            player1Elo = 1000,
            player2Elo = 1000,
            player1Wins = 5,
            player2Wins = 4,
            rounds = listOf(
                RoundResult(roundNumber = 1, winner = "me", resolvedAt = 1L),
                RoundResult(roundNumber = 2, winner = "me", resolvedAt = 2L),
                RoundResult(roundNumber = 3, winner = "me", resolvedAt = 3L),
                RoundResult(roundNumber = 4, winner = "me", resolvedAt = 4L),
                RoundResult(roundNumber = 5, winner = "me", resolvedAt = 5L),
                RoundResult(roundNumber = 6, winner = "opp", resolvedAt = 6L),
                RoundResult(roundNumber = 7, winner = "opp", resolvedAt = 7L),
                RoundResult(roundNumber = 8, winner = "opp", resolvedAt = 8L),
                RoundResult(roundNumber = 9, winner = "opp", resolvedAt = 9L),
            ),
        )
        val preview = match.liveEloPreview("me")
        requireNotNull(preview)
        assertEquals(1000, preview.myElo)
        assertEquals(1000, preview.opponentElo)
        assertEquals(13, preview.myWinDelta)
        assertEquals(13, preview.opponentWinDelta)
    }

    @Test
    fun liveEloPreview_shutoutBonusClearsAfterLosingRound() {
        val shutoutMatch = Match(
            player1 = "me",
            player2 = "opp",
            status = MatchStatus.ACTIVE,
            matchMode = MatchMode.BO3,
            player1Elo = 1000,
            player2Elo = 1000,
        )
        assertEquals(6, shutoutMatch.liveEloPreview("me")!!.myWinDelta)

        val afterLoss = shutoutMatch.copy(
            rounds = listOf(
                RoundResult(roundNumber = 1, winner = "opp", resolvedAt = 1L),
            ),
        )
        assertEquals(3, afterLoss.liveEloPreview("me")!!.myWinDelta)
    }

    @Test
    fun resultEloPreview_usesActualDeltasFromCompletedMatch() {
        val match = Match(
            id = "m1",
            player1 = "me",
            player2 = "opp",
            status = MatchStatus.COMPLETED,
            player1Elo = 1000,
            player2Elo = 1000,
            player1EloDelta = 13,
            player2EloDelta = -13,
        )
        val preview = match.resultEloPreview("me")
        requireNotNull(preview)
        assertEquals(1000, preview.myElo)
        assertEquals(1000, preview.opponentElo)
        assertEquals(13, preview.myWinDelta)
        assertEquals(-13, preview.opponentWinDelta)
    }

    @Test
    fun liveEloPreview_returnsNullWhenRatingsMissing() {
        val match = Match(status = MatchStatus.ACTIVE)
        assertNull(match.liveEloPreview("me"))
    }
}
