package com.rpsonline.app.ui.util

import android.content.Context
import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.Move
import com.rpsonline.app.data.model.RoundResult
import com.rpsonline.app.data.preferences.SoundFeedbackMode
import kotlinx.coroutines.delay

fun roundResolutionRepetitions(resolved: RoundResult, userId: String): Int =
    when (resolved.winner) {
        "tie" -> 2
        userId -> 3
        else -> 1
    }

/** Move-sound bursts (1/2/3) plus a single haptic when a round resolves. */
suspend fun playRoundResolutionFeedback(
    context: Context,
    move: Move,
    repetitions: Int,
    mode: SoundFeedbackMode,
    moveSoundPlayer: MoveSoundPlayer,
) {
    if (mode.allowsHaptic()) {
        MatchClockHaptics.initialize(context)
        MatchClockHaptics.pulseTick()
    }
    val reps = repetitions.coerceIn(1, 3)
    repeat(reps) { index ->
        if (mode.allowsSound()) {
            moveSoundPlayer.playOnce(move)
        } else {
            delay(ROUND_RESOLUTION_MUTED_BEAT_MS)
        }
        if (index < reps - 1) {
            delay(ROUND_RESOLUTION_BURST_GAP_MS)
        }
    }
}

/** Plays live round-resolution feedback once; returns false when skipped or invalid. */
suspend fun playLiveRoundResolutionFeedback(
    context: Context,
    match: Match,
    resolved: RoundResult,
    userId: String,
    mode: SoundFeedbackMode,
    moveSoundPlayer: MoveSoundPlayer,
    pulseNotifier: RoundResolutionPulseNotifier,
): Boolean {
    val key = roundResolutionKey(resolved, match.id)
    if (pulseNotifier.shouldSkipFeedback(key)) return false
    val choice = if (userId == match.player1) resolved.player1Choice else resolved.player2Choice
    val move = Move.fromString(choice) ?: run {
        pulseNotifier.markFeedbackComplete(resolved, match.id)
        return false
    }
    pulseNotifier.beginFeedback(key)
    var finished = false
    try {
        playRoundResolutionFeedback(
            context = context,
            move = move,
            repetitions = roundResolutionRepetitions(resolved, userId),
            mode = mode,
            moveSoundPlayer = moveSoundPlayer,
        )
        finished = true
    } finally {
        pulseNotifier.endFeedback(
            resolved = resolved,
            matchId = match.id,
            key = key,
            success = finished,
        )
    }
    return finished
}

fun playReadyFeedback(
    context: Context,
    tickPlayer: ClockTickPlayer,
    mode: SoundFeedbackMode,
) {
    if (mode.allowsSound()) {
        tickPlayer.playReadyTick()
    }
    if (mode.allowsHaptic()) {
        MatchClockHaptics.initialize(context)
        MatchClockHaptics.pulseTick()
    }
}

fun playMatchFoundFeedback(
    context: Context,
    tickPlayer: ClockTickPlayer,
    mode: SoundFeedbackMode,
) {
    if (mode.allowsSound()) {
        tickPlayer.playReadyTick()
    }
    if (mode.allowsHaptic()) {
        MatchClockHaptics.initialize(context)
        MatchClockHaptics.pulseTick()
    }
}
