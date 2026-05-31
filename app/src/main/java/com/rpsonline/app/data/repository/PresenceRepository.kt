package com.rpsonline.app.data.repository

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout

class PresenceRepository(
    private val firestore: FirebaseFirestore = appFirestore(),
) {
    private val _onlineCount = MutableStateFlow<Int?>(null)
    val onlineCount: StateFlow<Int?> = _onlineCount.asStateFlow()

    /** serverTimeMs - clientTimeMs; used so online-window checks match server timestamps. */
    @Volatile
    private var serverTimeOffsetMs: Long = 0L

    private fun updateServerTimeOffset(serverTimeMs: Long) {
        serverTimeOffsetMs = serverTimeMs - System.currentTimeMillis()
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

    companion object {
        const val COLLECTION = "presence"
        /** Legacy profile activity window (match stats, guest cleanup). */
        const val ONLINE_WINDOW_MS = 2 * 60 * 1000L
        /** Presence heartbeat window for the online counter (~4 missed beats at 20s). */
        const val ONLINE_PRESENCE_WINDOW_MS = 90_000L
        const val HEARTBEAT_INTERVAL_MS = 20_000L
        private const val PRESENCE_WRITE_TIMEOUT_MS = 8_000L
        private const val PRESENCE_SYNC_TIMEOUT_MS = 10_000L
        private const val PRESENCE_ACK_MAX_AGE_MS = 90_000L

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
    }
}
