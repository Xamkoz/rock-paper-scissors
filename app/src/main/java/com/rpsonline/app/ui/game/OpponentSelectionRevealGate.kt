package com.rpsonline.app.ui.game

import com.rpsonline.app.data.model.Move
import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.RoundResult
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** Minimum time both moves stay hidden after round resolve before icons and outcome banner show. */
const val DUAL_SELECTION_MIN_DISPLAY_MS = 200L

/** Minimum time revealed recap (both icons + round banner) stays before next-round clocks. */
const val ROUND_RECAP_REVEALED_MIN_DISPLAY_MS = 1_800L

@Deprecated("Use DUAL_SELECTION_MIN_DISPLAY_MS", ReplaceWith("DUAL_SELECTION_MIN_DISPLAY_MS"))
const val OPPONENT_SELECTION_MIN_DISPLAY_MS = DUAL_SELECTION_MIN_DISPLAY_MS

fun dualSelectionElapsedMs(holdStartedAtMs: Long, nowMs: Long): Long =
    if (holdStartedAtMs == 0L) 0L else (nowMs - holdStartedAtMs).coerceAtLeast(0L)

fun canRevealDualSelection(holdStartedAtMs: Long, nowMs: Long): Boolean =
    holdStartedAtMs == 0L ||
        dualSelectionElapsedMs(holdStartedAtMs, nowMs) >= DUAL_SELECTION_MIN_DISPLAY_MS

/** Score on the panel before the just-resolved recap round is applied (for the 1s reveal gate). */
fun scoresBeforeRecapRound(
    myWins: Int,
    opponentWins: Int,
    recapRound: RoundResult?,
    userId: String,
): Pair<Int, Int> {
    val round = recapRound ?: return myWins to opponentWins
    return when (round.winner) {
        "tie", null -> myWins to opponentWins
        userId -> (myWins - 1).coerceAtLeast(0) to opponentWins
        else -> myWins to (opponentWins - 1).coerceAtLeast(0)
    }
}

fun winMovesBeforeRecapRound(
    match: Match,
    playerId: String,
    recapRound: RoundResult?,
): List<Move> {
    val round = recapRound ?: return match.winMovesFor(playerId)
    if (round.winner != playerId) return match.winMovesFor(playerId)
    return match.rounds
        .filter {
            it.resolvedAt != null &&
                it.winner == playerId &&
                it.roundNumber != round.roundNumber
        }
        .sortedBy { it.roundNumber }
        .mapNotNull { resolved ->
            val choice = if (playerId == match.player1) {
                resolved.player1Choice
            } else {
                resolved.player2Choice
            }
            Move.fromString(choice)
        }
}

fun dualSelectionRevealHoldMs(holdStartedAtMs: Long, nowMs: Long): Long =
    if (holdStartedAtMs == 0L) {
        DUAL_SELECTION_MIN_DISPLAY_MS
    } else {
        (DUAL_SELECTION_MIN_DISPLAY_MS - dualSelectionElapsedMs(holdStartedAtMs, nowMs))
            .coerceAtLeast(0L)
    }

/** Last round outcome while the next round is already open (e.g. local player submitted first). */
fun shouldShowPendingRoundOutcome(
    awaitingNextRound: Boolean,
    pendingOutcome: RoundResult?,
): Boolean = awaitingNextRound && pendingOutcome != null

/** Prefer resolved-round icons over blind-play on the new open round. */
fun preferResolvedRoundPanel(
    showOutcomeReveal: Boolean,
    showDrawReveal: Boolean,
    showPreviousRoundRecap: Boolean,
    showPendingRoundOutcome: Boolean,
    hasDrawReplay: Boolean,
    roundRecapComplete: Boolean,
    inRecapDualHold: Boolean = false,
    recapDismissed: Boolean = false,
): Boolean {
    if (inRecapDualHold) return true
    if (recapDismissed) return showOutcomeReveal || showDrawReveal
    if (roundRecapComplete) {
        return showOutcomeReveal || showDrawReveal
    }
    return showOutcomeReveal ||
        showDrawReveal ||
        showPreviousRoundRecap ||
        showPendingRoundOutcome ||
        (hasDrawReplay && showPreviousRoundRecap)
}

/** Move picker only after recap finishes — open round may already be the next one. */
fun shouldAllowRoundMovePicker(
    showRecapRoundMoves: Boolean,
    holdForResolvedRound: Boolean,
    recapDismissed: Boolean,
    showOutcomeReveal: Boolean,
    showDrawReveal: Boolean,
): Boolean {
    if (showRecapRoundMoves) return false
    if (holdForResolvedRound && !recapDismissed) return false
    if (showOutcomeReveal || showDrawReveal) return false
    return true
}

/** "Pick a move" title — blocked during dual-reveal hold and while recap still owns the panel. */
fun shouldShowPickMovePrompt(
    allowRoundMovePicker: Boolean,
    inDualSelectionHold: Boolean,
    showMovePicker: Boolean,
    hasDrawReplay: Boolean,
    awaitingNextRound: Boolean,
): Boolean {
    if (inDualSelectionHold || !allowRoundMovePicker) return false
    return showMovePicker || hasDrawReplay || awaitingNextRound
}

/** Post-resolve recap: both moves revealed together with the round outcome banner. */
fun resolveRecapMovePresentations(
    myChoice: String?,
    opponentChoice: String?,
): Pair<PanelMovePresentation, PanelMovePresentation> =
    PanelMovePresentation(
        move = Move.fromString(myChoice),
        display = PanelMoveDisplay.Revealed,
    ) to PanelMovePresentation(
        move = Move.fromString(opponentChoice),
        display = PanelMoveDisplay.Revealed,
    )

/** Open round is live and neither player has picked yet (clock placeholders). */
fun isOpenRoundAwaitingPicks(
    openRound: RoundResult?,
    hasSubmittedMove: Boolean,
    isSubmitting: Boolean,
    opponentHasSubmitted: Boolean,
    player1: String,
    userId: String,
): Boolean {
    if (openRound == null) return false
    if (hasSubmittedMove || isSubmitting || opponentHasSubmitted) return false
    if (openRound.hasSubmittedFor(userId, player1)) return false
    if (openRound.opponentHasSubmittedFor(userId, player1)) return false
    return true
}

/** Open round still in blind play or waiting on the server — not a fresh next round. */
fun isOpenRoundAwaitingServerResolve(openRound: RoundResult?): Boolean {
    if (openRound == null) return false
    if (openRound.resolvedAt != null || openRound.winner != null) return false
    return openRound.player1Submitted ||
        openRound.player2Submitted ||
        openRound.player1Choice != null ||
        openRound.player2Choice != null
}

/** Server shows the open round is mid-resolve (blind play done, winner not set yet). */
fun isOpenRoundResolving(openRound: RoundResult?): Boolean =
    isOpenRoundAwaitingServerResolve(openRound)

/** Block between-rounds recap while both players are locked in blind play before server echo. */
fun shouldSuppressBetweenRoundsRecap(
    openRound: RoundResult?,
    localHasSubmitted: Boolean,
    localOpponentSubmitted: Boolean,
    serverRoundSettled: Boolean,
): Boolean =
    !serverRoundSettled &&
        localHasSubmitted &&
        localOpponentSubmitted &&
        !isOpenRoundAwaitingServerResolve(openRound)

/**
 * Last resolved winner round while the next round is opening (or not yet in the snapshot).
 * Covers the gap when the opponent moved first and pendingRoundOutcome is not ready yet.
 */
fun resolveBetweenRoundsRecapRound(
    pendingOutcome: RoundResult?,
    lastResolved: RoundResult?,
    openRound: RoundResult?,
    suppressWhileLocalBlindComplete: Boolean = false,
): RoundResult? {
    pendingOutcome?.let { return it }
    if (suppressWhileLocalBlindComplete) return null
    val last = lastResolved ?: return null
    if (last.winner == null || last.winner == "tie") return null
    if (last.player1Choice == null || last.player2Choice == null) return null
    if (openRound != null && openRound.roundNumber <= last.roundNumber) return null
    if (isOpenRoundAwaitingServerResolve(openRound)) return null
    return last
}

fun isServerRoundSettled(
    showOutcomeReveal: Boolean,
    showDrawReveal: Boolean,
    awaitingNextRound: Boolean,
    hasPendingOutcome: Boolean,
): Boolean = showOutcomeReveal || showDrawReveal || awaitingNextRound || hasPendingOutcome

/** Winning/tie-less round to recap, including the frame where the open round already has a winner. */
fun resolveRecapRound(
    resolvedRound: RoundResult?,
    pendingOutcome: RoundResult?,
    lastResolved: RoundResult?,
    openRound: RoundResult?,
): RoundResult? {
    resolvedRound?.let { return it }
    resolveBetweenRoundsRecapRound(pendingOutcome, lastResolved, openRound)?.let { return it }
    val open = openRound ?: return null
    if (open.player1Choice == null || open.player2Choice == null) return null
    if (open.winner == null || open.winner == "tie") return null
    return open
}

/** Recap owns the panel once the server settled a round (even if hold state lags one frame). */
fun shouldActivateRoundRecapPhase(
    shouldHoldForResolvedRound: Boolean,
    openRoundResolving: Boolean,
    serverRoundSettled: Boolean,
): Boolean = shouldHoldForResolvedRound && (!openRoundResolving || serverRoundSettled)

/** Keep recap panel until timed dismiss — not when the next open round merely exists. */
fun shouldShowRecapRoundMoves(
    recapRound: RoundResult?,
    recapPhaseActive: Boolean,
    recapDismissed: Boolean,
    serverRoundSettled: Boolean = false,
): Boolean =
    recapRound != null &&
        !recapDismissed &&
        (recapPhaseActive || serverRoundSettled)

/** Final-round recap stays visible until post-match navigation, not only the timed dismiss. */
fun shouldHoldMatchEndRecapUntilPostMatch(
    inMatchEndTransition: Boolean,
    recapRevealStarted: Boolean,
    navigatedToPostMatch: Boolean,
): Boolean =
    inMatchEndTransition && recapRevealStarted && !navigatedToPostMatch

fun shouldShowRecapRoundMovesInPhase(
    recapPhaseOpen: Boolean,
    recapDismissed: Boolean,
    holdMatchEndRecapGate: Boolean,
): Boolean = recapPhaseOpen && (!recapDismissed || holdMatchEndRecapGate)

fun recapDismissedForUi(
    recapDismissed: Boolean,
    holdMatchEndRecapGate: Boolean,
): Boolean = recapDismissed && !holdMatchEndRecapGate

/** Live panel must not flash resolved icons before the timed recap sequence. */
fun shouldBlockLiveResolvedMoveReveal(
    serverRoundSettled: Boolean,
    recapRound: RoundResult?,
    recapPhaseActive: Boolean,
    openRoundResolving: Boolean,
): Boolean =
    recapPhaseActive ||
        openRoundResolving ||
        (serverRoundSettled && recapRound != null)

fun recapRevealedRemainingMs(revealedAtMs: Long, nowMs: Long): Long =
    if (revealedAtMs == 0L) {
        ROUND_RECAP_REVEALED_MIN_DISPLAY_MS
    } else {
        (ROUND_RECAP_REVEALED_MIN_DISPLAY_MS - (nowMs - revealedAtMs).coerceAtLeast(0L))
            .coerceAtLeast(0L)
    }

/** Distinct remember key so in-round play state does not reuse post-resolve hold timing. */
fun dualRevealHoldRoundKey(recapRound: RoundResult?): Int? =
    recapRound?.roundNumber?.let { it * 10_000 + 1 }

/** True once a resolved recap round should own the panel (before timed dismiss). */
fun isRecapPhaseOpen(
    recapRound: RoundResult?,
    recapPhaseActive: Boolean,
    serverRoundSettled: Boolean,
): Boolean =
    recapRound != null && (recapPhaseActive || serverRoundSettled)

/** @see isRecapPhaseOpen */
fun shouldRequestDualRevealGate(
    recapRound: RoundResult?,
    recapPhaseActive: Boolean,
    recapDismissed: Boolean,
    serverRoundSettled: Boolean,
): Boolean = isRecapPhaseOpen(recapRound, recapPhaseActive, serverRoundSettled)

fun shouldHoldForResolvedRound(
    resolvedRound: RoundResult?,
    player1Choice: String?,
    player2Choice: String?,
    showOutcomeReveal: Boolean,
    showDrawReveal: Boolean,
    awaitingNextRound: Boolean,
    betweenRoundsRecapRound: RoundResult?,
): Boolean {
    if (resolvedRound == null || player1Choice == null || player2Choice == null) return false
    return showOutcomeReveal ||
        showDrawReveal ||
        awaitingNextRound ||
        betweenRoundsRecapRound != null
}

/** True while resolved moves should stay on secret placeholders. */
fun shouldDelayDualSelectionReveal(
    holdStartedAtMs: Long,
    nowMs: Long,
    holdForResolvedRound: Boolean,
): Boolean {
    if (!holdForResolvedRound) return false
    if (holdStartedAtMs == 0L) return true
    return dualSelectionElapsedMs(holdStartedAtMs, nowMs) < DUAL_SELECTION_MIN_DISPLAY_MS
}

/**
 * After a round resolves ([gateActive]), keeps both moves on secret placeholders and
 * delays outcome banner for [DUAL_SELECTION_MIN_DISPLAY_MS] from that moment.
 *
 * Timing is keyed only by [roundKey] so a one-frame [gateActive] flicker does not skip the hold.
 */
@Composable
fun rememberDualSelectionRevealAllowed(
    roundKey: Int?,
    gateActive: Boolean,
): Boolean {
    var resolveHoldStartedAtMs by remember(roundKey) { mutableLongStateOf(0L) }
    var revealAllowed by remember(roundKey) { mutableStateOf(false) }
    var holdCompleted by remember(roundKey) { mutableStateOf(false) }

    SideEffect {
        if (gateActive && roundKey != null && !holdCompleted && resolveHoldStartedAtMs == 0L) {
            resolveHoldStartedAtMs = SystemClock.elapsedRealtime()
        }
    }

    LaunchedEffect(roundKey, gateActive) {
        if (roundKey == null || !gateActive || holdCompleted) return@LaunchedEffect

        if (resolveHoldStartedAtMs == 0L) {
            resolveHoldStartedAtMs = SystemClock.elapsedRealtime()
        }
        val nowMs = SystemClock.elapsedRealtime()
        val remaining = dualSelectionRevealHoldMs(resolveHoldStartedAtMs, nowMs)
        revealAllowed = false
        if (remaining > 0L) {
            delay(remaining)
        }
        revealAllowed = true
        holdCompleted = true
    }

    if (holdCompleted) return true
    if (!gateActive || roundKey == null) return true
    return revealAllowed
}

/**
 * Keeps revealed recap (icons + banner) for [ROUND_RECAP_REVEALED_MIN_DISPLAY_MS]
 * after dual reveal completes — not from initial round resolve.
 */
@Composable
fun rememberRoundRecapDismissed(
    roundKey: Int?,
    recapRevealStarted: Boolean,
): Boolean {
    var recapShownAtMs by remember(roundKey) { mutableLongStateOf(0L) }
    var dismissed by remember(roundKey) { mutableStateOf(false) }

    SideEffect {
        if (recapRevealStarted && recapShownAtMs == 0L) {
            recapShownAtMs = SystemClock.elapsedRealtime()
        }
    }

    LaunchedEffect(roundKey, recapRevealStarted) {
        if (roundKey == null || !recapRevealStarted) {
            if (!recapRevealStarted) {
                recapShownAtMs = 0L
                dismissed = false
            }
            return@LaunchedEffect
        }
        if (recapShownAtMs == 0L) {
            recapShownAtMs = SystemClock.elapsedRealtime()
        }
        val nowMs = SystemClock.elapsedRealtime()
        val remaining = recapRevealedRemainingMs(recapShownAtMs, nowMs)
        dismissed = false
        if (remaining > 0L) {
            delay(remaining)
        }
        dismissed = true
    }

    if (!recapRevealStarted) return false
    if (recapShownAtMs == 0L) return false
    return dismissed
}

fun holdMoveReveal(
    move: PanelMovePresentation,
    revealAllowed: Boolean,
): PanelMovePresentation =
    if (move.display == PanelMoveDisplay.Revealed && !revealAllowed) {
        PanelMovePresentation(move = move.move, display = PanelMoveDisplay.Secret)
    } else {
        move
    }

@Deprecated("Use holdMoveReveal", ReplaceWith("holdMoveReveal(move, revealAllowed)"))
fun holdOpponentMoveReveal(
    opponentMove: PanelMovePresentation,
    revealAllowed: Boolean,
): PanelMovePresentation = holdMoveReveal(opponentMove, revealAllowed)

@Deprecated("Use canRevealDualSelection", ReplaceWith("canRevealDualSelection(holdStartedAtMs, nowMs)"))
fun canRevealOpponentSelection(firstShownAtMs: Long, nowMs: Long): Boolean =
    canRevealDualSelection(firstShownAtMs, nowMs)

@Deprecated("Use dualSelectionElapsedMs", ReplaceWith("dualSelectionElapsedMs(holdStartedAtMs, nowMs)"))
fun opponentSelectionElapsedMs(firstShownAtMs: Long, nowMs: Long): Long =
    dualSelectionElapsedMs(firstShownAtMs, nowMs)

@Deprecated("Use dualSelectionRevealHoldMs", ReplaceWith("dualSelectionRevealHoldMs(holdStartedAtMs, nowMs)"))
fun opponentSelectionRevealHoldMs(firstShownAtMs: Long, nowMs: Long): Long =
    dualSelectionRevealHoldMs(firstShownAtMs, nowMs)
