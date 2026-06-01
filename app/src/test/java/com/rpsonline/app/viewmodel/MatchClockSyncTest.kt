package com.rpsonline.app.viewmodel

import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus
import com.rpsonline.app.data.model.RoundResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MatchClockSyncTest {

    @Test
    fun timeoutResolutionFingerprint_ignoresClockFieldChanges() {
        val base = match(player1ClockMs = 50_000L, player2ClockMs = 40_000L, clocksUpdatedAt = 100L)
        val ticked = match(player1ClockMs = 49_000L, player2ClockMs = 39_000L, clocksUpdatedAt = 2_000L)
        assertEquals(timeoutResolutionFingerprint(base), timeoutResolutionFingerprint(ticked))
    }

    @Test
    fun timeoutResolutionFingerprint_changesWhenRoundResolves() {
        val open = match(
            rounds = listOf(
                RoundResult(
                    roundNumber = 1,
                    player1Submitted = true,
                    player2Submitted = true,
                ),
            ),
        )
        val resolved = match(
            rounds = listOf(
                RoundResult(
                    roundNumber = 1,
                    player1Submitted = true,
                    player2Submitted = true,
                    resolvedAt = 5_000L,
                    winner = "p1",
                ),
            ),
        )
        assertFalse(timeoutResolutionFingerprint(open) == timeoutResolutionFingerprint(resolved))
    }

    @Test
    fun reconcileClockBaseMs_pinsZeroWhenServerStillPositive() {
        assertEquals(0L to false, reconcileClockBaseMs(running = true, displayedSeconds = 0, serverMs = 800L))
    }

    @Test
    fun reconcileClockBaseMs_acceptsServerWhenNotYetAtZero() {
        assertEquals(12_000L to true, reconcileClockBaseMs(running = true, displayedSeconds = 12, serverMs = 12_000L))
    }

    @Test
    fun reconcileClockBaseMs_usesServerWhenStopped() {
        assertEquals(0L to false, reconcileClockBaseMs(running = false, displayedSeconds = 0, serverMs = 0L))
    }

    private fun match(
        player1ClockMs: Long = 50_000L,
        player2ClockMs: Long = 50_000L,
        clocksUpdatedAt: Long = 0L,
        rounds: List<RoundResult> = listOf(RoundResult(roundNumber = 1)),
    ): Match = Match(
        id = "m1",
        status = MatchStatus.ACTIVE,
        player1 = "p1",
        player2 = "p2",
        player1ClockMs = player1ClockMs,
        player2ClockMs = player2ClockMs,
        clocksUpdatedAt = clocksUpdatedAt,
        rounds = rounds,
    )
}
