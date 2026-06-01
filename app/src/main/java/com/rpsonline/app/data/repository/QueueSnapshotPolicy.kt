package com.rpsonline.app.data.repository

/** How Firestore queue listener updates affect local matchmaking session markers. */
internal object QueueSnapshotPolicy {
    /** Ignore transient listener errors while waiting in queue (e.g. screen lock / doze). */
    fun shouldRetainSessionOnListenerError(matchmakingInProgress: Boolean, error: Exception?): Boolean =
        matchmakingInProgress && error != null

    /** Offline/cache gaps should not drop an active queue session. */
    fun shouldRetainSessionOnMissingDoc(
        matchmakingInProgress: Boolean,
        exists: Boolean,
        fromCache: Boolean,
    ): Boolean = matchmakingInProgress && !exists && fromCache

    /** Server-confirmed queue doc deletion (not a cache/offline gap). */
    fun isAuthoritativeQueueMissing(exists: Boolean, fromCache: Boolean): Boolean =
        !exists && !fromCache
}
