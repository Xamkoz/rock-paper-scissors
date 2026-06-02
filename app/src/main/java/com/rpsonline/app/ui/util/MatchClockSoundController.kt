package com.rpsonline.app.ui.util

import android.content.Context
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
    private var player: ClockTickPlayer? = null
    private var tickJob: Job? = null

    fun initialize(context: Context) {
        if (player != null) return
        player = ClockTickPlayer(context)
    }

    fun sync(shouldPlay: Boolean) {
        if (!shouldPlay) {
            tickJob?.cancel()
            tickJob = null
            player?.stop()
            return
        }
        val tickPlayer = player ?: return
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            try {
                while (isActive) {
                    tickPlayer.playTick()
                    delay(500)
                }
            } finally {
                tickPlayer.stop()
            }
        }
    }

    /** Keeps match clock audible while backgrounded; Compose stops receiving match updates there. */
    fun syncFromSessionWhenBackground(context: Context) {
        if (AppForegroundTracker.isInForeground) return
        initialize(context)
        sync(MatchClockSoundPolicy.shouldPlayMatchClock(context))
    }
}
