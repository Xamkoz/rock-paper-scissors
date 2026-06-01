package com.rpsonline.app.data.repository

import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveMatchReplacePolicyTest {
    private val active = Match(
        id = "m1",
        player1 = "a",
        player2 = "b",
        status = MatchStatus.ACTIVE,
        lastActivityAt = 100L,
    )

    @Test
    fun cachedCompletedMatch_replacesActiveMatch() {
        val completed = active.copy(status = MatchStatus.COMPLETED, lastActivityAt = 101L)
        assertTrue(
            shouldReplaceActiveMatch(
                incoming = completed,
                current = active,
                fromCache = true,
            ),
        )
    }

    @Test
    fun cachedActiveMatch_doesNotReplaceLiveActiveMatch() {
        val cachedActive = active.copy(lastActivityAt = 99L)
        assertFalse(
            shouldReplaceActiveMatch(
                incoming = cachedActive,
                current = active,
                fromCache = true,
            ),
        )
    }

    @Test
    fun cachedLobby_doesNotDowngradeActiveMatch() {
        val lobby = active.copy(status = MatchStatus.LOBBY, lastActivityAt = 101L)
        assertFalse(
            shouldReplaceActiveMatch(
                incoming = lobby,
                current = active,
                fromCache = true,
            ),
        )
    }
}
