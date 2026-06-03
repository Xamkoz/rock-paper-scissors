package com.rpsonline.app.ui.game

import com.rpsonline.app.data.model.RoundResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpponentSelectionRevealGateTest {

    private fun dummyRound(): RoundResult = RoundResult(
        roundNumber = 1,
        player1Choice = "rock",
        player2Choice = "paper",
        winner = "p1",
        resolvedAt = 1L,
    )

    @Test
    fun scoresBeforeRecapRound_rollsBackWinnerOnly() {
        val round = dummyRound().copy(winner = "p1")
        assertEquals(1 to 0, scoresBeforeRecapRound(1, 1, round, userId = "p2"))
        assertEquals(0 to 1, scoresBeforeRecapRound(1, 1, round, userId = "p1"))
        val tie = dummyRound().copy(winner = "tie")
        assertEquals(2 to 2, scoresBeforeRecapRound(2, 2, tie, userId = "p1"))
        assertEquals(3 to 1, scoresBeforeRecapRound(3, 1, null, userId = "p1"))
    }

    @Test
    fun canRevealDualSelection_requiresMinimumDisplayTime() {
        assertFalse(canRevealDualSelection(holdStartedAtMs = 1_000L, nowMs = 1_500L))
        assertTrue(canRevealDualSelection(holdStartedAtMs = 1_000L, nowMs = 2_000L))
        assertTrue(canRevealDualSelection(holdStartedAtMs = 0L, nowMs = 9_999L))
    }

    @Test
    fun dualSelectionRevealHoldMs_countsDownToZero() {
        assertEquals(1_000L, dualSelectionRevealHoldMs(holdStartedAtMs = 0L, nowMs = 0L))
        assertEquals(500L, dualSelectionRevealHoldMs(holdStartedAtMs = 1_000L, nowMs = 1_500L))
        assertEquals(0L, dualSelectionRevealHoldMs(holdStartedAtMs = 1_000L, nowMs = 2_000L))
    }

    @Test
    fun shouldDelayDualSelectionReveal_trueUntilMinimumMet() {
        assertTrue(
            shouldDelayDualSelectionReveal(
                holdStartedAtMs = 0L,
                nowMs = 5_000L,
                holdForResolvedRound = true,
            ),
        )
        assertTrue(
            shouldDelayDualSelectionReveal(
                holdStartedAtMs = 1_000L,
                nowMs = 1_500L,
                holdForResolvedRound = true,
            ),
        )
        assertFalse(
            shouldDelayDualSelectionReveal(
                holdStartedAtMs = 1_000L,
                nowMs = 2_000L,
                holdForResolvedRound = true,
            ),
        )
        assertFalse(
            shouldDelayDualSelectionReveal(
                holdStartedAtMs = 1_000L,
                nowMs = 500L,
                holdForResolvedRound = false,
            ),
        )
    }

    @Test
    fun shouldAllowRoundMovePicker_falseDuringRecap() {
        assertFalse(
            shouldAllowRoundMovePicker(
                showRecapRoundMoves = true,
                holdForResolvedRound = true,
                recapDismissed = false,
                showOutcomeReveal = false,
                showDrawReveal = false,
            ),
        )
        assertTrue(
            shouldAllowRoundMovePicker(
                showRecapRoundMoves = false,
                holdForResolvedRound = true,
                recapDismissed = true,
                showOutcomeReveal = false,
                showDrawReveal = false,
            ),
        )
    }

    @Test
    fun resolveRecapMovePresentations_alwaysRevealed() {
        val revealed = resolveRecapMovePresentations("rock", "paper")
        assertEquals(PanelMoveDisplay.Revealed, revealed.first.display)
        assertEquals(PanelMoveDisplay.Revealed, revealed.second.display)
    }

    @Test
    fun preferResolvedRoundPanel_trueDuringRecapDualHold() {
        assertTrue(
            preferResolvedRoundPanel(
                showOutcomeReveal = false,
                showDrawReveal = false,
                showPreviousRoundRecap = false,
                showPendingRoundOutcome = false,
                hasDrawReplay = false,
                roundRecapComplete = false,
                inRecapDualHold = true,
            ),
        )
    }

    @Test
    fun holdMoveReveal_keepsBothSecretUntilAllowed() {
        val revealed = PanelMovePresentation(display = PanelMoveDisplay.Revealed)
        val secret = holdMoveReveal(revealed, revealAllowed = false)
        assertEquals(PanelMoveDisplay.Secret, secret.display)

        val allowed = holdMoveReveal(revealed, revealAllowed = true)
        assertEquals(PanelMoveDisplay.Revealed, allowed.display)
    }

    @Test
    fun shouldShowPendingRoundOutcome_whenAwaitingNextRound() {
        assertTrue(shouldShowPendingRoundOutcome(awaitingNextRound = true, pendingOutcome = dummyRound()))
        assertFalse(shouldShowPendingRoundOutcome(awaitingNextRound = false, pendingOutcome = dummyRound()))
        assertFalse(shouldShowPendingRoundOutcome(awaitingNextRound = true, pendingOutcome = null))
    }

    @Test
    fun shouldHoldForResolvedRound_includesAwaitingNextRound() {
        val round = dummyRound()
        assertTrue(
            shouldHoldForResolvedRound(
                resolvedRound = round,
                player1Choice = "rock",
                player2Choice = "paper",
                showOutcomeReveal = false,
                showDrawReveal = false,
                awaitingNextRound = true,
                betweenRoundsRecapRound = null,
            ),
        )
        assertFalse(
            shouldHoldForResolvedRound(
                resolvedRound = null,
                player1Choice = "rock",
                player2Choice = "paper",
                showOutcomeReveal = false,
                showDrawReveal = false,
                awaitingNextRound = true,
                betweenRoundsRecapRound = null,
            ),
        )
    }

    @Test
    fun resolveBetweenRoundsRecapRound_coversGapBeforeOpenRound() {
        val last = dummyRound()
        assertEquals(
            last,
            resolveBetweenRoundsRecapRound(
                pendingOutcome = null,
                lastResolved = last,
                openRound = null,
            ),
        )
        assertEquals(
            last,
            resolveBetweenRoundsRecapRound(
                pendingOutcome = last,
                lastResolved = last,
                openRound = RoundResult(roundNumber = 2),
            ),
        )
    }

    @Test
    fun resolveBetweenRoundsRecapRound_nullWhileOpenRoundStillResolving() {
        val last = dummyRound()
        val resolvingOpen = RoundResult(
            roundNumber = 2,
            player1Submitted = true,
            player2Submitted = true,
        )
        assertEquals(
            null,
            resolveBetweenRoundsRecapRound(
                pendingOutcome = null,
                lastResolved = last,
                openRound = resolvingOpen,
            ),
        )
    }

    @Test
    fun isOpenRoundResolving_followsServerSubmissions() {
        val resolving = RoundResult(roundNumber = 2, player1Submitted = true, player2Submitted = true)
        assertTrue(isOpenRoundResolving(resolving))
        assertFalse(isOpenRoundResolving(RoundResult(roundNumber = 2)))
    }

    @Test
    fun shouldSuppressBetweenRoundsRecap_duringLocalBlindWait() {
        assertTrue(
            shouldSuppressBetweenRoundsRecap(
                openRound = RoundResult(roundNumber = 2),
                localHasSubmitted = true,
                localOpponentSubmitted = true,
                serverRoundSettled = false,
            ),
        )
        assertFalse(
            shouldSuppressBetweenRoundsRecap(
                openRound = RoundResult(roundNumber = 2),
                localHasSubmitted = true,
                localOpponentSubmitted = true,
                serverRoundSettled = true,
            ),
        )
    }

    @Test
    fun isServerRoundSettled_whenOutcomeKnown() {
        assertTrue(
            isServerRoundSettled(
                showOutcomeReveal = true,
                showDrawReveal = false,
                awaitingNextRound = false,
                hasPendingOutcome = false,
            ),
        )
    }

    @Test
    fun shouldHoldForResolvedRound_whenRecapRoundKnownBeforeAwaitingNextRound() {
        val round = dummyRound()
        assertTrue(
            shouldHoldForResolvedRound(
                resolvedRound = round,
                player1Choice = "rock",
                player2Choice = "paper",
                showOutcomeReveal = false,
                showDrawReveal = false,
                awaitingNextRound = false,
                betweenRoundsRecapRound = round,
            ),
        )
    }

    @Test
    fun shouldShowRecapRoundMoves_untilTimedDismiss() {
        val round = dummyRound()
        assertTrue(
            shouldShowRecapRoundMoves(
                recapRound = round,
                recapPhaseActive = true,
                recapDismissed = false,
            ),
        )
        assertFalse(
            shouldShowRecapRoundMoves(
                recapRound = round,
                recapPhaseActive = true,
                recapDismissed = true,
            ),
        )
        assertTrue(
            shouldShowRecapRoundMoves(
                recapRound = round,
                recapPhaseActive = false,
                recapDismissed = false,
                serverRoundSettled = true,
            ),
        )
        assertFalse(
            shouldShowRecapRoundMoves(
                recapRound = round,
                recapPhaseActive = false,
                recapDismissed = false,
                serverRoundSettled = false,
            ),
        )
    }

    @Test
    fun shouldActivateRoundRecapPhase_whenServerSettledDespiteOpenResolve() {
        assertTrue(
            shouldActivateRoundRecapPhase(
                shouldHoldForResolvedRound = true,
                openRoundResolving = true,
                serverRoundSettled = true,
            ),
        )
        assertFalse(
            shouldActivateRoundRecapPhase(
                shouldHoldForResolvedRound = true,
                openRoundResolving = true,
                serverRoundSettled = false,
            ),
        )
    }

    @Test
    fun shouldBlockLiveResolvedMoveReveal_beforeRecapSequence() {
        val round = dummyRound()
        assertTrue(
            shouldBlockLiveResolvedMoveReveal(
                serverRoundSettled = true,
                recapRound = round,
                recapPhaseActive = false,
                openRoundResolving = false,
            ),
        )
    }

    @Test
    fun recapRevealedRemainingMs_countsDown() {
        assertEquals(1_000L, recapRevealedRemainingMs(revealedAtMs = 0L, nowMs = 0L))
        assertEquals(400L, recapRevealedRemainingMs(revealedAtMs = 1_000L, nowMs = 1_600L))
        assertEquals(0L, recapRevealedRemainingMs(revealedAtMs = 1_000L, nowMs = 2_000L))
    }

    @Test
    fun preferResolvedRoundPanel_falseAfterRecapDismissedWhenOnlyPending() {
        assertFalse(
            preferResolvedRoundPanel(
                showOutcomeReveal = false,
                showDrawReveal = false,
                showPreviousRoundRecap = false,
                showPendingRoundOutcome = true,
                hasDrawReplay = false,
                roundRecapComplete = true,
                recapDismissed = true,
            ),
        )
    }

    @Test
    fun resolveRecapRound_usesOpenRoundWithWinner() {
        val open = RoundResult(
            roundNumber = 3,
            player1Choice = "rock",
            player2Choice = "scissors",
            winner = "p1",
        )
        assertEquals(open, resolveRecapRound(null, null, null, open))
    }

    @Test
    fun preferResolvedRoundPanel_pendingOutcomeOnlyDuringRecap() {
        assertTrue(
            preferResolvedRoundPanel(
                showOutcomeReveal = false,
                showDrawReveal = false,
                showPreviousRoundRecap = false,
                showPendingRoundOutcome = true,
                hasDrawReplay = false,
                roundRecapComplete = false,
            ),
        )
        assertFalse(
            preferResolvedRoundPanel(
                showOutcomeReveal = false,
                showDrawReveal = false,
                showPreviousRoundRecap = true,
                showPendingRoundOutcome = true,
                hasDrawReplay = false,
                roundRecapComplete = true,
            ),
        )
    }

    @Test
    fun dualSelectionHold_fullSecondFromResolveTime() {
        val resolveAt = 5_000L
        assertEquals(
            1_000L,
            dualSelectionRevealHoldMs(holdStartedAtMs = resolveAt, nowMs = resolveAt),
        )
        assertEquals(
            400L,
            dualSelectionRevealHoldMs(holdStartedAtMs = resolveAt, nowMs = resolveAt + 600L),
        )
    }
}
