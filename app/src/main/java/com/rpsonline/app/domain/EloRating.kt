package com.rpsonline.app.domain

import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchHistoryEntry
import com.rpsonline.app.data.model.MatchStatus
import com.rpsonline.app.data.model.ViewerMatchResolution
import com.rpsonline.app.data.model.toHistoryEntry
import com.rpsonline.app.data.model.viewerResolution
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

const val ELO_K_FACTOR = 32.0

private const val ELO_DOMINATION_BONUS = 2.0

private val ELO_MODE_MULTIPLIERS = mapOf(
    MatchMode.BO3 to 0.25,
    MatchMode.BO5 to 0.4,
    MatchMode.BO10 to 0.9,
)

data class EloDeltaPair(val deltaA: Int, val deltaB: Int)

data class LiveEloPreview(
    /** Pre-match rating stored on the match doc. */
    val myElo: Int,
    /** ELO change if the viewer wins the series. */
    val myWinDelta: Int,
    /** ELO change if the viewer loses the series (zero or negative). */
    val myLossDelta: Int,
    val opponentElo: Int,
)

fun calculateElo(
    ratingA: Int,
    ratingB: Int,
    scoreA: Double,
    k: Double = ELO_K_FACTOR,
): EloDeltaPair {
    val expectedA = 1.0 / (1.0 + 10.0.pow((ratingB - ratingA) / 400.0))
    val expectedB = 1.0 - expectedA
    val deltaA = (k * (scoreA - expectedA)).roundToInt()
    val deltaB = (k * (1.0 - scoreA - expectedB)).roundToInt()
    return EloDeltaPair(deltaA, deltaB)
}

fun eloMultiplierForMatch(
    matchMode: MatchMode,
    winnerId: String,
    player1: String,
    player2: String,
    player1Wins: Int,
    player2Wins: Int,
): Double {
    var multiplier = ELO_MODE_MULTIPLIERS[matchMode] ?: ELO_MODE_MULTIPLIERS.getValue(MatchMode.BO3)
    val loserWins = if (winnerId == player1) player2Wins else player1Wins
    if (loserWins == 0) {
        multiplier *= ELO_DOMINATION_BONUS
    }
    return multiplier
}

fun calculateMatchElo(
    ratingA: Int,
    ratingB: Int,
    scoreA: Double,
    matchMode: MatchMode,
    winnerId: String,
    player1: String,
    player2: String,
    player1Wins: Int,
    player2Wins: Int,
    k: Double = ELO_K_FACTOR,
): EloDeltaPair {
    val base = calculateElo(ratingA, ratingB, scoreA, k)
    val multiplier = eloMultiplierForMatch(
        matchMode = matchMode,
        winnerId = winnerId,
        player1 = player1,
        player2 = player2,
        player1Wins = player1Wins,
        player2Wins = player2Wins,
    )
    val deltaA = (base.deltaA * multiplier).roundToInt()
    return EloDeltaPair(deltaA = deltaA, deltaB = -deltaA)
}

/** Resolved round wins (excludes ties); used for live ELO preview during active play. */
fun Match.liveSeriesWinCounts(): Pair<Int, Int> {
    var player1Wins = 0
    var player2Wins = 0
    for (round in rounds) {
        if (round.resolvedAt == null) continue
        when (round.winner) {
            player1 -> player1Wins++
            player2 -> player2Wins++
        }
    }
    return player1Wins to player2Wins
}

/** Actual ELO change for a completed match, using pre-match ratings on the match doc. */
fun Match.resultEloPreview(userId: String): LiveEloPreview? {
    if (status != MatchStatus.COMPLETED) return null
    val myRating = myElo(userId) ?: return null
    val opponentRating = opponentElo(userId) ?: return null
    val myDelta = myEloDelta(userId) ?: return null
    val opponentDelta = opponentEloDelta(userId) ?: return null
    return LiveEloPreview(
        myElo = myRating,
        myWinDelta = myDelta,
        myLossDelta = opponentDelta,
        opponentElo = opponentRating,
    )
}

/** Win ELO swings for an in-progress match, using pre-match ratings on the match doc. */
fun Match.liveEloPreview(userId: String): LiveEloPreview? {
    if (status != MatchStatus.ACTIVE) return null
    val myRating = myElo(userId) ?: return null
    val opponentRating = opponentElo(userId) ?: return null
    val (player1Wins, player2Wins) = liveSeriesWinCounts()
    val player1ScoreIfViewerWins = if (userId == player1) 1.0 else 0.0
    val ifViewerWins = calculateMatchElo(
        ratingA = player1Elo ?: return null,
        ratingB = player2Elo ?: return null,
        scoreA = player1ScoreIfViewerWins,
        matchMode = matchMode,
        winnerId = userId,
        player1 = player1,
        player2 = player2,
        player1Wins = player1Wins,
        player2Wins = player2Wins,
    )
    val ifViewerLoses = calculateMatchElo(
        ratingA = player1Elo ?: return null,
        ratingB = player2Elo ?: return null,
        scoreA = 1.0 - player1ScoreIfViewerWins,
        matchMode = matchMode,
        winnerId = opponentId(userId),
        player1 = player1,
        player2 = player2,
        player1Wins = player1Wins,
        player2Wins = player2Wins,
    )
    val myWinDelta = if (userId == player1) ifViewerWins.deltaA else ifViewerWins.deltaB
    val myLossDelta = if (userId == player1) ifViewerLoses.deltaA else ifViewerLoses.deltaB
    return LiveEloPreview(
        myElo = myRating,
        myWinDelta = myWinDelta,
        myLossDelta = myLossDelta,
        opponentElo = opponentRating,
    )
}

fun inferOpponentPreMatchElo(
    myPreMatchElo: Int,
    myEloDelta: Int,
    myScore: Double,
): Int? {
    val expectedA = myScore - myEloDelta / ELO_K_FACTOR
    if (expectedA <= 0.0 || expectedA >= 1.0) return null
    val opponentPre = myPreMatchElo + 400.0 * log10(1.0 / expectedA - 1.0)
    return opponentPre.roundToInt().coerceAtLeast(0)
}

fun Match.myScore(userId: String): Double? =
    when (viewerResolution(userId)) {
        ViewerMatchResolution.WIN -> 1.0
        ViewerMatchResolution.LOSS -> 0.0
        ViewerMatchResolution.DRAW -> 0.5
        ViewerMatchResolution.ABANDONED, null -> null
    }

fun Match.opponentEloAtMatch(userId: String, myCurrentElo: Int): Int? {
    opponentElo(userId)?.let { return it }
    val myDelta = myEloDelta(userId) ?: return null
    val myScore = myScore(userId) ?: return null
    val myPre = myCurrentElo - myDelta
    return inferOpponentPreMatchElo(myPre, myDelta, myScore)
}

/** Newest-first [matches]; [myCurrentElo] is the viewer's rating after the newest listed match. */
fun enrichMatchHistoryWithOpponentElos(
    viewerId: String,
    myCurrentElo: Int,
    matches: List<Match>,
): List<MatchHistoryEntry> {
    var runningMyElo = myCurrentElo
    return matches.map { match ->
        val entry = match.toHistoryEntry(viewerId)
        val myDelta = match.myEloDelta(viewerId)
        val myPreMatchElo = when {
            entry.myElo != null -> entry.myElo
            myDelta != null -> runningMyElo - myDelta
            else -> null
        }
        val opponentElo = entry.opponentElo ?: run {
            if (myDelta == null || myPreMatchElo == null) return@run null
            val myScore = match.myScore(viewerId) ?: return@run null
            inferOpponentPreMatchElo(myPreMatchElo, myDelta, myScore)
        }
        if (myDelta != null) {
            runningMyElo -= myDelta
        }
        entry.copy(
            myElo = myPreMatchElo,
            opponentElo = opponentElo ?: entry.opponentElo,
        )
    }
}
