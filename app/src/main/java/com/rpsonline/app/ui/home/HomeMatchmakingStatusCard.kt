package com.rpsonline.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rpsonline.app.R
import com.rpsonline.app.ui.components.RpsCard
import com.rpsonline.app.ui.util.formatQueueTime

private val HomeMatchmakingCardVerticalPadding = 16.dp
private val HomeMatchmakingCardSlotSpacing = 4.dp

private data class HomeMatchmakingCardMetrics(
    val labelSlotHeight: Dp,
    val primarySlotHeight: Dp,
    val subtitleSlotHeight: Dp,
    val cardHeight: Dp,
)

@Composable
private fun rememberHomeMatchmakingCardMetrics(): HomeMatchmakingCardMetrics {
    val labelStyle = MaterialTheme.typography.labelLarge
    val subtitleStyle = MaterialTheme.typography.bodySmall
    val timerTypography = MaterialTheme.typography.headlineMedium
    val timerStyle = timerTypography.copy(fontWeight = FontWeight.Bold)
    val bodyMedium = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val labelInQueue = stringResource(R.string.in_queue)
    val labelMatchFound = stringResource(R.string.match_found)
    val subtitleFinding = stringResource(R.string.finding_opponent)
    val primaryTimer = formatQueueTime(5_999)
    val primaryOpening = stringResource(R.string.opening_game)
    val primaryFindMatch = stringResource(R.string.find_match)
    val primarySending = stringResource(R.string.communicating_to_server)

    return remember(
        labelStyle,
        subtitleStyle,
        timerStyle,
        bodyMedium,
        labelInQueue,
        labelMatchFound,
        subtitleFinding,
        primaryTimer,
        primaryOpening,
        primaryFindMatch,
        primarySending,
        density,
    ) {
        fun measureHeight(text: String, style: TextStyle, maxLines: Int = 1): Int =
            textMeasurer.measure(
                text = text,
                style = style,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            ).size.height

        val labelPx = maxOf(
            measureHeight(labelInQueue, labelStyle),
            measureHeight(labelMatchFound, labelStyle),
        )
        val subtitlePx = measureHeight(subtitleFinding, subtitleStyle)
        val primaryPx = maxOf(
            measureHeight(primaryTimer, timerStyle),
            measureHeight(primaryOpening, timerStyle, maxLines = 2),
            measureHeight(primaryFindMatch, timerStyle),
            measureHeight(primarySending, bodyMedium, maxLines = 2),
        )

        val labelSlot = with(density) { labelPx.toDp() }
        val primarySlot = with(density) { primaryPx.toDp() }
        val subtitleSlot = with(density) { subtitlePx.toDp() }
        val cardHeight = HomeMatchmakingCardVerticalPadding * 2 +
            labelSlot +
            HomeMatchmakingCardSlotSpacing +
            primarySlot +
            HomeMatchmakingCardSlotSpacing +
            subtitleSlot

        HomeMatchmakingCardMetrics(
            labelSlotHeight = labelSlot,
            primarySlotHeight = primarySlot,
            subtitleSlotHeight = subtitleSlot,
            cardHeight = cardHeight,
        )
    }
}

/**
 * Shared matchmaking status card layout (Find Match, in-queue timer, match found).
 * Three fixed-height slots keep the card size identical across every state.
 */
@Composable
fun HomeMatchmakingStatusCard(
    label: String?,
    primary: String,
    primaryStyle: HomeMatchmakingPrimaryStyle,
    subtitle: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val metrics = rememberHomeMatchmakingCardMetrics()
    val contentAlpha = if (enabled) 1f else 0.38f
    RpsCard(
        modifier = modifier
            .fillMaxWidth()
            .height(metrics.cardHeight)
            .alpha(contentAlpha),
        onClick = if (enabled) onClick else null,
        containerColor = scheme.primaryContainer.copy(alpha = 0.94f),
        borderColor = scheme.primary.copy(alpha = 0.55f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 20.dp,
                    vertical = HomeMatchmakingCardVerticalPadding,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(HomeMatchmakingCardSlotSpacing),
        ) {
            HomeMatchmakingStatusTextSlot(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onPrimaryContainer.copy(alpha = 0.85f),
                slotHeight = metrics.labelSlotHeight,
                maxLines = 1,
            )
            HomeMatchmakingStatusPrimarySlot(
                text = primary,
                style = primaryStyle,
                slotHeight = metrics.primarySlotHeight,
            )
            HomeMatchmakingStatusTextSlot(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onPrimaryContainer.copy(alpha = 0.75f),
                slotHeight = metrics.subtitleSlotHeight,
                maxLines = 1,
            )
        }
    }
}

enum class HomeMatchmakingPrimaryStyle {
    Timer,
    Body,
    ActionTitle,
}

@Composable
fun HomeFindMatchActionCard(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    HomeMatchmakingStatusCard(
        label = null,
        primary = stringResource(R.string.find_match),
        primaryStyle = HomeMatchmakingPrimaryStyle.ActionTitle,
        subtitle = null,
        modifier = modifier,
        onClick = onClick,
        enabled = enabled,
    )
}

@Composable
private fun HomeMatchmakingStatusPrimarySlot(
    text: String,
    style: HomeMatchmakingPrimaryStyle,
    slotHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val primaryColor = MaterialTheme.colorScheme.onPrimaryContainer
    val timerTypography = MaterialTheme.typography.headlineMedium

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(slotHeight),
        contentAlignment = Alignment.Center,
    ) {
        when (style) {
            HomeMatchmakingPrimaryStyle.Body -> Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = primaryColor,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            HomeMatchmakingPrimaryStyle.Timer,
            HomeMatchmakingPrimaryStyle.ActionTitle,
            -> Text(
                text = text,
                style = timerTypography,
                fontWeight = FontWeight.Bold,
                color = primaryColor,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Subtitle row with the same locked height as [HomeMatchmakingStatusCard]. */
@Composable
internal fun HomeMatchmakingStatusSubtitleSlot(
    text: String?,
    modifier: Modifier = Modifier,
) {
    val metrics = rememberHomeMatchmakingCardMetrics()
    val scheme = MaterialTheme.colorScheme
    HomeMatchmakingStatusTextSlot(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onPrimaryContainer.copy(alpha = 0.75f),
        slotHeight = metrics.subtitleSlotHeight,
        maxLines = 1,
        modifier = modifier,
    )
}

@Composable
private fun HomeMatchmakingStatusTextSlot(
    text: String?,
    style: TextStyle,
    color: Color,
    slotHeight: Dp,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(slotHeight),
        contentAlignment = Alignment.Center,
    ) {
        if (text != null) {
            Text(
                text = text,
                style = style,
                color = color,
                textAlign = TextAlign.Center,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
