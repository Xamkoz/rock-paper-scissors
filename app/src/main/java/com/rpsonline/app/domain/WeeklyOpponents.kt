package com.rpsonline.app.domain

import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchHistoryEntry
import com.rpsonline.app.data.model.viewerResolution

data class WeeklyOpponentRow(
    val opponentUid: String,
    val displayName: String,
    val weeklyEloDelta: Int,
    val matchCount: Int,
    val lastPlayedAt: Long,
)

private data class OpponentWeekMatchSlice(
    val opponentUid: String,
    val displayName: String,
    val eloDelta: Int,
    val lastActivityAt: Long,
)

private fun buildWeeklyOpponentRows(
    slices: Sequence<OpponentWeekMatchSlice>,
): List<WeeklyOpponentRow> = slices
    .groupBy { it.opponentUid }
    .map { (uid, matches) ->
        val latest = matches.maxBy { it.lastActivityAt }
        WeeklyOpponentRow(
            opponentUid = uid,
            displayName = latest.displayName,
            weeklyEloDelta = matches.sumOf { it.eloDelta },
            matchCount = matches.size,
            lastPlayedAt = latest.lastActivityAt,
        )
    }
    .sortedWith(
        compareByDescending<WeeklyOpponentRow> { it.weeklyEloDelta }
            .thenByDescending { it.lastPlayedAt },
    )

fun weeklyOpponentsFromMatches(
    entries: List<MatchHistoryEntry>,
    sinceMs: Long,
): List<WeeklyOpponentRow> = buildWeeklyOpponentRows(
    entries
        .asSequence()
        .filter { it.lastActivityAt >= sinceMs && it.opponentUid.isNotBlank() }
        .map { entry ->
            OpponentWeekMatchSlice(
                opponentUid = entry.opponentUid,
                displayName = entry.opponentName,
                eloDelta = entry.eloDelta ?: 0,
                lastActivityAt = entry.lastActivityAt,
            )
        },
)

fun weeklyOpponentsFromMatchList(
    matches: List<Match>,
    viewerId: String,
    sinceMs: Long,
): List<WeeklyOpponentRow> = buildWeeklyOpponentRows(
    matches
        .asSequence()
        .filter {
            it.lastActivityAt >= sinceMs && it.viewerResolution(viewerId) != null
        }
        .mapNotNull { match ->
            val opponentUid = match.opponentId(viewerId).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            OpponentWeekMatchSlice(
                opponentUid = opponentUid,
                displayName = match.opponentName(viewerId),
                eloDelta = match.myEloDelta(viewerId) ?: 0,
                lastActivityAt = match.lastActivityAt,
            )
        },
)
