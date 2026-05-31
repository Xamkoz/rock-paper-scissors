package com.rpsonline.app.domain

import com.rpsonline.app.data.model.MatchHistoryEntry
import com.rpsonline.app.data.model.ViewerMatchResolution
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class WeeklyOpponentsTest {
    private val zoneId = ZoneId.of("UTC")
    private val weekStartMs = LocalDate.of(2026, 5, 21)
        .atStartOfDay(zoneId)
        .toInstant()
        .toEpochMilli()

    @Test
    fun weeklyOpponentsFromMatches_groupsByOpponentAndSumsEloDelta() {
        val entries = listOf(
            entry(
                opponentUid = "opp-a",
                opponentName = "Alpha",
                delta = 12,
                lastActivityAt = weekStartMs + 1_000,
            ),
            entry(
                opponentUid = "opp-a",
                opponentName = "Alpha",
                delta = -4,
                lastActivityAt = weekStartMs + 2_000,
            ),
            entry(
                opponentUid = "opp-b",
                opponentName = "Beta",
                delta = 7,
                lastActivityAt = weekStartMs + 3_000,
            ),
            entry(
                opponentUid = "opp-old",
                opponentName = "Old",
                delta = 99,
                lastActivityAt = weekStartMs - 1,
            ),
        )

        val opponents = weeklyOpponentsFromMatches(entries, weekStartMs)

        assertEquals(2, opponents.size)
        assertEquals("opp-a", opponents[0].opponentUid)
        assertEquals(8, opponents[0].weeklyEloDelta)
        assertEquals(2, opponents[0].matchCount)
        assertEquals("opp-b", opponents[1].opponentUid)
        assertEquals(7, opponents[1].weeklyEloDelta)
    }

    private fun entry(
        opponentUid: String,
        opponentName: String,
        delta: Int,
        lastActivityAt: Long,
    ): MatchHistoryEntry = MatchHistoryEntry(
        matchId = "m-$lastActivityAt",
        myUid = "me",
        myDisplayName = "Me",
        opponentUid = opponentUid,
        opponentName = opponentName,
        myWins = 1,
        opponentWins = 0,
        resolution = ViewerMatchResolution.WIN,
        eloDelta = delta,
        lastActivityAt = lastActivityAt,
        recaps = emptyList(),
    )
}
