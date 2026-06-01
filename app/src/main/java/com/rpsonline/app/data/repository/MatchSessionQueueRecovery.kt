package com.rpsonline.app.data.repository

internal enum class QueueRecoveryStep {
    SKIP,
    RETRY_LATER,
    SYNC,
    REJOIN,
}

/**
 * Decides whether background queue recovery should sync an existing server doc or re-join.
 * Re-join is required when local matchmaking UI is active but queue/{uid} is gone on the server.
 */
internal fun resolveQueueRecoveryStep(
    matchmakingInProgress: Boolean,
    queueEntryPending: Boolean,
    serverQueueExists: Boolean?,
): QueueRecoveryStep {
    if (!matchmakingInProgress) return QueueRecoveryStep.SKIP
    if (queueEntryPending) return QueueRecoveryStep.SKIP
    return when (serverQueueExists) {
        true -> QueueRecoveryStep.SYNC
        null -> QueueRecoveryStep.RETRY_LATER
        false -> QueueRecoveryStep.REJOIN
    }
}
