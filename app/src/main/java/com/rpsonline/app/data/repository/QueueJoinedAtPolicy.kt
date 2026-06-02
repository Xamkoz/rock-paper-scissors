package com.rpsonline.app.data.repository

/**
 * Prefer the most recent join timestamp when cache and server disagree.
 * Stale snapshots from before the current queue session must not inflate elapsed time.
 */
fun mergeQueueJoinedAtMs(current: Long?, candidateMs: Long): Long {
    if (candidateMs <= 0L) return current ?: candidateMs
    return when {
        current == null -> candidateMs
        candidateMs > current -> candidateMs
        else -> current
    }
}

/** Avoid a server-ahead `joinedAt` pinning queue UI at 0:00 until device clock catches up. */
fun normalizeQueueAnchorMs(serverAnchorMs: Long, nowMs: Long = System.currentTimeMillis()): Long =
    if (serverAnchorMs <= 0L) nowMs else minOf(serverAnchorMs, nowMs)
