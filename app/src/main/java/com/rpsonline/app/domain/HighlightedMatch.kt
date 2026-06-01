package com.rpsonline.app.domain

import com.rpsonline.app.data.model.MatchHistoryEntry
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Best positive ELO gain in the rolling 7-day window ending today; newest match wins ties. */
fun biggestEloGainMatchOfWeek(
    matches: List<MatchHistoryEntry>,
    zoneId: ZoneId = ZoneId.systemDefault(),
    clock: Clock = Clock.system(zoneId),
): MatchHistoryEntry? {
    val today = LocalDate.now(clock.withZone(zoneId))
    val startDay = today.minusDays(6)
    return matches
        .asSequence()
        .filter { match ->
            val delta = match.eloDelta ?: return@filter false
            if (delta <= 0) return@filter false
            val activityAt = match.lastActivityAt
            if (activityAt <= 0L) return@filter false
            val matchDay = Instant.ofEpochMilli(activityAt).atZone(zoneId).toLocalDate()
            !matchDay.isBefore(startDay) && !matchDay.isAfter(today)
        }
        .maxWithOrNull(
            compareBy<MatchHistoryEntry> { it.eloDelta ?: 0 }
                .thenBy { it.lastActivityAt },
        )
}
