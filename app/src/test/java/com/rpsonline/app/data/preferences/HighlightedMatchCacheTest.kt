package com.rpsonline.app.data.preferences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightedMatchCacheTest {
    private val nowMs = 1_700_086_400_000L

    @Test
    fun highlightedMatchCacheExpired_respectsOneDayTtl() {
        val cachedAtMs = nowMs
        assertFalse(highlightedMatchCacheExpired(cachedAtMs, nowMs))
        assertFalse(
            highlightedMatchCacheExpired(
                cachedAtMs,
                nowMs + HighlightedMatchCache.CACHE_TTL_MS - 1,
            ),
        )
        assertTrue(
            highlightedMatchCacheExpired(
                cachedAtMs,
                nowMs + HighlightedMatchCache.CACHE_TTL_MS,
            ),
        )
    }
}
