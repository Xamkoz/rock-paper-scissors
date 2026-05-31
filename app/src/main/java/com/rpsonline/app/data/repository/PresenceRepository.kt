package com.rpsonline.app.data.repository

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class PresenceRepository(
    private val firestore: FirebaseFirestore = appFirestore(),
) {
    private val _onlineCount = MutableStateFlow<Int?>(null)
    val onlineCount: StateFlow<Int?> = _onlineCount.asStateFlow()

    /** serverTimeMs - clientTimeMs; shared so all instances agree on the online window. */
    companion object {
        @Volatile
        var serverTimeOffsetMs: Long = 0L
            private set

        fun updateServerTimeOffset(serverTimeMs: Long) {
            serverTimeOffsetMs = serverTimeMs - System.currentTimeMillis()
        }

        const val COLLECTION = "presence"
        /** Legacy profile activity window (match stats, guest cleanup). */
        const val ONLINE_WINDOW_MS = 2 * 60 * 1000L
        /** Presence heartbeat window for the online counter (~4 missed beats at 20s). */
        const val ONLINE_PRESENCE_WINDOW_MS = 90_000L
        /** Extra time before UI drops a player after their last heartbeat expires. */
        const val ONLINE_DISPLAY_GRACE_MS = 25_000L
        const val HEARTBEAT_INTERVAL_MS = 20_000L
        private const val PRESENCE_WRITE_TIMEOUT_MS = 8_000L
        private const val PRESENCE_SYNC_TIMEOUT_MS = 10_000L
        private const val PRESENCE_ACK_MAX_AGE_MS = 90_000L
        private const val ONLINE_REEVALUATE_INTERVAL_MS = 5_000L

        internal fun countOnlineUids(
            lastSeenByUid: Map<String, Long?>,
            onlineWindowMs: Long = ONLINE_PRESENCE_WINDOW_MS,
            nowMs: Long = System.currentTimeMillis(),
            selfUid: String? = null,
        ): Int {
            val onlineUids = lastSeenByUid
                .filter { (_, lastSeenMs) ->
                    lastSeenMs != null && lastSeenMs >= nowMs - onlineWindowMs
                }
                .keys
                .toMutableSet()
            if (selfUid != null) {
                onlineUids.add(selfUid)
            }
            return onlineUids.size
        }

        internal fun onlineUidsFromLastSeen(
            tracked: Set<String>,
            lastSeenByUid: Map<String, Long?>,
            lastOnlineEmittedAt: MutableMap<String, Long>,
            nowMs: Long = System.currentTimeMillis() + serverTimeOffsetMs,
        ): Set<String> {
            return tracked.filterTo(mutableSetOf()) { uid ->
                val lastSeenMs = lastSeenByUid[uid]
                val fresh = lastSeenMs != null && lastSeenMs >= nowMs - ONLINE_PRESENCE_WINDOW_MS
                when {
                    fresh -> {
                        lastOnlineEmittedAt[uid] = nowMs
                        true
                    }
                    nowMs < (lastOnlineEmittedAt[uid] ?: 0L) + ONLINE_DISPLAY_GRACE_MS -> true
                    else -> false
                }
            }
        }
    }

    private fun updateServerTimeOffset(serverTimeMs: Long) {
        Companion.updateServerTimeOffset(serverTimeMs)
    }

    fun clearOnlineCount() {
        _onlineCount.value = null
    }

    /**
     * Writes [COLLECTION]/[uid] so other clients can count this player as online.
     * Prefers the [touchPresence] Cloud Function (server timestamp); falls back to Firestore.
     * Updates [onlineCount] when the callable returns [TouchPresenceResult.onlineCount].
     */
    suspend fun touchPresence(
        uid: String,
        forceAuthRefresh: Boolean = false,
        awaitServerAck: Boolean = false,
    ) {
        val touchResult = PresenceFunctions.tryTouchPresence()
        if (touchResult != null) {
            updateServerTimeOffset(touchResult.serverTimeMs)
            touchResult.onlineCount?.let { _onlineCount.value = it }
            return
        }
        touchPresenceViaFirestore(uid, forceAuthRefresh, awaitServerAck)
    }

    private suspend fun touchPresenceViaFirestore(
        uid: String,
        forceAuthRefresh: Boolean,
        awaitServerAck: Boolean,
    ) {
        val payload = mapOf("lastSeen" to Timestamp.now())
        val presenceRef = firestore.collection(COLLECTION).document(uid)
        val attempts = if (awaitServerAck) 3 else 1

        for (attempt in 0 until attempts) {
            val wrote = runCatching {
                awaitFirestoreAuth(forceRefresh = forceAuthRefresh || attempt > 0)
                withTimeout(PRESENCE_WRITE_TIMEOUT_MS) {
                    presenceRef.set(payload).awaitTask()
                }
                if (awaitServerAck) {
                    presenceRef.confirmPresenceFreshOnServer(
                        maxAgeMs = PRESENCE_ACK_MAX_AGE_MS,
                        confirmTimeoutMs = PRESENCE_SYNC_TIMEOUT_MS,
                    )
                } else {
                    true
                }
            }.getOrElse { false }
            if (wrote) {
                firestore.collection("users").document(uid).updateBestEffort(payload)
                return
            }
            if (awaitServerAck) delay(400)
        }
    }

    fun clearPresence(uid: String) {
        firestore.collection(COLLECTION)
            .document(uid)
            .deleteBestEffort()
    }

    /** Live set of [uids] whose lastSeen is within [ONLINE_PRESENCE_WINDOW_MS]. */
    fun observeOnlineUids(uids: Collection<String>): Flow<Set<String>> {
        val tracked = uids.filter { it.isNotBlank() }.toSet()
        if (tracked.isEmpty()) {
            return kotlinx.coroutines.flow.flowOf(emptySet())
        }
        return callbackFlow {
            val lastSeenByUid = mutableMapOf<String, Long?>()
            val lastOnlineEmittedAt = mutableMapOf<String, Long>()
            val listeners = mutableListOf<ListenerRegistration>()
            fun emitOnline() {
                val nowMs = System.currentTimeMillis() + serverTimeOffsetMs
                val online = onlineUidsFromLastSeen(
                    tracked = tracked,
                    lastSeenByUid = lastSeenByUid,
                    lastOnlineEmittedAt = lastOnlineEmittedAt,
                    nowMs = nowMs,
                )
                trySend(online)
            }
            for (uid in tracked) {
                val registration = firestore.collection(COLLECTION)
                    .document(uid)
                    .addSnapshotListener { snapshot, _ ->
                        lastSeenByUid[uid] = snapshot?.getTimestamp("lastSeen")?.toDate()?.time
                        emitOnline()
                    }
                listeners += registration
            }
            emitOnline()
            val reevaluateJob = launch {
                while (isActive) {
                    delay(ONLINE_REEVALUATE_INTERVAL_MS)
                    emitOnline()
                }
            }
            awaitClose {
                reevaluateJob.cancel()
                listeners.forEach { it.remove() }
            }
        }.distinctUntilChanged()
    }
}
