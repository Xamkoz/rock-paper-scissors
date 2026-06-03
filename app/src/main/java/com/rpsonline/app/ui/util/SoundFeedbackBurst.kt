package com.rpsonline.app.ui.util

import android.content.Context
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
