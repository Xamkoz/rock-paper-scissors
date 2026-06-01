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

import com.rpsonline.app.data.model.MatchStatus

import com.rpsonline.app.data.model.Move

import com.rpsonline.app.data.model.RoundResult

import com.rpsonline.app.ui.LocalClockSoundMuted

import kotlinx.coroutines.delay



fun roundResolutionRepetitions(resolved: RoundResult, userId: String): Int =

    when (resolved.winner) {

        "tie" -> 2

        userId -> 3

        else -> 1

    }



/**

 * Plays move sounds when a round resolves.

 */

@Composable

fun RoundResolutionFeedbackEffect(

    activeMatch: Match?,

    userId: String?,

    pulseNotifier: RoundResolutionPulseNotifier,

) {

    val context = LocalContext.current

    val muted = LocalClockSoundMuted.current

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

        muted,

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



        val myChoice = if (userId == match.player1) resolved.player1Choice else resolved.player2Choice

        val move = Move.fromString(myChoice) ?: run {

            pulseNotifier.markFeedbackComplete(resolved, match.id)

            return@LaunchedEffect

        }

        val repetitions = roundResolutionRepetitions(resolved, userId)

        val matchId = match.id

        val matchEnded = match.status == MatchStatus.COMPLETED || match.status == MatchStatus.ABANDONED

        val playbackKey = feedbackKey



        pulseNotifier.beginFeedback(playbackKey)

        var finished = false

        try {

            repeat(repetitions) { index ->
                if (muted) {

                    delay(ROUND_RESOLUTION_MUTED_BEAT_MS)

                } else {

                    moveSoundPlayer.playOnce(move)

                }

                if (index < repetitions - 1) {

                    delay(ROUND_RESOLUTION_BURST_GAP_MS)

                }

            }

            finished = true

        } finally {

            pulseNotifier.endFeedback(

                resolved = resolved,

                matchId = matchId,

                key = playbackKey,

                success = finished || matchEnded,

            )

            if (finished && activeMatch?.id != matchId) {

                feedbackMatch = null

            }

        }

    }

}


