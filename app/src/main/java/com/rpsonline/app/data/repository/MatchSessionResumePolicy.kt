package com.rpsonline.app.data.repository

import com.rpsonline.app.data.model.Match

/** Home should show the launch spinner only while a live match open is still pending. */
fun shouldDeferHomeForGameLaunch(
    pendingLaunchMatchId: String?,
    matchmakingInProgress: Boolean,
    sessionMatch: Match?,
): Boolean {
    val matchId = pendingLaunchMatchId ?: return false
    if (matchmakingInProgress) return false
    if (shouldDropPendingGameNavigation(matchId, sessionMatch)) return false
    return true
}

/** Home/Monitor queue UI is out of sync after a match or session reset. */
fun shouldClearStaleQueueUiOnResume(
    monitorMatchmaking: Boolean,
    hasQueueEntry: Boolean,
    queueJoinedAtMs: Long?,
    queueAnchorMs: Long?,
    isInQueue: Boolean,
    isJoiningQueue: Boolean,
): Boolean {
    val monitorQueued = monitorMatchmaking || hasQueueEntry || queueJoinedAtMs != null || queueAnchorMs != null
    if (monitorQueued) return false
    return isInQueue || isJoiningQueue
}

/** Resume should restore queue search only when the monitor still has queue markers. */
fun shouldReconcileQueueSessionOnResume(
    isInQueue: Boolean,
    isJoiningQueue: Boolean,
    monitorMatchmaking: Boolean,
    hasQueueEntry: Boolean,
    queueJoinedAtMs: Long?,
    queueAnchorMs: Long?,
): Boolean {
    if (hasQueueEntry || queueJoinedAtMs != null) return true
    if (isJoiningQueue) return true
    if (monitorMatchmaking && (isInQueue || queueAnchorMs != null)) return true
    return false
}
