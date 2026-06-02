package com.rpsonline.app.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.rpsonline.app.R
import com.rpsonline.app.ui.util.formatQueueTimeMmSs

/** How the top-bar online player count digits should render. */
sealed interface TopBarOnlineCountDisplay {
    data object Loading : TopBarOnlineCountDisplay
    data object Offline : TopBarOnlineCountDisplay
    data class Value(val count: Int) : TopBarOnlineCountDisplay
}

@Composable
fun TopBarSegmentedQueueIndicator(
    onlineCount: TopBarOnlineCountDisplay,
    inMatch: Boolean,
    inQueue: Boolean,
    elapsedSeconds: Long,
    inLobby: Boolean = false,
    playerClockStopped: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val timerDescription = when {
        inMatch -> stringResource(
            R.string.in_match_with_time,
            formatQueueTimeMmSs(elapsedSeconds),
        )
        inLobby -> stringResource(
            R.string.match_found_with_time,
            formatQueueTimeMmSs(elapsedSeconds),
        )
        inQueue -> stringResource(
            R.string.in_queue_with_time,
            formatQueueTimeMmSs(elapsedSeconds),
        )
        else -> stringResource(R.string.queue_timer_idle)
    }
    val onlineDescription = when (onlineCount) {
        TopBarOnlineCountDisplay.Loading -> stringResource(R.string.players_online_loading)
        TopBarOnlineCountDisplay.Offline -> stringResource(R.string.connection_indicator_offline)
        is TopBarOnlineCountDisplay.Value -> stringResource(R.string.players_online_count, onlineCount.count)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { clip = false },
    ) {
        val digitWidth = computeTopBarStatusDigitWidth(maxWidth)
        TopBarSegmentedStatusRow(
            onlineCount = onlineCount,
            inMatch = inMatch,
            inQueue = inQueue,
            inLobby = inLobby,
            elapsedSeconds = elapsedSeconds,
            playerClockStopped = playerClockStopped,
            digitWidth = digitWidth,
            digitHeight = TopBarSegmentedDigitHeight,
            modifier = Modifier
                .height(SegmentedDisplayHeight)
                .semantics {
                    contentDescription = "$onlineDescription. $timerDescription"
                },
        )
    }
}

/** @deprecated Use [TopBarSegmentedQueueIndicator] with [onlineCount]. */
@Composable
fun InQueueTopBarIndicator(
    elapsedSeconds: Long,
    modifier: Modifier = Modifier,
) {
    TopBarSegmentedQueueIndicator(
        onlineCount = TopBarOnlineCountDisplay.Loading,
        inMatch = false,
        inQueue = true,
        elapsedSeconds = elapsedSeconds,
        modifier = modifier,
    )
}
