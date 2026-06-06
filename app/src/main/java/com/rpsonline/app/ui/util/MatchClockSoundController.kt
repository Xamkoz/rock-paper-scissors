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
    private var lastLobbyAlertStoppedElapsedMs: Long? = null

    private fun lobbyStoppedRecently(): Boolean {
        val stoppedAt = lastLobbyAlertStoppedElapsedMs ?: return false
        return SystemClock.elapsedRealtime() - stoppedAt < LOBBY_TO_CLOCK_HANDOFF_MS
    }

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
        val handoffFromLobby = lobbyTicksActive()
        syncLobbyAlert(false)
        val tickPlayer = player ?: return
        if (tickJob?.isActive == true) return
        hapticAnchorElapsedMs = SystemClock.elapsedRealtime()
        tickJob = scope.launch {
            try {
                if (handoffFromLobby && lobbyStoppedRecently()) {
                    val stoppedAt = lastLobbyAlertStoppedElapsedMs ?: SystemClock.elapsedRealtime()
                    val elapsed = SystemClock.elapsedRealtime() - stoppedAt
                    val waitMs = (LOBBY_ALERT_TICK_MS - (elapsed % LOBBY_ALERT_TICK_MS))
                        .coerceIn(1L, LOBBY_ALERT_TICK_MS)
                    delay(waitMs)
                }
                delay(MatchClockSoundPolicy.TICK_AFTER_CLOCK_RUNNING_MS)
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

    /**
     * Match-found alert: notification-class tick + haptic on anchor-aligned 500ms beats.
     * Uses [JoinMatchNotificationState.lobbyAlertStartedAtMs] so haptics stay in phase with the
     * segmented notification timer (and are not drifted by coroutine work time).
     */
    fun syncLobbyAlert(shouldRun: Boolean) {
        if (!shouldRun) {
            if (lobbyTickJob?.isActive == true) {
                lastLobbyAlertStoppedElapsedMs = SystemClock.elapsedRealtime()
            }
            lobbyTickJob?.cancel()
            lobbyTickJob = null
            lobbyTickPlayer?.stop()
            return
        }
        if (lobbyTickJob?.isActive == true) return
        sync(false)
        val tickPlayer = lobbyTickPlayer ?: return
        lobbyTickJob = scope.launch {
            try {
                val ctx = appContext
                val anchorMs = JoinMatchNotificationState.lobbyAlertStartedAtMs()
                    ?: System.currentTimeMillis()
                var beatIndex = currentLobbyAlertBeatIndex(anchorMs)
                while (isActive) {
                    val waitMs = delayMsUntilNextLobbyAlertBeat(anchorMs, beatIndex)
                    if (waitMs > 0L) delay(waitMs)
                    if (!isActive) break
                    if (ctx != null) {
                        if (lobbyAlertSoundsAudible(ctx)) {
                            tickPlayer.playTick()
                        }
                        pulseLobbyAlertHapticIfAllowed(ctx)
                    }
                    beatIndex++
                }
            } finally {
                tickPlayer.stop()
            }
        }
    }

    /**
     * Picks match-found lobby alert ticks or in-match clock ticks, never both.
     * Foreground UI drives ticks via Compose; background uses the foreground service loop.
     */
    fun reconcileSession(context: Context) {
        initialize(context)
        when {
            PreGameLobbySoundPolicy.shouldRunMatchFoundLobbyAlert(context) -> syncLobbyAlert(true)
            MatchClockSoundPolicy.shouldRunMatchClock(context) -> sync(true)
            else -> {
                syncLobbyAlert(false)
                sync(false)
            }
        }
    }

    /** Background-only; foreground tick sync is owned by Compose effects. */
    fun syncFromSessionWhenBackground(context: Context) {
        if (AppForegroundTracker.isInForeground) return
        reconcileSession(context)
    }
}

private const val LOBBY_TO_CLOCK_HANDOFF_MS = 450L
