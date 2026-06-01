package com.rpsonline.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared height for home nav, back, and version footer row. */
val RpsOutlinedButtonHeight = 52.dp

/** Border width for [RpsOutlinedActionButton], header chips, and version footer. */
val RpsOutlinedBorderWidth = 2.dp

private val RpsCompactOutlinedButtonContentPadding = PaddingValues(
    horizontal = 12.dp,
    vertical = 4.dp,
)

/** Surface fill and stroke used by home outlined actions and info chips. */
@Stable
object RpsOutlinedSurfaceStyle {
    @Composable
    fun containerColor(): Color =
        MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.88f)

    @Composable
    fun borderColor(): Color =
        MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)

    @Composable
    fun contentColor(): Color = MaterialTheme.colorScheme.onSurface
}

/** Compact outlined button for the version footer (same shape, border, and colors as home actions). */
@Composable
fun RpsCompactOutlinedActionButton(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(RpsOutlinedBorderWidth, RpsOutlinedSurfaceStyle.borderColor()),
        contentPadding = RpsCompactOutlinedButtonContentPadding,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = RpsOutlinedSurfaceStyle.contentColor(),
            disabledContentColor = scheme.onSurface.copy(alpha = 0.38f),
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Primary call-to-action on home (Find Match, reconnect, etc.). */
val RpsHeroButtonHeight = 80.dp

private val RpsOutlinedButtonContentPadding = PaddingValues(
    horizontal = 12.dp,
    vertical = 4.dp,
)

private val RpsHeroButtonContentPadding = PaddingValues(
    horizontal = 20.dp,
    vertical = 10.dp,
)

/** Outlined action with profile-card corners; label uses most of the button area. */
@Composable
fun RpsOutlinedActionButton(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    height: Dp = RpsOutlinedButtonHeight,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(RpsOutlinedBorderWidth, RpsOutlinedSurfaceStyle.borderColor()),
        contentPadding = RpsOutlinedButtonContentPadding,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = scheme.onSurface,
        ),
    ) {
        RpsActionButtonLabel(text = text)
    }
}

/** Filled primary action; same size and corner radius as [RpsOutlinedActionButton]. */
@Composable
fun RpsPrimaryActionButton(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    height: Dp = RpsOutlinedButtonHeight,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        shape = MaterialTheme.shapes.medium,
        contentPadding = RpsOutlinedButtonContentPadding,
    ) {
        RpsActionButtonLabel(text = text)
    }
}

/** Large filled primary button with profile-card corners (e.g. Find Match). */
@Composable
fun RpsHeroPrimaryButton(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(RpsHeroButtonHeight),
        shape = MaterialTheme.shapes.medium,
        contentPadding = RpsHeroButtonContentPadding,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RpsActionButtonLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
