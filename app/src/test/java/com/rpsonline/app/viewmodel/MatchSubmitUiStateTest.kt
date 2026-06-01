package com.rpsonline.app.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchSubmitUiStateTest {

    @Test
    fun computeHasSubmittedMove_clearsLocalPendingWhenRoundClosed() {
        assertFalse(
            computeHasSubmittedMove(
                alreadySubmitted = false,
                locallySubmittedRound = 10,
                openRoundNumber = null,
                matchActive = true,
            ),
        )
    }

    @Test
    fun computeHasSubmittedMove_keepsLocalPendingForOpenRound() {
        assertTrue(
            computeHasSubmittedMove(
                alreadySubmitted = false,
                locallySubmittedRound = 10,
                openRoundNumber = 10,
                matchActive = true,
            ),
        )
    }

    @Test
    fun shouldShowWaitingForOpponentMessage_falseAfterFinalRoundCloses() {
        assertFalse(
            shouldShowWaitingForOpponentMessage(
                hasSubmittedMove = true,
                opponentHasSubmitted = false,
                isSubmitting = false,
                isResolvingTimeout = false,
                hasOpenRound = false,
                hasPanelOutcome = false,
            ),
        )
    }

    @Test
    fun shouldShowWaitingForOpponentMessage_falseWhenOpponentSubmitted() {
        assertFalse(
            shouldShowWaitingForOpponentMessage(
                hasSubmittedMove = true,
                opponentHasSubmitted = true,
                isSubmitting = false,
                isResolvingTimeout = false,
                hasOpenRound = true,
                hasPanelOutcome = false,
            ),
        )
    }

    @Test
    fun shouldShowWaitingForOpponentMessage_trueWhileWaitingOnOpenRound() {
        assertTrue(
            shouldShowWaitingForOpponentMessage(
                hasSubmittedMove = true,
                opponentHasSubmitted = false,
                isSubmitting = false,
                isResolvingTimeout = false,
                hasOpenRound = true,
                hasPanelOutcome = false,
            ),
        )
    }
}
