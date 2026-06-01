package com.rpsonline.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcludedMatchCacheTest {
    @Test
    fun keysTouchingParticipants_includeRecentAndSharedQueries() {
        val keys = setOf(
            "recent_user:player-a:10",
            "recent_user_since:player-a:1000:200",
            "shared_between:player-a:player-b:10",
            "shared_between_since:player-b:player-a:1000:50",
            "recent_user:other:10",
        )
        val matched = MatchRepository.concludedCacheKeysTouchingParticipants(
            keys,
            "player-a",
            "player-b",
        )
        assertEquals(4, matched.size)
        assertTrue("recent_user:other:10" !in matched)
    }
}
