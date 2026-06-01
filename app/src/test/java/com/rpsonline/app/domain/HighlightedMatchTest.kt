package com.rpsonline.app.domain

import com.rpsonline.app.data.model.MatchHistoryEntry
import com.rpsonline.app.data.model.ViewerMatchResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class HighlightedMatchTest {
    private val zoneId = ZoneId.of("UTC")
    private val fixedClock = Clock.fixed(Instant.parse("2026-05-27T18:00:00Z"), zoneId)
    private val today = LocalDate.of(2026, 5, 27)

    @Test
    fun biggestEloGainMatchOfWeek_picksHighestPositiveGainInWindow() {
        val noon = today.atTime(12, 0).atZone(zoneId).toInstant().toEpochMilli()
        val matches = listOf(
            entry("a", noon, 12),
            entry("b", noon + 1_000, 25),
            entry("c", noon + 2_000, 18),
        )

        val best = biggestEloGainMatchOfWeek(matches, zoneId = zoneId, clock = fixedClock)

        assertEquals("b", best?.matchId)
    }

    @Test
    fun biggestEloGainMatchOfWeek_prefersNewerMatchOnTie() {
        val noon = today.atTime(12, 0).atZone(zoneId).toInstant().toEpochMilli()
        val matches = listOf(
            entry("older", noon, 20),
            entry("newer", noon + 3_600_000, 20),
        )

        val best = biggestEloGainMatchOfWeek(matches, zoneId = zoneId, clock = fixedClock)

        assertEquals("newer", best?.matchId)
    }

    @Test
    fun biggestEloGainMatchOfWeek_ignoresLossesAndMatchesOutsideWindow() {
        val noon = today.atTime(12, 0).atZone(zoneId).toInstant().toEpochMilli()
        val outsideWindow = today.minusDays(8).atTime(12, 0).atZone(zoneId).toInstant().toEpochMilli()
        val matches = listOf(
            entry("loss", noon, -30),
            entry("outside", outsideWindow, 40),
            entry("zero", noon + 1_000, 0),
        )

        assertNull(biggestEloGainMatchOfWeek(matches, zoneId = zoneId, clock = fixedClock))
    }

    @Test
    fun biggestEloGainMatchOfWeek_ignoresSingleRoundMatches() {
        val noon = today.atTime(12, 0).atZone(zoneId).toInstant().toEpochMilli()
        val matches = listOf(
            entry("one-round", noon + 2_000, 40, recapCount = 1),
            entry("two-rounds", noon + 1_000, 12, recapCount = 2),
        )

        val best = biggestEloGainMatchOfWeek(matches, zoneId = zoneId, clock = fixedClock)

        assertEquals("two-rounds", best?.matchId)
    }

    private fun entry(
        matchId: String,
        lastActivityAt: Long,
        eloDelta: Int,
        recapCount: Int = 2,
    ): MatchHistoryEntry =
        MatchHistoryEntry(
            matchId = matchId,
            myDisplayName = "Me",
            opponentName = "Them",
            myWins = 1,
            opponentWins = 0,
            resolution = ViewerMatchResolution.WIN,
            eloDelta = eloDelta,
            lastActivityAt = lastActivityAt,
            recaps = List(recapCount) { index ->
                com.rpsonline.app.data.model.RoundRecap(
                    roundNumber = index + 1,
                    myChoice = "ROCK",
                    opponentChoice = "SCISSORS",
                    won = true,
                )
            },
        )
}
