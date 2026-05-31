package com.rpsonline.app.domain

import com.rpsonline.app.data.model.MatchHistoryEntry

data class WeeklyOpponentRow(
    val opponentUid: String,
    val displayName: String,
    val weeklyEloDelta: Int,
    val matchCount: Int,
    val lastPlayedAt: Long,
)

fun weeklyOpponentsFromMatches(
    entries: List<MatchHistoryEntry>,
    sinceMs: Long,
): List<WeeklyOpponentRow> = entries
    .asSequence()
    .filter { it.lastActivityAt >= sinceMs && it.opponentUid.isNotBlank() }
    .groupBy { it.opponentUid }
    .map { (uid, matches) ->
        val latest = matches.maxBy { it.lastActivityAt }
        WeeklyOpponentRow(
            opponentUid = uid,
            displayName = latest.opponentName,
            weeklyEloDelta = matches.sumOf { it.eloDelta ?: 0 },
            matchCount = matches.size,
            lastPlayedAt = latest.lastActivityAt,
        )
    }
    .sortedWith(
        compareByDescending<WeeklyOpponentRow> { it.weeklyEloDelta }
            .thenByDescending { it.lastPlayedAt },
    )
