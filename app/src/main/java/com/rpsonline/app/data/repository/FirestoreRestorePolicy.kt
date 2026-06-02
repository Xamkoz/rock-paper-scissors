package com.rpsonline.app.data.repository

/**
 * Decides when to soft-enable vs hard-reset Firestore. Hard resets tear down all
 * watch streams and often surface transient DNS UNAVAILABLE logs during network flaps.
 */
internal object FirestoreRestorePolicy {
    const val SOFT_RESTORE_MIN_INTERVAL_MS = 3_000L
    const val HARD_RESTORE_MIN_INTERVAL_MS = 120_000L

    fun shouldSoftRestore(
        lastSoftRestoreMs: Long,
        nowMs: Long,
        bypassThrottle: Boolean = false,
        minIntervalMs: Long = SOFT_RESTORE_MIN_INTERVAL_MS,
    ): Boolean = bypassThrottle || nowMs - lastSoftRestoreMs >= minIntervalMs

    fun shouldHardReset(
        preferHardReset: Boolean,
        lastHardRestoreMs: Long,
        nowMs: Long,
        minIntervalMs: Long = HARD_RESTORE_MIN_INTERVAL_MS,
    ): Boolean = preferHardReset && nowMs - lastHardRestoreMs >= minIntervalMs
}
