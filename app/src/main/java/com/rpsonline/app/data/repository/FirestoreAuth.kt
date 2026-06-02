package com.rpsonline.app.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Refreshes the Firebase ID token when possible. Network and internal auth failures are
 * swallowed so callers on the main thread never crash the process.
 */
internal suspend fun awaitFirestoreAuth(
    auth: FirebaseAuth = FirebaseAuth.getInstance(),
    forceRefresh: Boolean = false,
) {
    val user = auth.currentUser ?: return
    runCatching {
        withTimeout(10_000) {
            user.getIdToken(forceRefresh).await()
        }
    }
}

/**
 * Cloud Functions require a fresh ID token. Returns null when the token cannot be obtained.
 */
internal suspend fun awaitCallableAuth(
    auth: FirebaseAuth = FirebaseAuth.getInstance(),
    timeoutMs: Long = 15_000,
): FirebaseUser? {
    val user = auth.currentUser ?: return null
    repeat(2) { attempt ->
        val refreshed = runCatching {
            withTimeout(timeoutMs) {
                user.getIdToken(true).await()
            }
        }
        if (refreshed.isSuccess) return user
        if (attempt == 0) delay(400)
    }
    return null
}

/** Prefer cached token for latency; refresh only if needed before a move callable. */
internal suspend fun awaitCallableAuthForSubmit(
    auth: FirebaseAuth = FirebaseAuth.getInstance(),
): FirebaseUser? {
    val user = auth.currentUser ?: return null
    val cached = runCatching {
        withTimeout(4_000) {
            user.getIdToken(false).await()
        }
    }
    if (cached.isSuccess) return user
    val refreshed = runCatching {
        withTimeout(6_000) {
            user.getIdToken(true).await()
        }
    }
    return refreshed.getOrNull()?.let { user }
}
