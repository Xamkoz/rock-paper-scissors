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
    private var matchClockGeneration = 0
    private var activeMatchClockGeneration = 0
    private var lobbyAlertGeneration = 0
    private var activeLobbyAlertGeneration = 0

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

    private fun shouldRunMatchClockNow(): Boolean {
        val ctx = appContext ?: return false
        return MatchClockSoundPolicy.shouldRunMatchClock(ctx)
    }

    private fun shouldRunLobbyAlertNow(): Boolean {
        val ctx = appContext ?: return false
        return PreGameLobbySoundPolicy.shouldRunMatchFoundLobbyAlert(ctx)
    }

    private fun stopMatchClockFeedback() {
        if (!lobbyTicksActive()) {
            player?.stop()
            MatchClockHaptics.cancel()
            hapticAnchorElapsedMs = null
        }
    }

    private fun stopMatchClockTicks() {
        matchClockGeneration++
        tickJob?.cancel()
        tickJob = null
        stopMatchClockFeedback()
    }

    private fun stopLobbyAlertTicks() {
        if (lobbyTickJob?.isActive == true) {
            lastLobbyAlertStoppedElapsedMs = SystemClock.elapsedRealtime()
        }
        lobbyAlertGeneration++
        lobbyTickJob?.cancel()
        lobbyTickJob = null
        lobbyTickPlayer?.stop()
        MatchClockHaptics.cancel()
    }

    fun sync(shouldRun: Boolean) {
        if (!shouldRun) {
            stopMatchClockTicks()
            return
        }
        if (!shouldRunMatchClockNow()) {
            stopMatchClockTicks()
            return
        }
        val handoffFromLobby = lobbyTicksActive()
        syncLobbyAlert(false)
        val tickPlayer = player ?: return
        if (tickJob?.isActive == true && activeMatchClockGeneration == matchClockGeneration) {
            return
        }
        matchClockGeneration++
        activeMatchClockGeneration = matchClockGeneration
        val generation = matchClockGeneration
        tickJob?.cancel()
        tickJob = null
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
                while (isActive && generation == matchClockGeneration && shouldRunMatchClockNow()) {
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
                if (generation == matchClockGeneration) {
                    tickPlayer.stop()
                    hapticAnchorElapsedMs = null
                }
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
            stopLobbyAlertTicks()
            return
        }
        if (!shouldRunLobbyAlertNow()) {
            stopLobbyAlertTicks()
            return
        }
        if (lobbyTickJob?.isActive == true && activeLobbyAlertGeneration == lobbyAlertGeneration) {
            return
        }
        sync(false)
        val tickPlayer = lobbyTickPlayer ?: return
        lobbyAlertGeneration++
        activeLobbyAlertGeneration = lobbyAlertGeneration
        val generation = lobbyAlertGeneration
        lobbyTickJob?.cancel()
        lobbyTickJob = null
        lobbyTickJob = scope.launch {
            try {
                val ctx = appContext
                val anchorMs = JoinMatchNotificationState.lobbyAlertStartedAtMs()
                    ?: System.currentTimeMillis()
                var beatIndex = currentLobbyAlertBeatIndex(anchorMs)
                while (isActive && generation == lobbyAlertGeneration && shouldRunLobbyAlertNow()) {
                    val waitMs = delayMsUntilNextLobbyAlertBeat(anchorMs, beatIndex)
                    if (waitMs > 0L) delay(waitMs)
                    if (!isActive || generation != lobbyAlertGeneration || !shouldRunLobbyAlertNow()) {
                        break
                    }
                    if (ctx != null) {
                        if (lobbyAlertSoundsAudible(ctx)) {
                            tickPlayer.playTick()
                        }
                        pulseLobbyAlertHapticIfAllowed(ctx)
                    }
                    beatIndex++
                }
            } finally {
                if (generation == lobbyAlertGeneration) {
                    tickPlayer.stop()
                }
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
