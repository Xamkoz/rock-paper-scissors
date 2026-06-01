package com.rpsonline.app.ui.game

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** Minimum time the opponent's hidden pick stays visible before round reveal. */
const val OPPONENT_SELECTION_MIN_DISPLAY_MS = 500L

fun opponentSelectionElapsedMs(firstShownAtMs: Long, nowMs: Long): Long =
    if (firstShownAtMs == 0L) 0L else (nowMs - firstShownAtMs).coerceAtLeast(0L)

fun canRevealOpponentSelection(firstShownAtMs: Long, nowMs: Long): Boolean =
    firstShownAtMs == 0L ||
        opponentSelectionElapsedMs(firstShownAtMs, nowMs) >= OPPONENT_SELECTION_MIN_DISPLAY_MS

fun opponentSelectionRevealHoldMs(firstShownAtMs: Long, nowMs: Long): Long =
    if (firstShownAtMs == 0L) {
        OPPONENT_SELECTION_MIN_DISPLAY_MS
    } else {
        (OPPONENT_SELECTION_MIN_DISPLAY_MS - opponentSelectionElapsedMs(firstShownAtMs, nowMs))
            .coerceAtLeast(0L)
    }

/**
 * Delays opponent move reveal until their hidden pick has been on screen for
 * [OPPONENT_SELECTION_MIN_DISPLAY_MS].
 */
@Composable
fun rememberOpponentSelectionRevealAllowed(
    roundKey: Int?,
    opponentHasSubmitted: Boolean,
    opponentWouldReveal: Boolean,
): Boolean {
    var selectionFirstShownAtMs by remember(roundKey) { mutableLongStateOf(0L) }
    var revealAllowed by remember(roundKey) { mutableStateOf(false) }

    LaunchedEffect(roundKey, opponentHasSubmitted, opponentWouldReveal) {
        if (roundKey == null) {
            selectionFirstShownAtMs = 0L
            revealAllowed = false
            return@LaunchedEffect
        }

        if (!opponentHasSubmitted && !opponentWouldReveal) {
            selectionFirstShownAtMs = 0L
            revealAllowed = false
            return@LaunchedEffect
        }

        if (selectionFirstShownAtMs == 0L) {
            selectionFirstShownAtMs = SystemClock.elapsedRealtime()
        }

        if (!opponentWouldReveal) {
            revealAllowed = false
            return@LaunchedEffect
        }

        val holdMs = opponentSelectionRevealHoldMs(
            firstShownAtMs = selectionFirstShownAtMs,
            nowMs = SystemClock.elapsedRealtime(),
        )
        if (holdMs == 0L) {
            revealAllowed = true
        } else {
            revealAllowed = false
            delay(holdMs)
            revealAllowed = true
        }
    }

    return !opponentWouldReveal || revealAllowed
}

fun holdOpponentMoveReveal(
    opponentMove: PanelMovePresentation,
    revealAllowed: Boolean,
): PanelMovePresentation =
    if (opponentMove.display == PanelMoveDisplay.Revealed && !revealAllowed) {
        PanelMovePresentation(display = PanelMoveDisplay.Secret)
    } else {
        opponentMove
    }
