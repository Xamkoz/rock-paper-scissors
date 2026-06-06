package com.rpsonline.app.ui.util

import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus
import com.rpsonline.app.domain.MatchMode
import com.rpsonline.app.data.model.RoundResult
import com.rpsonline.app.domain.GameRules
import org.junit.Assert.assertEquals
import org.junit.Test

class MatchClockSoundPolicyTest {

    @Test
    fun myClockMsRemainingLive_ticksDownWhileRunning() {
        val match = runningClockMatch(
            player1ClockMs = 12_000L,
            clocksUpdatedAt = 1_000L,
        )
        assertEquals(7_000L, match.myClockMsRemainingLive("p1", nowMs = 6_000L))
    }

    @Test
    fun myClockMsRemainingLive_returnsStoredWhenFrozen() {
        val match = runningClockMatch(
            player1ClockMs = 12_000L,
            clocksUpdatedAt = 1_000L,
        ).copy(
            rounds = listOf(
                RoundResult(
                    roundNumber = 1,
                    player1Submitted = true,
                    player2Submitted = false,
                ),
            ),
        )
        assertEquals(12_000L, match.myClockMsRemainingLive("p1", nowMs = 6_000L))
    }

    @Test
    fun tickAfterClockRunningMs_constant() {
        assertEquals(3_000L, MatchClockSoundPolicy.TICK_AFTER_CLOCK_RUNNING_MS)
    }

    @Test
    fun hapticAfterClockRunningMs_constant() {
        assertEquals(7_000L, MatchClockSoundPolicy.HAPTIC_AFTER_CLOCK_RUNNING_MS)
    }

    private fun runningClockMatch(
        player1ClockMs: Long,
        clocksUpdatedAt: Long,
    ): Match = Match(
        id = "m1",
        player1 = "p1",
        player2 = "p2",
        status = MatchStatus.ACTIVE,
        player1ClockMs = player1ClockMs,
        player2ClockMs = GameRules.INITIAL_CLOCK_MS,
        clocksUpdatedAt = clocksUpdatedAt,
        rounds = listOf(
            RoundResult(
                roundNumber = 1,
                player1Submitted = false,
                player2Submitted = false,
            ),
        ),
        matchMode = MatchMode.BO3,
    )
}
