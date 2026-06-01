package com.rpsonline.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rpsonline.app.R
import com.rpsonline.app.data.model.Move
import com.rpsonline.app.data.model.RoundEndReason
import com.rpsonline.app.data.model.RoundRecap
import com.rpsonline.app.ui.leaderboard.recapMoveTimeColor

private val RecapMoveIconSize = 14.dp
private val RecapLayoutCompactBreakpoint = 360.dp
private val RecapRowColumnSpacing = 4.dp
private val RecapColRound = 34.dp
private val RecapColTime = 38.dp
private val RecapColChoices = 68.dp
private val RecapColOutcome = 40.dp

private fun recapRowTotalWidth(): Dp =
    RecapColRound + RecapColTime * 2 + RecapColChoices + RecapColOutcome + RecapRowColumnSpacing * 4

fun formatRecapMoveMs(ms: Int?): String {
    if (ms == null) return "—"
    val seconds = ((ms + 500) / 1000).coerceAtLeast(0)
    return "${seconds}s"
}

@Composable
fun MatchRecapCard(
    recaps: List<RoundRecap>,
    title: String? = "Match recap",
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
) {
    if (recaps.isEmpty()) return
    if (embedded) {
        MatchRecapContent(
            recaps = recaps,
            title = title,
            embedded = true,
            modifier = modifier.fillMaxWidth(),
        )
    } else {
        RpsCard(modifier = modifier.fillMaxWidth()) {
            MatchRecapContent(
                recaps = recaps,
                title = title,
                embedded = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun MatchRecapContent(
    recaps: List<RoundRecap>,
    title: String?,
    embedded: Boolean,
    modifier: Modifier = Modifier,
) {
    val displayRecaps = recaps.asReversed()
    BoxWithConstraints(modifier = modifier) {
        val horizontalPadding = when {
            embedded -> 0.dp
            maxWidth < RecapLayoutCompactBreakpoint -> 8.dp
            else -> 10.dp
        }
        val verticalPadding = if (embedded) 4.dp else 8.dp
        val contentWidth = maxWidth - horizontalPadding * 2
        val compact = contentWidth < RecapLayoutCompactBreakpoint
        val useFixedColumns = contentWidth < recapRowTotalWidth()
        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                modifier = if (useFixedColumns) {
                    Modifier.horizontalScroll(scrollState)
                } else {
                    Modifier.fillMaxWidth()
                },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                displayRecaps.forEachIndexed { index, recap ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    val label = roundRecapLabel(recap.roundNumber, compact)
                    if (useFixedColumns) {
                        RoundRecapRowFixed(
                            roundLabel = label,
                            recap = recap,
                            compact = compact,
                        )
                    } else {
                        RoundRecapRow(
                            roundLabel = label,
                            recap = recap,
                            compact = compact,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RoundRecapRow(
    roundLabel: String,
    recap: RoundRecap,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(RecapRowColumnSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundRecapCells(
            roundLabel = roundLabel,
            recap = recap,
            compact = compact,
            roundModifier = { Modifier.weight(1.1f) },
            myTimeModifier = { Modifier.weight(0.85f) },
            choicesModifier = { Modifier.weight(1.35f) },
            oppTimeModifier = { Modifier.weight(0.85f) },
            outcomeModifier = { Modifier.weight(0.9f) },
        )
    }
}

/** Fixed-width row for narrow screens inside [horizontalScroll] (weights do not work there). */
@Composable
private fun RoundRecapRowFixed(
    roundLabel: String,
    recap: RoundRecap,
    compact: Boolean,
) {
    Row(
        modifier = Modifier.width(recapRowTotalWidth()),
        horizontalArrangement = Arrangement.spacedBy(RecapRowColumnSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundRecapCells(
            roundLabel = roundLabel,
            recap = recap,
            compact = compact,
            roundModifier = { Modifier.width(RecapColRound) },
            myTimeModifier = { Modifier.width(RecapColTime) },
            choicesModifier = { Modifier.width(RecapColChoices) },
            oppTimeModifier = { Modifier.width(RecapColTime) },
            outcomeModifier = { Modifier.width(RecapColOutcome) },
        )
    }
}

@Composable
private fun RoundRecapCells(
    roundLabel: String,
    recap: RoundRecap,
    compact: Boolean,
    roundModifier: () -> Modifier,
    myTimeModifier: () -> Modifier,
    choicesModifier: () -> Modifier,
    oppTimeModifier: () -> Modifier,
    outcomeModifier: () -> Modifier,
) {
    RecapCell(
        modifier = roundModifier(),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = roundLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    RecapCell(
        modifier = myTimeModifier(),
        contentAlignment = Alignment.Center,
    ) {
        RecapMoveTimeText(
            moveMs = recap.myMoveMs,
            cancelled = recap.isCancelled,
            compact = compact,
        )
    }
    RecapCell(
        modifier = choicesModifier(),
        contentAlignment = Alignment.Center,
    ) {
        RoundChoicesLine(recap = recap, compact = compact)
    }
    RecapCell(
        modifier = oppTimeModifier(),
        contentAlignment = Alignment.Center,
    ) {
        RecapMoveTimeText(
            moveMs = recap.opponentMoveMs,
            cancelled = recap.isCancelled,
            compact = compact,
        )
    }
    RecapCell(
        modifier = outcomeModifier(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(
            text = recapOutcomeColumnLabel(recap),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = recapOutcomeColor(recap),
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RecapCell(
    contentAlignment: Alignment,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier,
        contentAlignment = contentAlignment,
    ) {
        content()
    }
}

@Composable
private fun RecapMoveTimeText(
    moveMs: Int?,
    cancelled: Boolean,
    compact: Boolean,
) {
    Text(
        text = formatRecapMoveMs(moveMs),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = if (cancelled) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            recapMoveTimeColor(moveMs)
        },
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun RoundChoicesLine(recap: RoundRecap, compact: Boolean) {
    if (recap.isCancelled && recap.myChoice == null && recap.opponentChoice == null) {
        Text(
            text = "—",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    if (recap.isDraw && recap.myChoice == null && recap.opponentChoice == null) {
        Text(
            text = stringResource(R.string.replay),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val lineColor = recapOutcomeColor(recap)
        RecapMoveIcon(
            choice = recap.myChoice,
            color = lineColor,
            iconSize = if (compact) 12.dp else RecapMoveIconSize,
        )
        Text(
            text = recapChoicesSeparator(recap),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = lineColor,
        )
        RecapMoveIcon(
            choice = recap.opponentChoice,
            color = lineColor,
            iconSize = if (compact) 12.dp else RecapMoveIconSize,
        )
    }
}

private fun roundRecapLabel(roundNumber: Int, compact: Boolean): String =
    if (compact) "R$roundNumber" else "Rd$roundNumber"

private fun recapChoicesSeparator(recap: RoundRecap): String = when {
    recap.isCancelled -> "·"
    recap.isDraw || recap.won == null -> "="
    recap.won -> ">"
    else -> "<"
}

@Composable
private fun RecapMoveIcon(
    choice: String?,
    color: Color,
    iconSize: Dp = RecapMoveIconSize,
) {
    val move = Move.fromString(choice)
    val icon = moveIcon(move)
    if (icon == null) {
        Text(
            text = "—",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
        return
    }
    Icon(
        imageVector = icon,
        contentDescription = move?.label,
        tint = color,
        modifier = Modifier.size(iconSize),
    )
}

private fun moveIcon(move: Move?): ImageVector? = when (move) {
    Move.ROCK -> Icons.Default.Landscape
    Move.PAPER -> Icons.Default.Description
    Move.SCISSORS -> Icons.Default.ContentCut
    null -> null
}

fun recapOutcomeLabel(recap: RoundRecap): String = when {
    recap.isCancelled -> "Cancelled"
    recap.isDraw || recap.won == null -> "Draw"
    recap.opponentTimedOut -> recapWinTimeoutLabel(recap.endReason)
    recap.iTimedOut -> recapLossTimeoutLabel(recap.endReason)
    recap.won -> "Win"
    else -> "Loss"
}

private fun recapOutcomeColumnLabel(recap: RoundRecap): String = when {
    recap.isCancelled -> "—"
    recap.isDraw || recap.won == null -> "Draw"
    recap.opponentTimedOut -> recapWinTimeoutColumnLabel(recap.endReason)
    recap.iTimedOut -> recapLossTimeoutColumnLabel(recap.endReason)
    recap.won -> "Win"
    else -> "Loss"
}

private fun recapWinTimeoutLabel(reason: RoundEndReason?): String = when (reason) {
    RoundEndReason.CLOCK_TIMEOUT -> "Win (clock)"
    RoundEndReason.ROUND_TIMEOUT -> "Win (round)"
    else -> "Win (timeout)"
}

private fun recapLossTimeoutLabel(reason: RoundEndReason?): String = when (reason) {
    RoundEndReason.CLOCK_TIMEOUT -> "Loss (clock)"
    RoundEndReason.ROUND_TIMEOUT -> "Loss (round)"
    else -> "Loss (timeout)"
}

private fun recapWinTimeoutColumnLabel(reason: RoundEndReason?): String = when (reason) {
    RoundEndReason.CLOCK_TIMEOUT -> "W-CLK"
    RoundEndReason.ROUND_TIMEOUT -> "W-RND"
    else -> "W-TO"
}

private fun recapLossTimeoutColumnLabel(reason: RoundEndReason?): String = when (reason) {
    RoundEndReason.CLOCK_TIMEOUT -> "L-CLK"
    RoundEndReason.ROUND_TIMEOUT -> "L-RND"
    else -> "L-TO"
}

@Composable
fun recapOutcomeColor(recap: RoundRecap): Color = when {
    recap.isCancelled -> MaterialTheme.colorScheme.onSurfaceVariant
    recap.isDraw || recap.won == null -> MaterialTheme.colorScheme.tertiary
    recap.won -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.error
}
