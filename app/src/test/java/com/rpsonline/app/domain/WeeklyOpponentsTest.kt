package com.rpsonline.app.domain

import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchHistoryEntry
import com.rpsonline.app.data.model.MatchStatus
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
        assertEquals(4.0, opponents[0].avgMyEloDeltaPerMatch(), 0.001)
        assertEquals("opp-b", opponents[1].opponentUid)
        assertEquals(7, opponents[1].weeklyEloDelta)
        assertEquals(1, opponents[1].matchCount)
        assertEquals(7.0, opponents[1].avgMyEloDeltaPerMatch(), 0.001)
    }

    @Test
    fun weeklyOpponentsFromMatchList_avgEloDeltaPerMatchUsesOneDecimal() {
        val viewerId = "me"
        val matches = listOf(
            match(
                id = "m1",
                player1 = viewerId,
                player2 = "opp-a",
                player1EloDelta = 10,
                player2EloDelta = -10,
                lastActivityAt = weekStartMs + 1_000,
            ),
            match(
                id = "m2",
                player1 = viewerId,
                player2 = "opp-a",
                player1EloDelta = 7,
                player2EloDelta = -7,
                lastActivityAt = weekStartMs + 2_000,
            ),
            match(
                id = "m3",
                player1 = viewerId,
                player2 = "opp-a",
                player1EloDelta = 8,
                player2EloDelta = -8,
                lastActivityAt = weekStartMs + 3_000,
            ),
        )

        val avg = weeklyOpponentsFromMatchList(matches, viewerId, weekStartMs).single().avgMyEloDeltaPerMatch()

        assertEquals(25.0 / 3.0, avg, 0.001)
    }

    @Test
    fun weeklyOpponentsFromMatchList_computesAvgEloDeltaPerMatch() {
        val viewerId = "me"
        val matches = listOf(
            match(
                id = "m1",
                player1 = viewerId,
                player2 = "opp-a",
                player1EloDelta = 10,
                player2EloDelta = -10,
                lastActivityAt = weekStartMs + 1_000,
            ),
        )

        val opponents = weeklyOpponentsFromMatchList(matches, viewerId, weekStartMs)

        assertEquals(1, opponents.size)
        assertEquals(10.0, opponents.single().avgMyEloDeltaPerMatch(), 0.001)
        assertEquals(1, opponents.single().matchCount)
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

    private fun match(
        id: String,
        player1: String,
        player2: String,
        player1EloDelta: Int?,
        player2EloDelta: Int?,
        lastActivityAt: Long,
    ): Match = Match(
        id = id,
        player1 = player1,
        player2 = player2,
        status = MatchStatus.COMPLETED,
        player1EloDelta = player1EloDelta,
        player2EloDelta = player2EloDelta,
        winnerId = player1,
        lastActivityAt = lastActivityAt,
    )
}
