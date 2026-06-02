package com.rpsonline.app.data.repository

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Re-establishes Firestore after the app returns from sleep/background.
 * Safe to call repeatedly; failures are ignored so UI is never blocked.
 */
object FirestoreConnectivity {
    private val restoreMutex = Mutex()
    @Volatile
    private var lastSoftRestoreAtMs = 0L
    @Volatile
    private var lastHardRestoreAtMs = 0L

    /** Light reconnect after resume — avoids toggling the network on every foreground. */
    suspend fun restoreOnResume() {
        performSoftRestore(bypassThrottle = true)
    }

    /**
     * Reconnect after the OS reports a network became available.
     * @param preferHardReset use disable/enable only after a real offline period (throttled).
     */
    suspend fun restoreAfterConnectivityLoss(preferHardReset: Boolean = false) {
        restoreMutex.withLock {
            val nowMs = System.currentTimeMillis()
            if (FirestoreRestorePolicy.shouldHardReset(
                    preferHardReset = preferHardReset,
                    lastHardRestoreMs = lastHardRestoreAtMs,
                    nowMs = nowMs,
                )
            ) {
                performHardRestore(nowMs)
            } else if (FirestoreRestorePolicy.shouldSoftRestore(
                    lastSoftRestoreMs = lastSoftRestoreAtMs,
                    nowMs = nowMs,
                )
            ) {
                performSoftRestoreInternal(nowMs, refreshAuth = preferHardReset)
            }
        }
    }

    private suspend fun performSoftRestore(bypassThrottle: Boolean) {
        restoreMutex.withLock {
            val nowMs = System.currentTimeMillis()
            if (!FirestoreRestorePolicy.shouldSoftRestore(
                    lastSoftRestoreMs = lastSoftRestoreAtMs,
                    nowMs = nowMs,
                    bypassThrottle = bypassThrottle,
                )
            ) {
                return
            }
            performSoftRestoreInternal(nowMs, refreshAuth = false)
        }
    }

    private suspend fun performSoftRestoreInternal(nowMs: Long, refreshAuth: Boolean) {
        lastSoftRestoreAtMs = nowMs
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) return
        awaitFirestoreAuth(auth, forceRefresh = refreshAuth)
        runCatching {
            appFirestore().enableNetwork().await()
        }
    }

    private suspend fun performHardRestore(nowMs: Long) {
        lastHardRestoreAtMs = nowMs
        lastSoftRestoreAtMs = nowMs
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser ?: return
        runCatching {
            withTimeoutOrNull(5_000) {
                user.getIdToken(true).await()
            }
        }
        awaitFirestoreAuth(auth, forceRefresh = true)
        val firestore = appFirestore()
        runCatching {
            firestore.disableNetwork().await()
            firestore.enableNetwork().await()
        }
        runCatching {
            firestore.enableNetwork().await()
        }
    }
}
