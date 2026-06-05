package com.rpsonline.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MatchReadyDeadlineTest {

    @Test
    fun effectiveDeadlineUsesFullLobbyWindowWhenServerSentLegacy15s() {
        val createdAt = 1_000_000L
        val match = Match(
            createdAt = createdAt,
            readyDeadlineAt = createdAt + 15_000L,
        )
        assertEquals(createdAt + Match.LOBBY_READY_MS, match.effectiveReadyDeadlineAtMs(createdAt))
    }

    @Test
    fun readySecondsRemainingCeilsPartialSeconds() {
        val createdAt = 1_000_000L
        val match = Match(
            createdAt = createdAt,
            readyDeadlineAt = createdAt + Match.LOBBY_READY_MS,
        )
        assertEquals(
            20,
            match.readySecondsRemaining(createdAt + 1L),
        )
        assertEquals(
            19,
            match.readySecondsRemaining(createdAt + 1_500L),
        )
    }
}
