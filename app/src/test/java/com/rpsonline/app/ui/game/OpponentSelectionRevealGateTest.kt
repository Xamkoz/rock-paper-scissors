package com.rpsonline.app.ui.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpponentSelectionRevealGateTest {

    @Test
    fun canRevealOpponentSelection_requiresMinimumDisplayTime() {
        assertFalse(canRevealOpponentSelection(firstShownAtMs = 1_000L, nowMs = 1_200L))
        assertTrue(canRevealOpponentSelection(firstShownAtMs = 1_000L, nowMs = 1_500L))
        assertTrue(canRevealOpponentSelection(firstShownAtMs = 0L, nowMs = 9_999L))
    }

    @Test
    fun opponentSelectionRevealHoldMs_countsDownToZero() {
        assertEquals(500L, opponentSelectionRevealHoldMs(firstShownAtMs = 0L, nowMs = 0L))
        assertEquals(300L, opponentSelectionRevealHoldMs(firstShownAtMs = 1_000L, nowMs = 1_200L))
        assertEquals(0L, opponentSelectionRevealHoldMs(firstShownAtMs = 1_000L, nowMs = 1_600L))
    }

    @Test
    fun holdOpponentMoveReveal_keepsSecretUntilAllowed() {
        val revealed = PanelMovePresentation(display = PanelMoveDisplay.Revealed)
        val secret = holdOpponentMoveReveal(revealed, revealAllowed = false)
        assertEquals(PanelMoveDisplay.Secret, secret.display)

        val allowed = holdOpponentMoveReveal(revealed, revealAllowed = true)
        assertEquals(PanelMoveDisplay.Revealed, allowed.display)
    }

    @Test
    fun livePanelRecapHold_extendsMinimumDisplayAfterLivePanelEnds() {
        val shownAt = 1_000L
        val endedAt = 1_100L
        assertEquals(
            400L,
            (OPPONENT_SELECTION_MIN_DISPLAY_MS -
                opponentSelectionElapsedMs(shownAt, endedAt)).coerceAtLeast(0L),
        )
    }
}
