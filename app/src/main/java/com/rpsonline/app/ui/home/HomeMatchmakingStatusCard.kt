package com.rpsonline.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.rpsonline.app.R
import com.rpsonline.app.ui.components.RpsCard
import com.rpsonline.app.ui.util.formatQueueTime

/**
 * Shared matchmaking status card layout (queue timer, pre-game, Find Match CTA).
 * Fixed label / primary / subtitle slots keep every variant the same height.
 */
@Composable
fun HomeMatchmakingStatusCard(
    label: String?,
    labelReference: String,
    primary: String,
    primaryStyle: HomeMatchmakingPrimaryStyle,
    subtitle: String?,
    subtitleReference: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val contentAlpha = if (enabled) 1f else 0.38f
    RpsCard(
        modifier = modifier
            .fillMaxWidth()
            .alpha(contentAlpha),
        onClick = if (enabled) onClick else null,
        containerColor = scheme.primaryContainer.copy(alpha = 0.94f),
        borderColor = scheme.primary.copy(alpha = 0.55f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            HomeMatchmakingStatusLabelSlot(
                text = label,
                referenceText = labelReference,
            )
            HomeMatchmakingStatusPrimaryLine(
                text = primary,
                style = primaryStyle,
            )
            HomeMatchmakingStatusSubtitleSlot(
                text = subtitle,
                referenceText = subtitleReference,
            )
        }
    }
}

enum class HomeMatchmakingPrimaryStyle {
    Timer,
    Body,
}

@Composable
fun HomeFindMatchActionCard(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    HomeMatchmakingStatusCard(
        label = null,
        labelReference = stringResource(R.string.in_queue),
        primary = stringResource(R.string.find_match),
        primaryStyle = HomeMatchmakingPrimaryStyle.Timer,
        subtitle = null,
        subtitleReference = stringResource(R.string.finding_opponent),
        modifier = modifier,
        onClick = onClick,
        enabled = enabled,
    )
}

@Composable
private fun HomeMatchmakingStatusLabelSlot(
    text: String?,
    referenceText: String,
    modifier: Modifier = Modifier,
) {
    val style = MaterialTheme.typography.labelLarge
    val color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
    HomeMatchmakingStatusTextSlot(
        text = text,
        referenceText = referenceText,
        style = style,
        color = color,
        maxLines = 1,
        modifier = modifier,
    )
}

@Composable
private fun HomeMatchmakingStatusPrimaryLine(
    text: String,
    style: HomeMatchmakingPrimaryStyle,
    modifier: Modifier = Modifier,
) {
    val primaryColor = MaterialTheme.colorScheme.onPrimaryContainer
    val timerTypography = MaterialTheme.typography.headlineMedium
    val timerStyle = timerTypography.copy(fontWeight = FontWeight.Bold)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val timerReference = formatQueueTime(5_999)
    val openingReference = stringResource(R.string.opening_game)

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val maxWidthPx = constraints.maxWidth.coerceAtLeast(0)
        val slotHeightPx = maxOf(
            textMeasurer.measure(
                text = timerReference,
                style = timerStyle,
                maxLines = 1,
                constraints = Constraints(maxWidth = maxWidthPx),
            ).size.height,
            textMeasurer.measure(
                text = openingReference,
                style = timerStyle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                constraints = Constraints(maxWidth = maxWidthPx),
            ).size.height,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { slotHeightPx.toDp() }),
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
                HomeMatchmakingPrimaryStyle.Timer -> Text(
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
}

@Composable
internal fun HomeMatchmakingStatusSubtitleSlot(
    text: String?,
    referenceText: String,
    modifier: Modifier = Modifier,
) {
    val style = MaterialTheme.typography.bodySmall
    val color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
    HomeMatchmakingStatusTextSlot(
        text = text,
        referenceText = referenceText,
        style = style,
        color = color,
        maxLines = 1,
        modifier = modifier,
    )
}

@Composable
private fun HomeMatchmakingStatusTextSlot(
    text: String?,
    referenceText: String,
    style: TextStyle,
    color: Color,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val slotHeightPx = textMeasurer.measure(
        text = referenceText,
        style = style,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    ).size.height

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(with(density) { slotHeightPx.toDp() }),
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
