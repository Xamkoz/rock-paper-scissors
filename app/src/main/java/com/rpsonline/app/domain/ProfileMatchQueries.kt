package com.rpsonline.app.domain

import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.repository.MatchRepository

/** Shared Firestore match queries for profile weekly charts and opponents list. */
object ProfileMatchQueries {
    const val MATCH_POOL_SIZE = 200

    suspend fun fetchMatchPool(
        matchRepository: MatchRepository,
        isOwnProfile: Boolean,
        viewerId: String,
        profileUserId: String,
        limit: Int = MATCH_POOL_SIZE,
    ): List<Match> = if (isOwnProfile) {
        matchRepository.getRecentMatchesForUser(
            userId = viewerId,
            limit = limit,
        )
    } else {
        matchRepository.getSharedMatchesBetween(
            userId = viewerId,
            opponentId = profileUserId,
            limit = limit,
        )
    }

    suspend fun fetchOwnWeeklyMatchPool(
        matchRepository: MatchRepository,
        viewerId: String,
        sinceMs: Long = weeklyChartWindowStartMs(),
        limit: Int = MATCH_POOL_SIZE,
    ): List<Match> = matchRepository.getRecentMatchesForUserSince(
        userId = viewerId,
        sinceMs = sinceMs,
        limit = limit,
    )

    suspend fun fetchSharedWeeklyMatchPool(
        matchRepository: MatchRepository,
        viewerId: String,
        profileUserId: String,
        sinceMs: Long = weeklyChartWindowStartMs(),
        limit: Int = MATCH_POOL_SIZE,
    ): List<Match> = matchRepository.getSharedMatchesBetweenSince(
        userId = viewerId,
        opponentId = profileUserId,
        sinceMs = sinceMs,
        limit = limit,
    )
}
