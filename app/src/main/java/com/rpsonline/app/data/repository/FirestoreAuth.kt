package com.rpsonline.app.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun awaitFirestoreAuth(
    auth: FirebaseAuth = FirebaseAuth.getInstance(),
    forceRefresh: Boolean = false,
) {
    val user = auth.currentUser ?: return
    // After OAuth sign-in Firestore may still see the previous session until the token refreshes.
    withTimeoutOrNull(10_000) {
        user.getIdToken(forceRefresh).await()
    }
}

/**
 * Cloud Functions require a fresh ID token. Unlike [awaitFirestoreAuth], this fails if the
 * token cannot be obtained (callers can fall back to direct Firestore writes).
 */
internal suspend fun awaitCallableAuth(
    auth: FirebaseAuth = FirebaseAuth.getInstance(),
    timeoutMs: Long = 15_000,
): FirebaseUser {
    val user = auth.currentUser ?: error("Not signed in")
    var lastError: Exception? = null
    repeat(2) { attempt ->
        try {
            withTimeout(timeoutMs) {
                user.getIdToken(true).await()
            }
            return user
        } catch (e: Exception) {
            lastError = e
            if (attempt == 0) delay(400)
        }
    }
    throw lastError ?: IllegalStateException("Could not refresh sign-in token")
}

/** Prefer cached token for latency; refresh only if needed before a move callable. */
internal suspend fun awaitCallableAuthForSubmit(
    auth: FirebaseAuth = FirebaseAuth.getInstance(),
): FirebaseUser {
    val user = auth.currentUser ?: error("Not signed in")
    try {
        withTimeout(4_000) {
            user.getIdToken(false).await()
        }
    } catch (_: Exception) {
        withTimeout(6_000) {
            user.getIdToken(true).await()
        }
    }
    return user
}
