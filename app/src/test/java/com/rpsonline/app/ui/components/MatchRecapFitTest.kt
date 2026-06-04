package com.rpsonline.app.ui.components

import com.rpsonline.app.data.model.RoundRecap
import org.junit.Assert.assertEquals
import org.junit.Test
import androidx.compose.ui.unit.dp

private fun recap(roundNumber: Int) = RoundRecap(
    roundNumber = roundNumber,
    myChoice = "rock",
    opponentChoice = "paper",
    won = false,
)

class MatchRecapFitTest {
    @Test
    fun maxRecapRowsThatFit_returnsZeroWhenNoSpace() {
        assertEquals(0, maxRecapRowsThatFit(0.dp, compact = false))
        assertEquals(0, maxRecapRowsThatFit(3.dp, compact = true))
    }

    @Test
    fun recapStackHeight_accountsForDividersBetweenRows() {
        assertEquals(24.dp, recapStackHeight(1, compact = true))
        assertEquals(53.dp, recapStackHeight(2, compact = true))
    }

    @Test
    fun maxRecapRowsThatFit_packsRowsTopToBottom() {
        assertEquals(5, maxRecapRowsThatFit(168.dp, compact = false))
        assertEquals(5, maxRecapRowsThatFit(140.dp, compact = true))
    }

    @Test
    fun visibleMatchRoundRecaps_newestFirstAndKeepsRecentWindow() {
        val recaps = listOf(recap(11), recap(7), recap(9), recap(8), recap(10))
        assertEquals(
            listOf(11, 10, 9, 8, 7),
            visibleMatchRoundRecaps(recaps, maxRows = 5).map { it.roundNumber },
        )
        assertEquals(
            listOf(11, 10),
            visibleMatchRoundRecaps(recaps, maxRows = 2).map { it.roundNumber },
        )
    }
}
