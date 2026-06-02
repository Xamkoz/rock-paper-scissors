package com.rpsonline.app.data.repository

import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchSessionEndPolicyTest {

    @Test
    fun clearsWhenNotMatchmakingAndNoPendingNavigation() {
        assertTrue(
            shouldClearActiveMatchOnUserDocClear(
                finalizedMatchId = "m1",
                currentMatch = null,
                matchmakingInProgress = false,
                hasPendingGameNavigation = false,
            ),
        )
    }

    @Test
    fun clearsTerminalMatchAlreadyInMemory() {
        val completed = Match(
            id = "m1",
            player1 = "a",
            player2 = "b",
            status = MatchStatus.COMPLETED,
            createdAt = 0L,
        )
        assertTrue(
            shouldClearActiveMatchOnUserDocClear(
                finalizedMatchId = "m1",
                currentMatch = completed,
                matchmakingInProgress = true,
                hasPendingGameNavigation = false,
            ),
        )
    }

    @Test
    fun keepsLobbyMatchDuringMatchmaking() {
        val lobby = Match(
            id = "m1",
            player1 = "a",
            player2 = "b",
            status = MatchStatus.LOBBY,
            createdAt = 0L,
        )
        assertFalse(
            shouldClearActiveMatchOnUserDocClear(
                finalizedMatchId = "m1",
                currentMatch = lobby,
                matchmakingInProgress = true,
                hasPendingGameNavigation = false,
            ),
        )
    }
}
