package com.rpsonline.app.ui.util

import android.content.Context
import android.os.SystemClock
import com.rpsonline.app.data.preferences.SoundPreferences
import com.rpsonline.app.platform.AppForegroundTracker
import com.rpsonline.app.platform.JoinMatchNotificationState
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
    private var lobbyTickPlayer: ClockTickPlayer? = null
    private var tickJob: Job? = null
    private var lobbyTickJob: Job? = null
    private var hapticAnchorElapsedMs: Long? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (player == null) {
            player = ClockTickPlayer(context)
        }
        if (lobbyTickPlayer == null) {
            lobbyTickPlayer = ClockTickPlayer(context, GameAudioContext.notificationSoundAttributes())
        }
    }

    fun playReadyBurst() {
        player?.playReadyTick()
    }

    /** Ready/opponent-ready burst during pre-game lobby (notification stream + volume). */
    fun playLobbyReadyFeedback(context: Context) {
        val ctx = context.applicationContext
        initialize(ctx)
        if (lobbyAlertSoundsAudible(ctx)) {
            lobbyTickPlayer?.playReadyTick()
        }
        val mode = SoundPreferences(ctx).getMode()
        if (lobbyAlertHapticsAllowed(ctx)) {
            MatchClockHaptics.initialize(ctx)
            MatchClockHaptics.pulseTick()
        }
    }

    private fun lobbyAlertSoundsAudible(ctx: Context): Boolean {
        val mode = SoundPreferences(ctx).getMode()
        if (AppForegroundTracker.isInForeground) {
            return mode.allowsSound()
        }
        if (!mode.allowsLobbyAlertTickSounds()) return false
        return NotificationAlertSoundPolicy.notificationSoundsAudible(ctx)
    }

    private fun lobbyAlertHapticsAllowed(ctx: Context): Boolean {
        val mode = SoundPreferences(ctx).getMode()
        if (!mode.allowsHaptic()) return false
        return NotificationAlertSoundPolicy.notificationHapticsAllowed(ctx)
    }

    private fun pulseLobbyAlertHapticIfAllowed(ctx: Context) {
        if (!lobbyAlertHapticsAllowed(ctx)) return
        MatchClockHaptics.initialize(ctx)
        MatchClockHaptics.pulseTick()
    }

    private fun lobbyTicksActive(): Boolean = lobbyTickJob?.isActive == true

    fun sync(shouldRun: Boolean) {
        if (!shouldRun) {
            tickJob?.cancel()
            tickJob = null
            if (!lobbyTicksActive()) {
                player?.stop()
                hapticAnchorElapsedMs = null
            }
            return
        }
        syncLobbyAlert(false)
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

    /** Match-found alert: ready burst once, then notification-class ticks + haptics every 500ms. */
    fun syncLobbyAlert(shouldRun: Boolean) {
        if (!shouldRun) {
            lobbyTickJob?.cancel()
            lobbyTickJob = null
            lobbyTickPlayer?.stop()
            return
        }
        sync(false)
        val tickPlayer = lobbyTickPlayer ?: return
        if (lobbyTickJob?.isActive == true) return
        lobbyTickJob = scope.launch {
            try {
                val ctx = appContext
                if (ctx != null && lobbyAlertSoundsAudible(ctx)) {
                    tickPlayer.playReadyTick()
                }
                while (isActive) {
                    if (ctx != null) {
                        if (lobbyAlertSoundsAudible(ctx)) {
                            tickPlayer.playTick()
                        }
                        pulseLobbyAlertHapticIfAllowed(ctx)
                    }
                    delay(500)
                }
            } finally {
                tickPlayer.stop()
            }
        }
    }

    /** Keeps lobby/match clock feedback while backgrounded; Compose stops receiving match updates there. */
    fun syncFromSessionWhenBackground(context: Context) {
        initialize(context)
        if (PreGameLobbySoundPolicy.shouldRunMatchFoundLobbyAlert(context)) {
            syncLobbyAlert(true)
            return
        }
        if (!lobbyTicksActive()) {
            syncLobbyAlert(false)
        }
        if (AppForegroundTracker.isInForeground) return
        sync(MatchClockSoundPolicy.shouldRunMatchClock(context))
    }
}
