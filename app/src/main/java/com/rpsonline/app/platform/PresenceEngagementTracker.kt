package com.rpsonline.app.platform

import android.content.Context
import android.os.PowerManager

/** Tracks screen-on and recent user interaction for presence heartbeats. */
object PresenceEngagementTracker {
    /** Stop counting as online after this long without a touch while foreground-idle. */
    const val IDLE_TIMEOUT_MS = 60_000L
    const val ENGAGEMENT_POLL_MS = 5_000L

    @Volatile
    private var lastInteractionMs: Long = System.currentTimeMillis()

    @Volatile
    private var screenInteractive: Boolean = true

    fun recordInteraction(nowMs: Long = System.currentTimeMillis()) {
        lastInteractionMs = nowMs
    }

    fun setScreenInteractive(interactive: Boolean) {
        screenInteractive = interactive
    }

    fun syncScreenInteractive(context: Context) {
        val powerManager = context.getSystemService(PowerManager::class.java)
        screenInteractive = powerManager?.isInteractive != false
    }

    fun isEngaged(
        nowMs: Long = System.currentTimeMillis(),
        idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
    ): Boolean {
        if (!screenInteractive) return false
        return nowMs - lastInteractionMs < idleTimeoutMs
    }
}
