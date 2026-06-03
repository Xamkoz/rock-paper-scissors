package com.rpsonline.app.data.repository

/** Recovery must not be skipped from a stale local [hasQueueEntry] flag alone. */
internal fun shouldSkipQueueRecovery(
    hasQueueEntry: Boolean,
    queueJoinedAtMs: Long?,
    serverQueueExists: Boolean?,
): Boolean {
    if (!hasQueueEntry || queueJoinedAtMs == null) return false
    return serverQueueExists == true
}
