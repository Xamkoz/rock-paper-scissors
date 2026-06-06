package com.rpsonline.app.data.repository

/** How Firestore match/user listener errors affect the cached active match. */
internal object MatchSnapshotPolicy {
    /** Keep the last known match during transient listener errors (offline / doze). */
    fun shouldRetainActiveMatchOnListenerError(
        trackedMatchId: String?,
        error: Exception?,
    ): Boolean = !trackedMatchId.isNullOrBlank() && error != null
}
