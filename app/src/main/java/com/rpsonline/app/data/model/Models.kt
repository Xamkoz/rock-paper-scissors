package com.rpsonline.app.data.model

import com.rpsonline.app.domain.GameRules
import com.rpsonline.app.domain.MatchMode

enum class Move(val label: String) {
    ROCK("Rock"),
    PAPER("Paper"),
    SCISSORS("Scissors");

    companion object {
        fun fromString(value: String?): Move? =
            entries.find { it.name.equals(value, ignoreCase = true) }
    }
}

enum class MatchStatus {
    LOBBY,
    ACTIVE,
    COMPLETED,
    ABANDONED;

    companion object {
        fun fromString(value: String?): MatchStatus = when (value?.lowercase()) {
            "lobby" -> LOBBY
            "active" -> ACTIVE
            "completed" -> COMPLETED
            "abandoned" -> ABANDONED
            else -> ACTIVE
        }
    }
}

enum class MatchEndReason {
    NORMAL,
    ROUND_TIMEOUT,
    CLOCK_TIMEOUT;

    companion object {
        fun fromString(value: String?): MatchEndReason? = when (value?.lowercase()) {
            "round_timeout" -> ROUND_TIMEOUT
            "clock_timeout" -> CLOCK_TIMEOUT
            "normal" -> NORMAL
            else -> null
        }
    }
}

enum class RoundEndReason {
    NORMAL,
    ROUND_TIMEOUT,
    CLOCK_TIMEOUT,
    CANCELLED;

    companion object {
        fun fromString(value: String?): RoundEndReason? = when (value?.lowercase()) {
            "normal", "tie" -> NORMAL
            "round_timeout" -> ROUND_TIMEOUT
            "clock_timeout" -> CLOCK_TIMEOUT
            "cancelled" -> CANCELLED
            else -> null
        }
    }
}

data class RoundResult(
    val roundNumber: Int = 0,
    val player1Submitted: Boolean = false,
    val player2Submitted: Boolean = false,
    val player1Choice: String? = null,
    val player2Choice: String? = null,
    val winner: String? = null,
    val endReason: RoundEndReason? = null,
    val resolvedAt: Long? = null,
    val startedAt: Long? = null,
    val deadline: Long? = null,
    val player1MoveMs: Int? = null,
    val player2MoveMs: Int? = null,
) {
    fun roundStartMs(): Long? =
        startedAt ?: deadline?.let { it - GameRules.ROUND_TIMEOUT_SECONDS * 1000L }

    /** Seconds left in this round, capped at [GameRules.ROUND_TIMEOUT_SECONDS]. */
    fun roundSecondsRemaining(nowMs: Long = System.currentTimeMillis()): Int? {
        val startMs = roundStartMs() ?: return null
        val timeoutMs = GameRules.ROUND_TIMEOUT_SECONDS * 1000L
        val remainingMs = startMs + timeoutMs - nowMs
        return ((remainingMs + 999) / 1000).toInt()
            .coerceIn(0, GameRules.ROUND_TIMEOUT_SECONDS)
    }

    fun isRecapRound(): Boolean = resolvedAt != null

    fun isCancelledRound(): Boolean =
        endReason == RoundEndReason.CANCELLED ||
            (endReason == null && resolvedAt != null && winner == null)

    fun effectiveEndReason(): RoundEndReason? = endReason ?: inferLegacyEndReason()

    private fun inferLegacyEndReason(): RoundEndReason? = when {
        isCancelledRound() -> RoundEndReason.CANCELLED
        winner != null && (winner == "tie" || (player1Choice != null && player2Choice != null)) ->
            RoundEndReason.NORMAL
        winner != null -> null
        else -> null
    }

    /** True when this player locked in a move for this round (open or resolved). */
    fun hasSubmittedFor(userId: String, player1Id: String): Boolean = when {
        userId == player1Id -> player1Submitted || player1Choice != null
        else -> player2Submitted || player2Choice != null
    }

    fun opponentHasSubmittedFor(userId: String, player1Id: String): Boolean = when {
        userId == player1Id -> player2Submitted || player2Choice != null
        else -> player1Submitted || player1Choice != null
    }
}

data class Match(
    val id: String = "",
    val player1: String = "",
    val player2: String = "",
    val player1Name: String = "",
    val player2Name: String = "",
    val matchMode: MatchMode = MatchMode.BO3,
    val status: MatchStatus = MatchStatus.ACTIVE,
    val player1Ready: Boolean = false,
    val player2Ready: Boolean = false,
    val readyDeadlineAt: Long = 0L,
    val currentRound: Int = 1,
    val player1Wins: Int = 0,
    val player2Wins: Int = 0,
    val player1MoveTimeMs: Long = 0,
    val player2MoveTimeMs: Long = 0,
    val player1MoveCount: Int = 0,
    val player2MoveCount: Int = 0,
    val player1ClockMs: Long = GameRules.INITIAL_CLOCK_MS,
    val player2ClockMs: Long = GameRules.INITIAL_CLOCK_MS,
    val clocksUpdatedAt: Long = 0L,
    val rounds: List<RoundResult> = emptyList(),
    val winnerId: String? = null,
    val resolution: MatchResolution? = null,
    val endReason: MatchEndReason? = null,
    val player1EloDelta: Int? = null,
    val player2EloDelta: Int? = null,
    val player1Elo: Int? = null,
    val player2Elo: Int? = null,
    val createdAt: Long = 0L,
    val lastActivityAt: Long = 0L,
) {
    fun isParticipant(userId: String): Boolean =
        userId == player1 || userId == player2

    /** True when the user should resume this match instead of joining a new queue. */
    fun isLiveForReconnect(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (status == MatchStatus.LOBBY) {
            if (isReadyDeadlineExpired(nowMs)) return false
            val last = lastActivityAt
            return last <= 0L || nowMs - last <= LOBBY_RECONNECT_GRACE_MS
        }
        if (status != MatchStatus.ACTIVE) return false
        val last = lastActivityAt
        return last > 0 && nowMs - last <= ACTIVE_RECONNECT_GRACE_MS
    }

    fun isPlayerReady(userId: String): Boolean =
        if (userId == player1) player1Ready else player2Ready

    fun isOpponentReady(userId: String): Boolean =
        if (userId == player1) player2Ready else player1Ready

    /** Lobby ready window; falls back to createdAt + grace when field is missing (older matches). */
    fun effectiveReadyDeadlineAtMs(nowMs: Long = System.currentTimeMillis()): Long {
        if (readyDeadlineAt > 0L) return readyDeadlineAt
        if (createdAt > 0L) return createdAt + LOBBY_READY_MS
        return nowMs + LOBBY_READY_MS
    }

    fun readySecondsRemaining(nowMs: Long = System.currentTimeMillis()): Int {
        val deadlineMs = effectiveReadyDeadlineAtMs(nowMs)
        return ((deadlineMs - nowMs) / 1_000L).toInt().coerceAtLeast(0)
    }

    fun isReadyDeadlineExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        readySecondsRemaining(nowMs) <= 0

    fun opponentName(userId: String): String =
        if (userId == player1) player2Name else player1Name

    fun myName(userId: String): String =
        if (userId == player1) player1Name else player2Name

    fun opponentId(userId: String): String =
        if (userId == player1) player2 else player1

    fun myWins(userId: String): Int =
        if (scoreFromResolvedRounds()) {
            roundWinsFor(userId)
        } else {
            if (userId == player1) player1Wins else player2Wins
        }

    fun opponentWins(userId: String): Int =
        if (scoreFromResolvedRounds()) {
            roundWinsFor(opponentId(userId))
        } else {
            if (userId == player1) player2Wins else player1Wins
        }

    private fun scoreFromResolvedRounds(): Boolean =
        status == MatchStatus.COMPLETED &&
            (endReason == MatchEndReason.ROUND_TIMEOUT || endReason == MatchEndReason.CLOCK_TIMEOUT)

    private fun roundWinsFor(playerId: String): Int =
        rounds.count { it.resolvedAt != null && it.winner == playerId }

    /** Winning throw for each round this player won, oldest to newest. */
    fun winMovesFor(playerId: String): List<Move> =
        rounds
            .filter { it.resolvedAt != null && it.winner == playerId }
            .sortedBy { it.roundNumber }
            .mapNotNull { round ->
                val choice = if (playerId == player1) round.player1Choice else round.player2Choice
                Move.fromString(choice)
            }

    fun myClockMs(userId: String): Long =
        if (userId == player1) player1ClockMs else player2ClockMs

    fun opponentClockMs(userId: String): Long =
        if (userId == player1) player2ClockMs else player1ClockMs

    fun myEloDelta(userId: String): Int? =
        if (userId == player1) player1EloDelta else player2EloDelta

    fun opponentEloDelta(userId: String): Int? =
        if (userId == player1) player2EloDelta else player1EloDelta

    fun opponentElo(userId: String): Int? =
        if (userId == player1) player2Elo else player1Elo

    fun myElo(userId: String): Int? =
        if (userId == player1) player1Elo else player2Elo

    fun currentRoundData(): RoundResult? =
        rounds.filter { it.resolvedAt == null }.lastOrNull()
            ?: rounds.find { it.roundNumber == currentRound }

    fun lastResolvedRound(): RoundResult? =
        rounds
            .filter { it.resolvedAt != null }
            .maxByOrNull { it.resolvedAt ?: 0L }

    /** Last round was a tie; next round is open (score unchanged). */
    fun pendingDrawReplay(): RoundResult? {
        val last = lastResolvedRound() ?: return null
        if (last.winner != "tie") return null
        val open = openRound() ?: return null
        return if (open.roundNumber > last.roundNumber) last else null
    }

    fun openRound(): RoundResult? =
        rounds.filter { it.resolvedAt == null }.lastOrNull()

    /** True while the local player still has an open-round pick and their match clock is ticking. */
    fun isPlayerClockRunning(userId: String?): Boolean {
        val uid = userId ?: return false
        if (status != MatchStatus.ACTIVE) return false
        val open = openRound() ?: return false
        return !open.hasSubmittedFor(uid, player1)
    }

    /** Choice for [userId] in [roundNumber], including after the round has resolved. */
    fun choiceForPlayerInRound(userId: String, roundNumber: Int): String? =
        rounds.find { it.roundNumber == roundNumber }?.let { round ->
            if (userId == player1) round.player1Choice else round.player2Choice
        }

    fun hasSubmittedInRound(userId: String, roundNumber: Int): Boolean =
        rounds.find { it.roundNumber == roundNumber }?.hasSubmittedFor(userId, player1) == true

    fun opponentHasSubmittedInRound(userId: String, roundNumber: Int): Boolean =
        rounds.find { it.roundNumber == roundNumber }?.opponentHasSubmittedFor(userId, player1) == true

    /** Last round had a winner; next round is open — keep showing outcome until play resumes. */
    fun pendingRoundOutcome(): RoundResult? {
        val last = lastResolvedRound() ?: return null
        if (last.winner == "tie" || last.winner == null) return null
        if (last.player1Choice == null || last.player2Choice == null) return null
        val open = openRound() ?: return null
        return if (open.roundNumber > last.roundNumber) last else null
    }

    fun resolvedRoundRecaps(userId: String): List<RoundRecap> =
        rounds
            .filter { it.isRecapRound() }
            .sortedWith(compareBy({ it.resolvedAt ?: 0L }, { it.roundNumber }))
            .map { round -> round.toRoundRecap(userId, player1) }

    companion object {
        private const val LOBBY_RECONNECT_GRACE_MS = 20_000L
        const val LOBBY_READY_MS = 15_000L
        private const val ACTIVE_RECONNECT_GRACE_MS = 90_000L
    }
}

private fun RoundResult.toRoundRecap(userId: String, player1: String): RoundRecap {
    val myChoice = if (userId == player1) player1Choice else player2Choice
    val opponentChoice = if (userId == player1) player2Choice else player1Choice
    val isCancelled = isCancelledRound()
    val isDraw = winner == "tie"
    val reason = effectiveEndReason()
    val won = when {
        isCancelled -> null
        winner == "tie" -> null
        winner == userId -> true
        else -> false
    }
    val forfeitTimeout = reason == RoundEndReason.ROUND_TIMEOUT ||
        reason == RoundEndReason.CLOCK_TIMEOUT
    val myTimedOut = when {
        isCancelled || isDraw -> false
        forfeitTimeout -> won == false
        else -> won == false && myChoice == null
    }
    val opponentTimedOut = when {
        isCancelled || isDraw -> false
        forfeitTimeout -> won == true
        else -> won == true && opponentChoice == null
    }
    val myMoveMsRaw = if (userId == player1) player1MoveMs else player2MoveMs
    val opponentMoveMsRaw = if (userId == player1) player2MoveMs else player1MoveMs
    return RoundRecap(
        roundNumber = roundNumber,
        myChoice = myChoice,
        opponentChoice = opponentChoice,
        myMoveMs = if (isCancelled) {
            recapCancelledMoveMs(myMoveMsRaw, this)
        } else {
            recapMoveDisplayMs(myMoveMsRaw, myTimedOut, this)
        },
        opponentMoveMs = if (isCancelled) {
            recapCancelledMoveMs(opponentMoveMsRaw, this)
        } else {
            recapMoveDisplayMs(opponentMoveMsRaw, opponentTimedOut, this)
        },
        won = won,
        isDraw = isDraw,
        isCancelled = isCancelled,
        endReason = reason,
        opponentTimedOut = opponentTimedOut,
        iTimedOut = myTimedOut,
    )
}

data class RoundRecap(
    val roundNumber: Int,
    val myChoice: String?,
    val opponentChoice: String?,
    val myMoveMs: Int? = null,
    val opponentMoveMs: Int? = null,
    val won: Boolean?,
    val isDraw: Boolean = false,
    val isCancelled: Boolean = false,
    val endReason: RoundEndReason? = null,
    val opponentTimedOut: Boolean = false,
    val iTimedOut: Boolean = false,
)

data class UserProfile(
    val uid: String = "",
    val displayName: String = "",
    val photoUrl: String? = null,
    val elo: Int = 1000,
    val wins: Int = 0,
    val losses: Int = 0,
    val draws: Int = 0,
    val roundsWon: Int = 0,
    val roundsLost: Int = 0,
    val roundsDraw: Int = 0,
    val moveTimeMs: Long = 0,
    val moveCount: Int = 0,
    val throwsRock: Int = 0,
    val throwsPaper: Int = 0,
    val throwsScissors: Int = 0,
    val activeMatchId: String? = null,
) {
    fun roundsPlayed(): Int = roundsWon + roundsLost + roundsDraw
}

data class LeaderboardEntry(
    val uid: String = "",
    val displayName: String = "",
    val elo: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val draws: Int = 0,
    val roundsWon: Int = 0,
    val roundsLost: Int = 0,
    val roundsDraw: Int = 0,
    val moveTimeMs: Long = 0,
    val moveCount: Int = 0,
    val throwsRock: Int = 0,
    val throwsPaper: Int = 0,
    val throwsScissors: Int = 0,
) {
    fun roundsPlayed(): Int = roundsWon + roundsLost + roundsDraw
}

data class MatchResult(
    val matchId: String,
    val won: Boolean,
    val eloDelta: Int,
    val myWins: Int,
    val opponentWins: Int,
    val opponentName: String,
)

data class MatchHistoryEntry(
    val matchId: String,
    val matchMode: MatchMode = MatchMode.BO3,
    val myUid: String = "",
    val myDisplayName: String,
    val opponentUid: String = "",
    val opponentName: String,
    val myElo: Int? = null,
    val opponentElo: Int? = null,
    val myWins: Int,
    val opponentWins: Int,
    val resolution: ViewerMatchResolution,
    val eloDelta: Int?,
    val opponentEloDelta: Int? = null,
    val lastActivityAt: Long,
    val recaps: List<RoundRecap>,
)

/** Elapsed time on a cancelled round when move ms was never recorded. */
internal fun recapCancelledMoveMs(moveMs: Int?, round: RoundResult): Int? =
    moveMs ?: roundElapsedMsAtTimeout(round).takeIf { round.resolvedAt != null }

/** Elapsed thinking time shown in history; on timeout uses round start → resolution when move ms is missing. */
internal fun recapMoveDisplayMs(moveMs: Int?, timedOut: Boolean, round: RoundResult): Int? {
    if (!timedOut) return moveMs
    if (moveMs != null) return moveMs
    return roundElapsedMsAtTimeout(round)
}

internal fun roundElapsedMsAtTimeout(round: RoundResult): Int {
    val start = round.roundStartMs()
    val end = round.resolvedAt
    if (start != null && end != null) {
        return (end - start).toInt().coerceIn(0, GameRules.ROUND_TIMEOUT_SECONDS * 1000)
    }
    return GameRules.ROUND_TIMEOUT_SECONDS * 1000
}

fun Match.toHistoryEntry(userId: String): MatchHistoryEntry {
    val myWins = myWins(userId)
    val opponentWins = opponentWins(userId)
    val resolution = viewerResolution(userId)
        ?: error("Match history requires a resolved match")
    return MatchHistoryEntry(
        matchId = id,
        matchMode = matchMode,
        myUid = userId,
        myDisplayName = myName(userId),
        opponentUid = opponentId(userId),
        opponentName = opponentName(userId),
        myElo = myElo(userId),
        opponentElo = opponentElo(userId),
        myWins = myWins,
        opponentWins = opponentWins,
        resolution = resolution,
        eloDelta = myEloDelta(userId),
        opponentEloDelta = opponentEloDelta(userId),
        lastActivityAt = lastActivityAt,
        recaps = resolvedRoundRecaps(userId),
    )
}
