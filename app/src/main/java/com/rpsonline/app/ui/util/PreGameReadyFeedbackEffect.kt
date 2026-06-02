package com.rpsonline.app.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import com.rpsonline.app.ui.LocalClockSoundMuted
import com.rpsonline.app.viewmodel.PreGameSyncUiState
import kotlinx.coroutines.delay

/** Tick sound + haptic when you or the opponent becomes ready in the pre-game lobby. */
@Composable
fun PreGameReadyFeedbackEffect(preGameSync: PreGameSyncUiState?) {
    val muted = LocalClockSoundMuted.current
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val tickPlayer = remember(context) { ClockTickPlayer(context) }

    var trackedMatchId by remember { mutableStateOf<String?>(null) }
    var lastMyReady by remember { mutableStateOf(false) }
    var lastOpponentReady by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { tickPlayer.release() }
    }

    LaunchedEffect(preGameSync?.matchId, preGameSync?.myReady, preGameSync?.opponentReady) {
        val sync = preGameSync
        if (sync == null) {
            trackedMatchId = null
            lastMyReady = false
            lastOpponentReady = false
            return@LaunchedEffect
        }

        if (sync.matchId != trackedMatchId) {
            trackedMatchId = sync.matchId
            lastMyReady = sync.myReady
            lastOpponentReady = sync.opponentReady
            return@LaunchedEffect
        }

        val myBecameReady = !lastMyReady && sync.myReady
        val opponentBecameReady = !lastOpponentReady && sync.opponentReady

        if (myBecameReady) {
            playReadyFeedback(tickPlayer, haptic, muted)
        }
        if (opponentBecameReady) {
            if (myBecameReady) delay(READY_TICK_GAP_MS)
            playReadyFeedback(tickPlayer, haptic, muted)
        }

        lastMyReady = sync.myReady
        lastOpponentReady = sync.opponentReady
    }
}

private fun playReadyFeedback(
    tickPlayer: ClockTickPlayer,
    haptic: HapticFeedback,
    muted: Boolean,
) {
    if (!muted) {
        tickPlayer.playReadyTick()
    }
    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
}

private const val READY_TICK_GAP_MS = 120L
