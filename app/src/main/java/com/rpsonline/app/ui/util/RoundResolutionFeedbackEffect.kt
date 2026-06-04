package com.rpsonline.app.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.rpsonline.app.data.model.Match
import com.rpsonline.app.ui.LocalSoundFeedbackMode

/** Plays move-sound bursts (1/2/3) and one haptic when a round resolves. */
@Composable
fun RoundResolutionFeedbackEffect(
    activeMatch: Match?,
    userId: String?,
    pulseNotifier: RoundResolutionPulseNotifier,
) {
    val context = LocalContext.current
    val feedbackMode = LocalSoundFeedbackMode.current
    val moveSoundPlayer = remember(context) { MoveSoundPlayer(context) }

    var feedbackMatch by remember(userId) { mutableStateOf<Match?>(null) }
    val liveSessionGeneration = pulseNotifier.liveSessionGeneration

    SideEffect {
        if (activeMatch != null) {
            feedbackMatch = activeMatch
        }
    }

    val matchForFeedback = activeMatch ?: feedbackMatch
    val feedbackKey = matchForFeedback?.lastResolvedRound()?.let { resolved ->
        if (resolved.player1Choice == null || resolved.player2Choice == null) {
            null
        } else {
            roundResolutionKey(resolved, matchForFeedback.id)
        }
    }

    DisposableEffect(Unit) {
        onDispose { moveSoundPlayer.release() }
    }

    LaunchedEffect(userId) {
        feedbackMatch = null
    }

    LaunchedEffect(activeMatch?.id, feedbackKey, userId) {
        val match = matchForFeedback ?: return@LaunchedEffect
        if (userId == null || feedbackKey == null) return@LaunchedEffect
        if (pulseNotifier.isLiveMatch(match.id)) return@LaunchedEffect
        val resolved = match.lastResolvedRound() ?: return@LaunchedEffect
        pulseNotifier.baselineHistoricalRound(resolved, match.id)
    }

    LaunchedEffect(
        feedbackKey,
        userId,
        feedbackMode,
        matchForFeedback?.id,
        matchForFeedback?.status,
        liveSessionGeneration,
    ) {
        if (userId == null || feedbackKey == null) return@LaunchedEffect
        val match = matchForFeedback ?: return@LaunchedEffect
        if (!pulseNotifier.isLiveMatch(match.id)) return@LaunchedEffect

        val resolved = match.lastResolvedRound() ?: return@LaunchedEffect
        if (roundResolutionKey(resolved, match.id) != feedbackKey) return@LaunchedEffect
        if (pulseNotifier.shouldSkipFeedback(feedbackKey)) return@LaunchedEffect
        if (shouldDeferRoundResolutionFeedbackToMatchScreen(match, resolved)) return@LaunchedEffect

        playLiveRoundResolutionFeedback(
            context = context,
            match = match,
            resolved = resolved,
            userId = userId,
            mode = feedbackMode,
            moveSoundPlayer = moveSoundPlayer,
            pulseNotifier = pulseNotifier,
        )

        if (activeMatch?.id != match.id) {
            feedbackMatch = null
        }
    }
}
