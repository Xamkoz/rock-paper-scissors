package com.rpsonline.app.ui.util

import android.content.Context
import android.os.SystemClock
import com.rpsonline.app.data.preferences.SoundPreferences
import com.rpsonline.app.platform.AppForegroundTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** App-wide match clock tick loop; foreground UI and background service both call [sync]. */
object MatchClockSoundController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var appContext: Context? = null
    private var player: ClockTickPlayer? = null
    private var tickJob: Job? = null
    private var hapticAnchorElapsedMs: Long? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (player != null) return
        player = ClockTickPlayer(context)
    }

    fun sync(shouldRun: Boolean) {
        if (!shouldRun) {
            tickJob?.cancel()
            tickJob = null
            player?.stop()
            hapticAnchorElapsedMs = null
            return
        }
        val tickPlayer = player ?: return
        if (tickJob?.isActive == true) return
        hapticAnchorElapsedMs = SystemClock.elapsedRealtime()
        tickJob = scope.launch {
            try {
                while (isActive) {
                    val ctx = appContext
                    val mode = ctx?.let { SoundPreferences(it).getMode() }
                    if (mode?.allowsSound() == true) {
                        tickPlayer.playTick()
                    }
                    val anchor = hapticAnchorElapsedMs
                    if (
                        mode?.allowsHaptic() == true &&
                        anchor != null &&
                        matchClockHapticDelayElapsed(
                            anchorElapsedMs = anchor,
                            nowElapsedMs = SystemClock.elapsedRealtime(),
                        )
                    ) {
                        MatchClockHaptics.pulseTick()
                    }
                    delay(500)
                }
            } finally {
                tickPlayer.stop()
                hapticAnchorElapsedMs = null
            }
        }
    }

    /** Keeps match clock feedback while backgrounded; Compose stops receiving match updates there. */
    fun syncFromSessionWhenBackground(context: Context) {
        if (AppForegroundTracker.isInForeground) return
        initialize(context)
        sync(MatchClockSoundPolicy.shouldRunMatchClock(context))
    }
}
