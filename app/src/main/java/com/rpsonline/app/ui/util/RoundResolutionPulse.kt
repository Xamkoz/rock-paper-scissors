package com.rpsonline.app.ui.util



import androidx.compose.runtime.compositionLocalOf

import androidx.compose.runtime.getValue

import androidx.compose.runtime.mutableIntStateOf

import androidx.compose.runtime.mutableStateOf

import androidx.compose.runtime.setValue

import com.rpsonline.app.data.model.Match

import com.rpsonline.app.data.model.RoundResult

import com.rpsonline.app.data.model.MatchStatus
import com.rpsonline.app.data.preferences.SoundFeedbackMode

import kotlinx.coroutines.delay



data class RoundResolutionKey(

    val matchId: String,

    val roundNumber: Int,

)



fun roundResolutionKey(resolved: RoundResult, matchId: String) =

    RoundResolutionKey(matchId, resolved.roundNumber)

/** True when [resolved] is the series-winning round for [match]. */
fun isMatchDecidingRound(match: Match, resolved: RoundResult): Boolean {
    val winnerId = resolved.winner ?: return false
    if (winnerId == "tie") return false
    val winsToFinish = match.matchMode.winsToFinish
    val winnerRoundWins = match.rounds.count {
        it.resolvedAt != null && it.winner == winnerId
    }
    return winnerRoundWins >= winsToFinish
}

/** Match-ending rounds play from the game screen after dual reveal. */
fun shouldDeferRoundResolutionFeedbackToMatchScreen(match: Match, resolved: RoundResult): Boolean =
    match.status == MatchStatus.COMPLETED ||
        match.status == MatchStatus.ABANDONED ||
        (match.status == MatchStatus.ACTIVE && isMatchDecidingRound(match, resolved))

/** Tracks round-resolution sound feedback and match-end navigation gating. */
class RoundResolutionPulseNotifier {

    var liveSessionGeneration by mutableIntStateOf(0)

        private set



    private var liveMatchIds by mutableStateOf(setOf<String>())

    private var historicalMatchIds by mutableStateOf(setOf<String>())

    private var feedbackCompleteKeys by mutableStateOf(setOf<RoundResolutionKey>())

    private var inFlightKeys by mutableStateOf(setOf<RoundResolutionKey>())



    fun isLiveMatch(matchId: String): Boolean = matchId in liveMatchIds



    /** Called when the in-game screen opens — clears stale suppressions for this match. */

    fun enterLiveMatch(matchId: String) {

        liveMatchIds = liveMatchIds + matchId

        feedbackCompleteKeys = feedbackCompleteKeys.filterNot { it.matchId == matchId }.toSet()

        inFlightKeys = inFlightKeys.filterNot { it.matchId == matchId }.toSet()

        liveSessionGeneration++

    }



    fun leaveLiveMatch(matchId: String) {

        liveMatchIds = liveMatchIds - matchId

    }



    /** Skip replay for rounds already resolved before the game screen opens. */

    fun baselineHistoricalRound(resolved: RoundResult, matchId: String) {

        if (matchId in liveMatchIds || matchId in historicalMatchIds) return

        if (resolved.player1Choice == null || resolved.player2Choice == null) return

        historicalMatchIds = historicalMatchIds + matchId

        markFeedbackComplete(resolved, matchId)

    }



    fun isFeedbackComplete(resolved: RoundResult, matchId: String): Boolean {

        if (resolved.player1Choice == null || resolved.player2Choice == null) return true

        return roundResolutionKey(resolved, matchId) in feedbackCompleteKeys

    }



    fun isFeedbackInFlight(key: RoundResolutionKey): Boolean = key in inFlightKeys



    fun shouldSkipFeedback(key: RoundResolutionKey): Boolean =

        key in feedbackCompleteKeys || key in inFlightKeys



    fun beginFeedback(key: RoundResolutionKey) {

        inFlightKeys = inFlightKeys + key

    }



    fun endFeedback(resolved: RoundResult, matchId: String, key: RoundResolutionKey, success: Boolean) {

        inFlightKeys = inFlightKeys - key

        if (success) {

            markFeedbackComplete(resolved, matchId)

        }

    }



    /** True while move-sound / burst feedback for [resolved] has not finished yet. */

    fun shouldSuppressClockTickFor(resolved: RoundResult?, matchId: String): Boolean {

        if (resolved == null || resolved.player1Choice == null || resolved.player2Choice == null) {

            return false

        }

        return !isFeedbackComplete(resolved, matchId)

    }



    fun markFeedbackComplete(resolved: RoundResult, matchId: String) {

        val key = roundResolutionKey(resolved, matchId)

        feedbackCompleteKeys = feedbackCompleteKeys + key

    }



    /** Clears stale in-flight state and marks feedback complete so navigation cannot deadlock. */

    fun forceFeedbackComplete(resolved: RoundResult, matchId: String) {

        val key = roundResolutionKey(resolved, matchId)

        inFlightKeys = inFlightKeys - key

        markFeedbackComplete(resolved, matchId)

    }

}



val LocalRoundResolutionPulse = compositionLocalOf<RoundResolutionPulseNotifier?> { null }



/** Gap between move-sound bursts — keep in sync with [MoveSoundPlayer]. */

const val ROUND_RESOLUTION_BURST_GAP_MS = 70L



/** Fallback beat length when move sounds are muted. */

const val ROUND_RESOLUTION_MUTED_BEAT_MS = 165L



/** Typical move clip length — used to cap post-game wait; clips are usually shorter. */
const val TYPICAL_MOVE_SOUND_MS = 850L

/** Estimated playback time for resolution sound bursts (single haptic is not counted). */
fun roundResolutionFeedbackDurationMs(repetitions: Int, mode: SoundFeedbackMode): Long {
    val reps = repetitions.coerceIn(1, 3)
    val beatMs = if (mode.allowsSound()) TYPICAL_MOVE_SOUND_MS else ROUND_RESOLUTION_MUTED_BEAT_MS
    return reps * beatMs + (reps - 1) * ROUND_RESOLUTION_BURST_GAP_MS
}



/** Pause on the match screen after final-round feedback before opening results. */

const val MATCH_END_NAVIGATION_DELAY_MS = 2_000L



/** If feedback never starts after match end, unblock navigation quickly. */

const val MATCH_END_FEEDBACK_START_GRACE_MS = 500L



/** Hard cap when final-round feedback never completes on this client. */

const val MATCH_END_FEEDBACK_MAX_WAIT_MS = 3_500L



/** Blocks until final-round resolution feedback has finished. */

suspend fun awaitMatchEndResolutionFeedback(

    pulseNotifier: RoundResolutionPulseNotifier?,

    match: Match,

    userId: String? = null,

    feedbackMode: SoundFeedbackMode = SoundFeedbackMode.SOUND_AND_HAPTIC,

    maxWaitMs: Long? = null,

) {

    if (pulseNotifier == null || match.status != MatchStatus.COMPLETED) return

    val resolved = match.lastResolvedRound() ?: return

    if (resolved.player1Choice == null || resolved.player2Choice == null) return

    if (pulseNotifier.isFeedbackComplete(resolved, match.id)) return



    val key = roundResolutionKey(resolved, match.id)

    val repetitions = userId?.let { roundResolutionRepetitions(resolved, it) } ?: 3

    val overallCapMs = (maxWaitMs ?: MATCH_END_FEEDBACK_MAX_WAIT_MS.toLong())
        .coerceAtMost(MATCH_END_FEEDBACK_MAX_WAIT_MS)

    val startGraceMs = minOf(MATCH_END_FEEDBACK_START_GRACE_MS, overallCapMs)

    val playbackBudgetMs = (roundResolutionFeedbackDurationMs(repetitions, feedbackMode) + 400L)

        .coerceAtMost(overallCapMs)



    var waitedMs = 0L

    while (!pulseNotifier.isFeedbackComplete(resolved, match.id)) {

        val inFlight = pulseNotifier.isFeedbackInFlight(key)

        if (!inFlight && waitedMs >= startGraceMs) {

            pulseNotifier.forceFeedbackComplete(resolved, match.id)

            return

        }

        if (inFlight && waitedMs >= playbackBudgetMs) {

            pulseNotifier.forceFeedbackComplete(resolved, match.id)

            return

        }

        if (waitedMs >= overallCapMs) {

            pulseNotifier.forceFeedbackComplete(resolved, match.id)

            return

        }

        delay(50)

        waitedMs += 50

    }

}


