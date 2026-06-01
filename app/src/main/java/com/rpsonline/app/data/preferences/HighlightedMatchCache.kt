package com.rpsonline.app.data.preferences

import android.content.Context
import com.rpsonline.app.data.model.MatchHistoryEntry
import com.rpsonline.app.data.model.RoundEndReason
import com.rpsonline.app.data.model.RoundRecap
import com.rpsonline.app.data.model.ViewerMatchResolution
import com.rpsonline.app.domain.MatchMode
import org.json.JSONArray
import org.json.JSONObject

/** Cached highlight payload, or `null` when the cache is missing/expired. */
data class HighlightedMatchCacheHit(val entry: MatchHistoryEntry?)

/** Disk cache for home highlighted match (24h TTL, scoped to user + weekly window). */
class HighlightedMatchCache(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(
        userId: String,
        windowStartMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): HighlightedMatchCacheHit? {
        val raw = prefs.getString(KEY_PAYLOAD, null) ?: return null
        val payload = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (payload.optString(KEY_USER_ID) != userId) return null
        if (payload.optLong(KEY_WINDOW_START_MS) != windowStartMs) return null
        val cachedAtMs = payload.optLong(KEY_CACHED_AT_MS)
        if (highlightedMatchCacheExpired(cachedAtMs, nowMs)) return null
        if (!payload.has(KEY_ENTRY)) return null
        if (payload.isNull(KEY_ENTRY)) return HighlightedMatchCacheHit(entry = null)
        val entry = payload.optJSONObject(KEY_ENTRY)?.toMatchHistoryEntry() ?: return null
        return HighlightedMatchCacheHit(entry = entry)
    }

    /** `null` entry means “no qualifying highlight this week”. */
    fun write(
        userId: String,
        windowStartMs: Long,
        entry: MatchHistoryEntry?,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val payload = JSONObject()
            .put(KEY_USER_ID, userId)
            .put(KEY_WINDOW_START_MS, windowStartMs)
            .put(KEY_CACHED_AT_MS, nowMs)
            .put(KEY_ENTRY, entry?.toJson() ?: JSONObject.NULL)
        prefs.edit().putString(KEY_PAYLOAD, payload.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_PAYLOAD).apply()
    }

    companion object {
        const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L
        private const val PREFS_NAME = "highlighted_match_cache"
        private const val KEY_PAYLOAD = "payload"
        private const val KEY_USER_ID = "userId"
        private const val KEY_WINDOW_START_MS = "windowStartMs"
        private const val KEY_CACHED_AT_MS = "cachedAtMs"
        private const val KEY_ENTRY = "entry"
    }
}

internal fun highlightedMatchCacheExpired(cachedAtMs: Long, nowMs: Long): Boolean =
    cachedAtMs <= 0L || nowMs - cachedAtMs >= HighlightedMatchCache.CACHE_TTL_MS

internal fun MatchHistoryEntry.toJson(): JSONObject {
    val recapsArray = JSONArray()
    recaps.forEach { recap ->
        recapsArray.put(
            JSONObject()
                .put("roundNumber", recap.roundNumber)
                .put("myChoice", recap.myChoice)
                .put("opponentChoice", recap.opponentChoice)
                .put("myMoveMs", recap.myMoveMs)
                .put("opponentMoveMs", recap.opponentMoveMs)
                .put("won", recap.won)
                .put("isDraw", recap.isDraw)
                .put("isCancelled", recap.isCancelled)
                .put("endReason", recap.endReason?.name)
                .put("opponentTimedOut", recap.opponentTimedOut)
                .put("iTimedOut", recap.iTimedOut),
        )
    }
    return JSONObject()
        .put("matchId", matchId)
        .put("matchMode", matchMode.name)
        .put("myUid", myUid)
        .put("myDisplayName", myDisplayName)
        .put("opponentUid", opponentUid)
        .put("opponentName", opponentName)
        .put("myElo", myElo)
        .put("opponentElo", opponentElo)
        .put("myWins", myWins)
        .put("opponentWins", opponentWins)
        .put("resolution", resolution.name)
        .put("eloDelta", eloDelta)
        .put("opponentEloDelta", opponentEloDelta)
        .put("lastActivityAt", lastActivityAt)
        .put("recaps", recapsArray)
}

internal fun JSONObject.toMatchHistoryEntry(): MatchHistoryEntry {
    val recapsArray = optJSONArray("recaps") ?: JSONArray()
    val recaps = buildList {
        for (index in 0 until recapsArray.length()) {
            val recap = recapsArray.optJSONObject(index) ?: continue
            add(
                RoundRecap(
                    roundNumber = recap.optInt("roundNumber"),
                    myChoice = recap.optNullableString("myChoice"),
                    opponentChoice = recap.optNullableString("opponentChoice"),
                    myMoveMs = recap.optNullableInt("myMoveMs"),
                    opponentMoveMs = recap.optNullableInt("opponentMoveMs"),
                    won = recap.optBooleanOrNull("won"),
                    isDraw = recap.optBoolean("isDraw", false),
                    isCancelled = recap.optBoolean("isCancelled", false),
                    endReason = RoundEndReason.fromString(recap.optNullableString("endReason")),
                    opponentTimedOut = recap.optBoolean("opponentTimedOut", false),
                    iTimedOut = recap.optBoolean("iTimedOut", false),
                ),
            )
        }
    }
    return MatchHistoryEntry(
        matchId = getString("matchId"),
        matchMode = MatchMode.fromString(optNullableString("matchMode")),
        myUid = optString("myUid", ""),
        myDisplayName = optString("myDisplayName"),
        opponentUid = optString("opponentUid", ""),
        opponentName = optString("opponentName"),
        myElo = optNullableInt("myElo"),
        opponentElo = optNullableInt("opponentElo"),
        myWins = optInt("myWins"),
        opponentWins = optInt("opponentWins"),
        resolution = ViewerMatchResolution.valueOf(getString("resolution")),
        eloDelta = optNullableInt("eloDelta"),
        opponentEloDelta = optNullableInt("opponentEloDelta"),
        lastActivityAt = getLong("lastActivityAt"),
        recaps = recaps,
    )
}

private fun JSONObject.optNullableString(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

private fun JSONObject.optNullableInt(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
    if (has(key) && !isNull(key)) optBoolean(key) else null
