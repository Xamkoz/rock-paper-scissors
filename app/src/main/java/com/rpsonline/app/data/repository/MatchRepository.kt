package com.rpsonline.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.rpsonline.app.BuildConfig
import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchEndReason
import com.rpsonline.app.data.model.MatchResolution
import com.rpsonline.app.data.model.MatchStatus
import com.rpsonline.app.data.model.RoundEndReason
import com.rpsonline.app.data.model.Move
import com.rpsonline.app.domain.DisplayNames
import com.rpsonline.app.domain.GameRules
import com.rpsonline.app.domain.MatchMode
import com.rpsonline.app.data.model.RoundResult
import com.rpsonline.app.data.model.UserProfile
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

class MatchRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = appFirestore(),
) {
    companion object {
        /** Default per-side cap for time-bounded match queries (see [getRecentMatchesForUserSince]). */
        const val DEFAULT_SINCE_MATCH_LIMIT = 200

        /**
         * Time-to-live for concluded match cache entries.
         *
         * The cache is in-memory and only stores matches that are already concluded
         * (COMPLETED or ABANDONED). Entries older than this will be refreshed from Firestore.
         */
        private const val CONCLUDED_MATCH_CACHE_TTL_MS = 2 * 60 * 1000L

        private const val PREFS_NAME = "concluded_match_cache"
        private const val PREF_KEY_VERSION_CODE = "version_code"
        private const val QUEUE_WRITE_TIMEOUT_MS = 12_000L
        private const val ENABLE_NETWORK_TIMEOUT_MS = 2_000L
        /** Align with [functions/src/queue.ts] [QUEUE_STALE_MS] and server matchmaking. */
        private const val ACTIVE_MATCH_RECONNECT_MAX_AGE_MS = 90_000L
        private const val QUEUE_READ_TIMEOUT_MS = 8_000L
        private const val USER_PROFILE_POLL_MS = 200L

        /**
         * Simple in-memory cache for concluded match queries.
         *
         * Keys reflect the query type and parameters (user ids, since timestamp, limit).
         * The cache is also scoped to the current app version; if the version changes
         * (i.e., the app is updated), all cached entries are discarded.
         */
        private val concludedMatchCache =
            mutableMapOf<String, ConcludedMatchCacheEntry>()

        private var cacheVersionCode: Int? = null

        private val prefs: SharedPreferences
            get() = FirebaseApp.getInstance().applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        private fun cacheKeyRecentForUser(userId: String, limit: Int): String =
            "recent_user:$userId:$limit"

        private fun cacheKeyRecentForUserSince(
            userId: String,
            sinceMs: Long,
            limit: Int,
        ): String = "recent_user_since:$userId:$sinceMs:$limit"

        private fun cacheKeySharedBetween(
            userId: String,
            opponentId: String,
            limit: Int,
        ): String = "shared_between:$userId:$opponentId:$limit"

        private fun cacheKeySharedBetweenSince(
            userId: String,
            opponentId: String,
            sinceMs: Long,
            limit: Int,
        ): String = "shared_between_since:$userId:$opponentId:$sinceMs:$limit"

        private fun nowMs(): Long = System.currentTimeMillis()

        private fun getCachedMatches(key: String): List<Match>? {
            synchronized(concludedMatchCache) {
                ensureCacheVersion()

                val now = nowMs()
                val inMemory = concludedMatchCache[key]
                val entry = inMemory ?: readCacheEntryFromPrefs(key)
                if (entry == null) return null

                return if (now - entry.createdAtMs <= CONCLUDED_MATCH_CACHE_TTL_MS) {
                    concludedMatchCache[key] = entry
                    entry.matches
                } else {
                    concludedMatchCache.remove(key)
                    prefs.edit().remove(key).apply()
                    null
                }
            }
        }

        private fun putCachedMatches(key: String, matches: List<Match>) {
            synchronized(concludedMatchCache) {
                ensureCacheVersion()
                val entry = ConcludedMatchCacheEntry(
                    createdAtMs = nowMs(),
                    matches = matches,
                )
                concludedMatchCache[key] = entry
                writeCacheEntryToPrefs(key, entry)
            }
        }

        private fun ensureCacheVersion() {
            val currentVersionCode = BuildConfig.VERSION_CODE
            if (cacheVersionCode == currentVersionCode) return

            val storedVersion = prefs.getInt(PREF_KEY_VERSION_CODE, -1)
            if (storedVersion != currentVersionCode) {
                prefs.edit()
                    .clear()
                    .putInt(PREF_KEY_VERSION_CODE, currentVersionCode)
                    .apply()
            }
            concludedMatchCache.clear()
            cacheVersionCode = currentVersionCode
        }

        /** Keys whose cache entries include either participant uid (recent + shared match queries). */
        internal fun concludedCacheKeysTouchingParticipants(
            allKeys: Set<String>,
            vararg participantIds: String,
        ): Set<String> {
            val uids = participantIds.filter { it.isNotBlank() }.toSet()
            if (uids.isEmpty()) return emptySet()
            return allKeys.filter { key -> uids.any { uid -> key.contains(uid) } }.toSet()
        }

        fun invalidateConcludedMatchCacheForParticipants(player1: String, player2: String) {
            synchronized(concludedMatchCache) {
                ensureCacheVersion()
                val allKeys = buildSet {
                    addAll(concludedMatchCache.keys)
                    addAll(prefs.all.keys)
                }
                concludedCacheKeysTouchingParticipants(allKeys, player1, player2).forEach { key ->
                    concludedMatchCache.remove(key)
                    prefs.edit().remove(key).apply()
                }
            }
        }

        private fun writeCacheEntryToPrefs(key: String, entry: ConcludedMatchCacheEntry) {
            val jsonArray = JSONArray()
            entry.matches.forEach { match ->
                jsonArray.put(match.toJson())
            }
            val combined = "${entry.createdAtMs}|${jsonArray}"
            prefs.edit().putString(key, combined).apply()
        }

        private fun readCacheEntryFromPrefs(key: String): ConcludedMatchCacheEntry? {
            val stored = prefs.getString(key, null) ?: return null
            val separatorIndex = stored.indexOf('|')
            if (separatorIndex <= 0 || separatorIndex >= stored.length - 1) return null

            val createdAtMs = stored.substring(0, separatorIndex).toLongOrNull() ?: return null
            val jsonPayload = stored.substring(separatorIndex + 1)
            val matches: List<Match> =
                try {
                    val array = JSONArray(jsonPayload)
                    buildList<Match> {
                        for (i in 0 until array.length()) {
                            val obj = array.optJSONObject(i) ?: continue
                            add(obj.toMatch())
                        }
                    }
                } catch (_: Exception) {
                    return null
                }
            if (matches.isEmpty()) return null
            return ConcludedMatchCacheEntry(
                createdAtMs = createdAtMs,
                matches = matches,
            )
        }
    }

    private val uid: String
        get() = auth.currentUser?.uid ?: error("Not signed in")

    data class JoinQueueResult(
        /** Non-null when the user already has an active match and should open it immediately. */
        val immediateMatchId: String?,
        /** Wall-clock ms written as `clientJoinedAt` when queued; null for immediate-match path. */
        val clientJoinedAtMs: Long?,
    )

    /**
     * Join matchmaking via Firestore (not Callable). Writes queue/{uid}; Cloud Function pairs players.
     */
    suspend fun joinQueue(matchModes: Set<MatchMode>, profile: UserProfile): JoinQueueResult {
        require(matchModes.isNotEmpty()) { "At least one match mode must be selected" }
        val userId = uid
        require(profile.uid == userId) { "Profile uid mismatch" }
        awaitFirestoreAuth(forceRefresh = true)
        MatchSessionMonitor.awaitSessionBootstrap()
        withTimeoutOrNull(ENABLE_NETWORK_TIMEOUT_MS) {
            runCatching { firestore.enableNetwork().await() }
        }

        findLiveActiveMatchId()?.let { matchId ->
            return JoinQueueResult(immediateMatchId = matchId, clientJoinedAtMs = null)
        }

        val queueProfile = resolveQueueProfile(userId, profile)

        val joinOutcome = try {
            val callable = MatchmakingFunctions.joinQueue(matchModes, queueProfile)
            callable.activeMatchId?.let { matchId ->
                resolveJoinableActiveMatchId(matchId)?.let { liveMatchId ->
                    return JoinQueueResult(immediateMatchId = liveMatchId, clientJoinedAtMs = null)
                }
            }
            callable.clientJoinedAtMs
        } catch (callableError: Exception) {
            if (!MatchmakingFunctions.isRecoverableViaFirestore(callableError)) {
                MatchmakingFunctions.toJoinErrorMessage(callableError)?.let {
                    throw IllegalStateException(it, callableError)
                }
            }
            findLiveActiveMatchId()?.let { matchId ->
                return JoinQueueResult(immediateMatchId = matchId, clientJoinedAtMs = null)
            }
            prepareQueueForJoin(userId)
            runCatching {
                MatchSessionMonitor.awaitSessionBootstrap()
                QueueWriteGate.withLock {
                    writeQueueEntryDirect(matchModes, queueProfile)
                }
            }.getOrElse { directError ->
                val message = MatchmakingFunctions.toJoinErrorMessage(callableError)
                    ?: MatchmakingFunctions.toJoinErrorMessage(directError)
                throw IllegalStateException(message ?: directError.message, directError)
            }
        }
        MatchSessionMonitor.confirmQueueJoinedAt(joinOutcome)

        return JoinQueueResult(immediateMatchId = null, clientJoinedAtMs = joinOutcome)
    }

    /** Avoid re-queuing when the server already assigned an active match (race after pairing). */
    private suspend fun findLiveActiveMatchId(): String? {
        val userId = uid
        awaitFirestoreAuth()
        val userSnap = withTimeoutOrNull(QUEUE_READ_TIMEOUT_MS) {
            firestore.collection("users").document(userId).get(Source.SERVER).await()
        } ?: return null
        val matchId = userSnap.getString("activeMatchId") ?: return null
        return resolveJoinableActiveMatchId(matchId)
    }

    /**
     * Returns [matchId] when the user should resume that match instead of joining queue.
     * Abandons expired pre-game lobbies so a stale [activeMatchId] cannot block matchmaking.
     */
    private suspend fun resolveJoinableActiveMatchId(matchId: String): String? {
        val userId = uid
        val matchSnap = withTimeoutOrNull(QUEUE_READ_TIMEOUT_MS) {
            firestore.collection("matches").document(matchId).get(Source.SERVER).await()
        } ?: return null
        if (!matchSnap.exists()) return null
        val match = matchSnap.toMatch(matchId)
        if (!match.isParticipant(userId)) return null
        if (match.status == MatchStatus.ABANDONED) return null
        if (match.status == MatchStatus.LOBBY && match.isReadyDeadlineExpired()) {
            runCatching { confirmMatchReady(matchId) }
            return null
        }
        if (!match.isLiveForReconnect()) return null
        return matchId
    }

    private fun nowMs(): Long = System.currentTimeMillis()

    private suspend fun resolveQueueProfile(userId: String, profile: UserProfile): UserProfile {
        UserProfileSync.queueReadyProfile(userId)?.let { return it }
        if (profile.uid == userId) return profile
        val authRepository = AuthRepository()
        return authRepository.fetchServerProfile(userId)
            ?: authRepository.ensureUserProfile(
                uid = userId,
                displayName = profile.displayName,
                photoUrl = profile.photoUrl,
            ).also { UserProfileSync.rememberQueueReady(userId, it) }
    }

    private suspend fun writeQueueEntryDirect(
        matchModes: Set<MatchMode>,
        queueProfile: UserProfile,
    ): Long {
        val userId = uid
        val clientJoinedAt = System.currentTimeMillis()
        val joinedAt = Timestamp.now()
        val queuePayload = mapOf(
            "joinedAt" to joinedAt,
            "lastHeartbeatAt" to joinedAt,
            "clientJoinedAt" to clientJoinedAt,
            "elo" to queueProfile.elo,
            "displayName" to queueProfile.displayName,
            "matchModes" to matchModes.map { it.name },
        )
        writeQueueEntry(firestore.collection("queue").document(userId), queuePayload)
        return clientJoinedAt
    }

    /** Clears any prior queue doc so leave → find again does not race an in-flight join. */
    private suspend fun prepareQueueForJoin(userId: String) {
        QueueWriteGate.withLock {
            MatchSessionMonitor.clearQueueState(endMatchmaking = false)
            withTimeoutOrNull(3_000) {
                runCatching {
                    awaitFirestoreAuth(forceRefresh = true)
                    firestore.collection("queue").document(userId).delete().await()
                }
            }
        }
    }

    private suspend fun writeQueueEntry(queueRef: DocumentReference, payload: Map<String, Any>) {
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                awaitFirestoreAuth(forceRefresh = attempt > 0)
                withTimeout(QUEUE_WRITE_TIMEOUT_MS) {
                    queueRef.set(payload).awaitTask()
                }
                return
            } catch (e: FirebaseFirestoreException) {
                lastError = e
                if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    throw IllegalStateException(
                        "Could not write to the matchmaking queue (permission denied). " +
                            "Check Firebase App Check is Monitoring for Firestore.",
                        e,
                    )
                }
                delay(400)
            } catch (e: Exception) {
                lastError = e
                delay(400)
            }
        }
        throw lastError ?: IllegalStateException("Could not join matchmaking queue")
    }

    /**
     * Keeps the queue entry alive while the user is actively waiting for a match.
     * Failures are treated as transient; cleanup of truly stale entries is server-driven.
     */
    /**
     * @return true if the queue doc exists on the server, false if it is confirmed absent,
     * or null if the server could not be reached (caller should retain the local session).
     */
    suspend fun queueEntryExistsOnServer(userId: String = uid): Boolean? {
        awaitFirestoreAuth()
        val snap = withTimeoutOrNull(QUEUE_READ_TIMEOUT_MS) {
            firestore.collection("queue").document(userId).get(Source.SERVER).await()
        } ?: return null
        return snap.exists()
    }

    suspend fun sendQueueHeartbeat(): Boolean {
        val userId = auth.currentUser?.uid ?: return false
        return runCatching {
            awaitFirestoreAuth()
            val ref = firestore.collection("queue").document(userId)
            val now = Timestamp.now()
            withTimeout(QUEUE_WRITE_TIMEOUT_MS) {
                ref.update(mapOf("lastHeartbeatAt" to now)).awaitTask()
            }
            true
        }.getOrDefault(false)
    }

    suspend fun confirmMatchReady(matchId: String) {
        MatchmakingFunctions.confirmMatchReady(matchId)
    }

    suspend fun leaveQueue() {
        leaveQueueForUser(uid)
    }

    suspend fun leaveQueueForUser(userId: String) {
        awaitFirestoreAuth()
        firestore.collection("queue").document(userId).delete().await()
    }

    fun leaveQueueBestEffort(userId: String) {
        firestore.collection("queue").document(userId).deleteBestEffort()
    }

    /**
     * Drops any leftover queue doc from a previous session. Call on app launch and after sign-in.
     */
    suspend fun clearStaleSessionQueue(userId: String) {
        QueueWriteGate.withLock {
            if (MatchSessionMonitor.isMatchmakingInProgress()) return@withLock
            MatchSessionMonitor.clearQueueState()
            runCatching {
                awaitFirestoreAuth(forceRefresh = true)
                if (MatchSessionMonitor.isMatchmakingInProgress()) return@runCatching
                leaveQueueForUser(userId)
            }
        }
    }

    fun clearStaleSessionQueueBestEffort(userId: String) {
        MatchSessionMonitor.setMatchmakingInProgress(false)
        MatchSessionMonitor.clearQueueState()
        leaveQueueBestEffort(userId)
    }

    fun observeQueue(): Flow<Long?> {
        MatchSessionMonitor.ensureStarted()
        return MatchSessionMonitor.queueJoinedAtMs
    }

    suspend fun getQueueJoinedAtMs(): Long? = readQueueJoinedAtFromServer()

    suspend fun awaitQueueJoinedAtFromServer(timeoutMs: Long = 15_000): Long? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            readQueueJoinedAtFromServer()?.let { return it }
            delay(250)
        }
        return null
    }

    private suspend fun readQueueJoinedAtFromServer(): Long? {
        awaitFirestoreAuth()
        val snap = withTimeoutOrNull(QUEUE_READ_TIMEOUT_MS) {
            firestore.collection("queue").document(uid).get(Source.SERVER).await()
        } ?: return null
        if (!snap.exists()) return null
        return resolveQueueJoinedAtMs(snap)
    }

    private fun resolveQueueJoinedAtMs(snap: DocumentSnapshot): Long? {
        return snap.getTimestamp("joinedAt")?.toDate()?.time
            ?: snap.getLong("clientJoinedAt")?.takeIf { it > 0L }
    }

    suspend fun requestRoundTimeout(matchId: String, roundNumber: Int) {
        if (RoundTimeoutRequestDeduper.wasSent(matchId, roundNumber)) {
            return
        }
        // Auto-id doc: rules only allow create (not update). Reusing uid doc breaks tie replays.
        firestore.collection("matches")
            .document(matchId)
            .collection("rounds")
            .document(roundNumber.toString())
            .collection("timeoutRequests")
            .add(
                mapOf(
                    "userId" to uid,
                    "requestedAt" to FieldValue.serverTimestamp(),
                ),
            )
            .await()
        RoundTimeoutRequestDeduper.markSent(matchId, roundNumber)
    }

    /**
     * Sends the move to the server (callable preferred). Does not block on match listeners;
     * [GameViewModel] confirms via snapshot sync.
     */
    suspend fun submitMove(matchId: String, move: Move, roundNumber: Int) {
        try {
            GameFunctions.submitMove(matchId, roundNumber, move)
        } catch (callableError: Exception) {
            if (!GameFunctions.isRecoverableViaFirestore(callableError)) {
                GameFunctions.toSubmitErrorMessage(callableError)?.let {
                    throw IllegalStateException(it, callableError)
                }
            }
            submitMoveDirect(matchId, move, roundNumber, callableError)
        }
    }

    private suspend fun submitMoveDirect(
        matchId: String,
        move: Move,
        roundNumber: Int,
        callableError: Exception,
    ) {
        awaitFirestoreAuth(forceRefresh = true)
        val choiceRef = firestore.collection("matches")
            .document(matchId)
            .collection("rounds")
            .document(roundNumber.toString())
            .collection("choices")
            .document(uid)
        val payload = mapOf(
            "choice" to move.name,
            "submittedAt" to FieldValue.serverTimestamp(),
        )
        try {
            withTimeout(10_000) {
                choiceRef.set(payload).awaitTask()
            }
        } catch (directError: Exception) {
            GameFunctions.toSubmitErrorMessage(callableError)
                ?: GameFunctions.toSubmitErrorMessage(directError)?.let {
                    throw IllegalStateException(it, directError)
                }
                ?: throw directError
        }
        // Choice docs are deleted after the Cloud Function runs — only wait on the match doc.
    }

    fun observeMatch(matchId: String): Flow<Match?> = callbackFlow {
        val listener = firestore.collection("matches")
            .document(matchId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    // Keep the last snapshot on transient listener errors so match-end navigation
                    // is not lost for one client.
                    return@addSnapshotListener
                }
                trySend(snapshot?.toMatch(matchId))
            }
        awaitClose { listener.remove() }
    }

    fun observeActiveMatch(): Flow<Match?> {
        MatchSessionMonitor.ensureStarted()
        return MatchSessionMonitor.activeMatch
    }

    suspend fun getMatch(matchId: String): Match? {
        val snapshot = firestore.collection("matches").document(matchId).get().await()
        return if (snapshot.exists()) snapshot.toMatch(matchId) else null
    }

    /** Fresh match state after resume; bypasses stale local cache. */
    suspend fun getMatchFromServer(matchId: String): Match? {
        awaitFirestoreAuth()
        val snapshot = firestore.collection("matches")
            .document(matchId)
            .get(Source.SERVER)
            .await()
        return if (snapshot.exists()) snapshot.toMatch(matchId) else null
    }

    /** Drop cached concluded-match lists so profile/history reflect a match that just ended. */
    fun invalidateConcludedMatchCacheForParticipants(player1: String, player2: String) {
        Companion.invalidateConcludedMatchCacheForParticipants(player1, player2)
    }

    suspend fun getRecentMatchesForUser(userId: String, limit: Int = 10): List<Match> {
        val cacheKey = cacheKeyRecentForUser(userId, limit)
        getCachedMatches(cacheKey)?.let { return it }

        val perSide = limit.coerceAtLeast(1)
        val asPlayer1 = firestore.collection("matches")
            .whereEqualTo("player1", userId)
            .orderBy("lastActivityAt", Query.Direction.DESCENDING)
            .limit(perSide.toLong())
            .get()
            .await()
        val asPlayer2 = firestore.collection("matches")
            .whereEqualTo("player2", userId)
            .orderBy("lastActivityAt", Query.Direction.DESCENDING)
            .limit(perSide.toLong())
            .get()
            .await()

        val merged = mergeRecentMatches(asPlayer1.documents + asPlayer2.documents, limit)
        putCachedMatches(cacheKey, merged)
        return merged
    }

    suspend fun getRecentMatchesForUserSince(
        userId: String,
        sinceMs: Long,
        limit: Int = MatchRepository.DEFAULT_SINCE_MATCH_LIMIT,
    ): List<Match> {
        val cacheKey = cacheKeyRecentForUserSince(userId, sinceMs, limit)
        getCachedMatches(cacheKey)?.let { return it }

        val since = Timestamp(Date(sinceMs))
        val perSide = limit.coerceAtLeast(1)
        val merged = coroutineScope {
            val asPlayer1 = async {
                firestore.collection("matches")
                    .whereEqualTo("player1", userId)
                    .whereGreaterThanOrEqualTo("lastActivityAt", since)
                    .orderBy("lastActivityAt", Query.Direction.DESCENDING)
                    .limit(perSide.toLong())
                    .get()
                    .await()
            }
            val asPlayer2 = async {
                firestore.collection("matches")
                    .whereEqualTo("player2", userId)
                    .whereGreaterThanOrEqualTo("lastActivityAt", since)
                    .orderBy("lastActivityAt", Query.Direction.DESCENDING)
                    .limit(perSide.toLong())
                    .get()
                    .await()
            }
            mergeRecentMatches(
                asPlayer1.await().documents + asPlayer2.await().documents,
                limit,
            )
        }
        putCachedMatches(cacheKey, merged)
        return merged
    }

    /** Matches between [userId] and [opponentId], newest first. */
    suspend fun getSharedMatchesBetween(
        userId: String,
        opponentId: String,
        limit: Int = 10,
    ): List<Match> {
        val cacheKey = cacheKeySharedBetween(userId, opponentId, limit)
        getCachedMatches(cacheKey)?.let { return it }

        val perSide = limit.coerceAtLeast(1)
        val asPlayer1 = firestore.collection("matches")
            .whereEqualTo("player1", userId)
            .whereEqualTo("player2", opponentId)
            .orderBy("lastActivityAt", Query.Direction.DESCENDING)
            .limit(perSide.toLong())
            .get()
            .await()
        val asPlayer2 = firestore.collection("matches")
            .whereEqualTo("player1", opponentId)
            .whereEqualTo("player2", userId)
            .orderBy("lastActivityAt", Query.Direction.DESCENDING)
            .limit(perSide.toLong())
            .get()
            .await()

        val merged = mergeRecentMatches(asPlayer1.documents + asPlayer2.documents, limit)
        putCachedMatches(cacheKey, merged)
        return merged
    }

    suspend fun getSharedMatchesBetweenSince(
        userId: String,
        opponentId: String,
        sinceMs: Long,
        limit: Int = DEFAULT_SINCE_MATCH_LIMIT,
    ): List<Match> {
        val cacheKey = cacheKeySharedBetweenSince(userId, opponentId, sinceMs, limit)
        getCachedMatches(cacheKey)?.let { return it }

        val since = Timestamp(Date(sinceMs))
        val perSide = limit.coerceAtLeast(1)
        val asPlayer1 = firestore.collection("matches")
            .whereEqualTo("player1", userId)
            .whereEqualTo("player2", opponentId)
            .whereGreaterThanOrEqualTo("lastActivityAt", since)
            .orderBy("lastActivityAt", Query.Direction.DESCENDING)
            .limit(perSide.toLong())
            .get()
            .await()
        val asPlayer2 = firestore.collection("matches")
            .whereEqualTo("player1", opponentId)
            .whereEqualTo("player2", userId)
            .whereGreaterThanOrEqualTo("lastActivityAt", since)
            .orderBy("lastActivityAt", Query.Direction.DESCENDING)
            .limit(perSide.toLong())
            .get()
            .await()

        val merged = mergeRecentMatches(asPlayer1.documents + asPlayer2.documents, limit)
        putCachedMatches(cacheKey, merged)
        return merged
    }

    /** Matches the signed-in [viewerId] can read that also involve [opponentId]. */
    suspend fun getRecentSharedMatches(
        viewerId: String,
        opponentId: String,
        limit: Int = 10,
    ): List<Match> = getSharedMatchesBetween(
        userId = viewerId,
        opponentId = opponentId,
        limit = limit,
    )

    suspend fun getRecentSharedMatchesSince(
        viewerId: String,
        opponentId: String,
        sinceMs: Long,
        limit: Int = MatchRepository.DEFAULT_SINCE_MATCH_LIMIT,
    ): List<Match> = getSharedMatchesBetweenSince(
        userId = viewerId,
        opponentId = opponentId,
        sinceMs = sinceMs,
        limit = limit,
    )
}

private fun mergeRecentMatches(documents: List<DocumentSnapshot>, limit: Int): List<Match> =
    documents
        .map { it.toMatch(it.id) }
        .filter { it.status == MatchStatus.COMPLETED || it.status == MatchStatus.ABANDONED }
        .distinctBy { it.id }
        .sortedByDescending { it.lastActivityAt }
        .take(limit)

@Suppress("UNCHECKED_CAST")
internal fun DocumentSnapshot.toMatch(id: String): Match {
    val roundsData = get("rounds") as? List<Map<String, Any?>> ?: emptyList()
    val rounds = roundsData.map { map ->
        RoundResult(
            roundNumber = (map["roundNumber"] as? Number)?.toInt() ?: 0,
            player1Submitted = map["player1Submitted"] as? Boolean ?: false,
            player2Submitted = map["player2Submitted"] as? Boolean ?: false,
            player1Choice = map["player1Choice"] as? String,
            player2Choice = map["player2Choice"] as? String,
            winner = map["winner"] as? String,
            endReason = RoundEndReason.fromString(map["endReason"] as? String),
            resolvedAt = (map["resolvedAt"] as? Timestamp)?.toDate()?.time,
            startedAt = (map["startedAt"] as? Timestamp)?.toDate()?.time,
            deadline = (map["deadline"] as? Timestamp)?.toDate()?.time,
            player1MoveMs = (map["player1MoveMs"] as? Number)?.toInt(),
            player2MoveMs = (map["player2MoveMs"] as? Number)?.toInt(),
        )
    }

    return Match(
        id = id,
        player1 = getString("player1") ?: "",
        player2 = getString("player2") ?: "",
        player1Name = getString("player1Name") ?: "Player 1",
        player2Name = getString("player2Name") ?: "Player 2",
        matchMode = MatchMode.fromString(getString("matchMode")),
        status = MatchStatus.fromString(getString("status")),
        player1Ready = contains("player1Ready") && getBoolean("player1Ready") == true,
        player2Ready = contains("player2Ready") && getBoolean("player2Ready") == true,
        readyDeadlineAt = getTimestamp("readyDeadlineAt")?.toDate()?.time ?: 0L,
        currentRound = getLong("currentRound")?.toInt() ?: 1,
        player1Wins = getLong("player1Wins")?.toInt() ?: 0,
        player2Wins = getLong("player2Wins")?.toInt() ?: 0,
        player1MoveTimeMs = getLong("player1MoveTimeMs") ?: 0L,
        player2MoveTimeMs = getLong("player2MoveTimeMs") ?: 0L,
        player1MoveCount = getLong("player1MoveCount")?.toInt() ?: 0,
        player2MoveCount = getLong("player2MoveCount")?.toInt() ?: 0,
        player1ClockMs = getLong("player1ClockMs") ?: GameRules.INITIAL_CLOCK_MS,
        player2ClockMs = getLong("player2ClockMs") ?: GameRules.INITIAL_CLOCK_MS,
        clocksUpdatedAt = getTimestamp("clocksUpdatedAt")?.toDate()?.time ?: 0L,
        rounds = rounds,
        winnerId = getString("winnerId"),
        resolution = MatchResolution.fromString(getString("resolution")),
        endReason = MatchEndReason.fromString(getString("endReason")),
        player1EloDelta = getIntField("player1EloDelta"),
        player2EloDelta = getIntField("player2EloDelta"),
        player1Elo = getIntField("player1Elo"),
        player2Elo = getIntField("player2Elo"),
        createdAt = getTimestamp("createdAt")?.toDate()?.time ?: 0L,
        lastActivityAt = getTimestamp("lastActivityAt")?.toDate()?.time ?: 0L,
    )
}

private fun Match.toJson(): JSONObject {
    val obj = JSONObject()
    obj.put("id", id)
    obj.put("player1", player1)
    obj.put("player2", player2)
    obj.put("player1Name", player1Name)
    obj.put("player2Name", player2Name)
    obj.put("matchMode", matchMode.name)
    obj.put("status", status.name)
    obj.put("player1Ready", player1Ready)
    obj.put("player2Ready", player2Ready)
    if (readyDeadlineAt > 0L) obj.put("readyDeadlineAt", readyDeadlineAt)
    obj.put("currentRound", currentRound)
    obj.put("player1Wins", player1Wins)
    obj.put("player2Wins", player2Wins)
    obj.put("player1MoveTimeMs", player1MoveTimeMs)
    obj.put("player2MoveTimeMs", player2MoveTimeMs)
    obj.put("player1MoveCount", player1MoveCount)
    obj.put("player2MoveCount", player2MoveCount)
    obj.put("player1ClockMs", player1ClockMs)
    obj.put("player2ClockMs", player2ClockMs)
    obj.put("clocksUpdatedAt", clocksUpdatedAt)
    obj.put("winnerId", winnerId)
    obj.put("resolution", resolution?.name)
    obj.put("endReason", endReason?.name)
    obj.put("player1EloDelta", player1EloDelta)
    obj.put("player2EloDelta", player2EloDelta)
    obj.put("player1Elo", player1Elo)
    obj.put("player2Elo", player2Elo)
    obj.put("createdAt", createdAt)
    obj.put("lastActivityAt", lastActivityAt)

    val roundsArray = JSONArray()
    rounds.forEach { round ->
        roundsArray.put(round.toJson())
    }
    obj.put("rounds", roundsArray)

    return obj
}

private fun JSONObject.toMatch(): Match {
    val roundsArray = optJSONArray("rounds") ?: JSONArray()
    val rounds = buildList {
        for (i in 0 until roundsArray.length()) {
            val obj = roundsArray.optJSONObject(i) ?: continue
            add(obj.toRoundResult())
        }
    }

    return Match(
        id = optString("id", ""),
        player1 = optString("player1", ""),
        player2 = optString("player2", ""),
        player1Name = optString("player1Name", ""),
        player2Name = optString("player2Name", ""),
        matchMode = MatchMode.fromString(optNullableString("matchMode")),
        status = MatchStatus.fromString(optNullableString("status")),
        player1Ready = optBoolean("player1Ready", false),
        player2Ready = optBoolean("player2Ready", false),
        readyDeadlineAt = optLong("readyDeadlineAt", 0L),
        currentRound = optInt("currentRound", 1),
        player1Wins = optInt("player1Wins", 0),
        player2Wins = optInt("player2Wins", 0),
        player1MoveTimeMs = optLong("player1MoveTimeMs", 0L),
        player2MoveTimeMs = optLong("player2MoveTimeMs", 0L),
        player1MoveCount = optInt("player1MoveCount", 0),
        player2MoveCount = optInt("player2MoveCount", 0),
        player1ClockMs = optLong("player1ClockMs", GameRules.INITIAL_CLOCK_MS),
        player2ClockMs = optLong("player2ClockMs", GameRules.INITIAL_CLOCK_MS),
        clocksUpdatedAt = optLong("clocksUpdatedAt", 0L),
        rounds = rounds,
        winnerId = optNullableString("winnerId"),
        resolution = MatchResolution.fromString(optNullableString("resolution")),
        endReason = MatchEndReason.fromString(optNullableString("endReason")),
        player1EloDelta = optNullableInt("player1EloDelta"),
        player2EloDelta = optNullableInt("player2EloDelta"),
        player1Elo = optNullableInt("player1Elo"),
        player2Elo = optNullableInt("player2Elo"),
        createdAt = optLong("createdAt", 0L),
        lastActivityAt = optLong("lastActivityAt", 0L),
    )
}

private fun RoundResult.toJson(): JSONObject {
    val obj = JSONObject()
    obj.put("roundNumber", roundNumber)
    if (player1Submitted) obj.put("player1Submitted", true)
    if (player2Submitted) obj.put("player2Submitted", true)
    obj.put("player1Choice", player1Choice)
    obj.put("player2Choice", player2Choice)
    obj.put("winner", winner)
    obj.put("endReason", endReason?.name)
    obj.put("resolvedAt", resolvedAt)
    obj.put("startedAt", startedAt)
    obj.put("deadline", deadline)
    obj.put("player1MoveMs", player1MoveMs)
    obj.put("player2MoveMs", player2MoveMs)
    return obj
}

private fun JSONObject.toRoundResult(): RoundResult =
    RoundResult(
        roundNumber = optInt("roundNumber", 0),
        player1Submitted = optBoolean("player1Submitted", false),
        player2Submitted = optBoolean("player2Submitted", false),
        player1Choice = optNullableString("player1Choice"),
        player2Choice = optNullableString("player2Choice"),
        winner = optNullableString("winner"),
        endReason = RoundEndReason.fromString(optNullableString("endReason")),
        resolvedAt = optNullableLong("resolvedAt"),
        startedAt = optNullableLong("startedAt"),
        deadline = optNullableLong("deadline"),
        player1MoveMs = optNullableInt("player1MoveMs"),
        player2MoveMs = optNullableInt("player2MoveMs"),
    )

private fun JSONObject.optNullableString(name: String): String? =
    if (has(name) && !isNull(name)) optString(name) else null

private fun JSONObject.optNullableInt(name: String): Int? =
    if (has(name) && !isNull(name)) optInt(name) else null

private fun JSONObject.optNullableLong(name: String): Long? =
    if (has(name) && !isNull(name)) optLong(name) else null

private fun JSONObject.optBoolean(name: String, default: Boolean): Boolean =
    if (has(name) && !isNull(name)) getBoolean(name) else default

private data class ConcludedMatchCacheEntry(
    val createdAtMs: Long,
    val matches: List<Match>,
)
