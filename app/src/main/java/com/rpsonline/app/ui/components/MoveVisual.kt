package com.rpsonline.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rpsonline.app.data.model.Move

/** Dimmed alpha for locked move picker, panel slots, and paused clocks. */
const val MoveSlotLockedAlpha = 0.42f

/** Minimum square move slot side (compact layouts). */
val MovePickerCardHeightCompact = 72.dp

/** Minimum square move slot side (regular layouts). */
val MovePickerCardHeight = 84.dp

/** Maximum square move slot side (compact layouts). */
val MoveSlotSquareCapCompact = 108.dp

/** Maximum square move slot side (regular layouts). */
val MoveSlotSquareCap = 128.dp

/** Square side for a move slot given its allocated width. */
fun moveSlotSquareSide(slotWidth: Dp, compact: Boolean): Dp {
    val cap = if (compact) MoveSlotSquareCapCompact else MoveSlotSquareCap
    val floor = if (compact) MovePickerCardHeightCompact else MovePickerCardHeight
    return minOf(slotWidth, cap).coerceAtLeast(minOf(floor, slotWidth))
}

/** Corner radius scaled to slot size so larger squares stay visibly rounded. */
fun moveSlotCornerRadius(squareSide: Dp, compact: Boolean): Dp {
    val fraction = if (compact) 0.10f else 0.11f
    val scaled = (squareSide.value * fraction).dp
    val floor = if (compact) 6.dp else 8.dp
    val cap = if (compact) 12.dp else 16.dp
    return scaled.coerceIn(floor, cap)
}

fun moveSlotShape(squareSide: Dp, compact: Boolean) =
    RoundedCornerShape(moveSlotCornerRadius(squareSide, compact))

/** Dimmed slot surface matching disabled [MoveIconCard]. */
@Composable
fun Modifier.moveSlotLockedSurface(
    squareSide: Dp,
    compact: Boolean,
): Modifier {
    val scheme = MaterialTheme.colorScheme
    val shape = moveSlotShape(squareSide, compact)
    return this
        .size(squareSide)
        .shadow(if (compact) 2.dp else 3.dp, shape)
        .clip(shape)
        .background(
            Brush.verticalGradient(
                colors = listOf(
                    scheme.surfaceContainerHigh,
                    scheme.surfaceContainer,
                    scheme.surfaceContainerLow,
                ),
            ),
        )
        .alpha(MoveSlotLockedAlpha)
        .border(
            BorderStroke(1.dp, scheme.outline.copy(alpha = 0.35f)),
            shape,
        )
}

/** Icon / marker size for move cards and panel slots — up to a square inset in the card. */
fun moveSlotContentSize(
    squareSide: Dp,
    compact: Boolean,
    large: Boolean = false,
): Dp {
    val fillFraction = when {
        large && compact -> 0.70f
        large -> 0.74f
        compact -> 0.82f
        else -> 0.86f
    }
    val floor = if (compact) 40.dp else 44.dp
    val fromSquare = (squareSide.value * fillFraction).dp
    return if (fromSquare < floor) floor else fromSquare
}

@Composable
fun MoveIconCard(
    move: Move,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    large: Boolean = false,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    squareSide: Dp? = null,
) {
    val style = moveCardStyle(move)
    if (squareSide != null) {
        MoveIconCardContent(
            move = move,
            style = style,
            squareSide = squareSide,
            compact = compact,
            large = large,
            enabled = enabled,
            selected = selected,
            onClick = onClick,
            modifier = modifier,
        )
        return
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val side = moveSlotSquareSide(maxWidth, compact)
        MoveIconCardContent(
            move = move,
            style = style,
            squareSide = side,
            compact = compact,
            large = large,
            enabled = enabled,
            selected = selected,
            onClick = onClick,
            modifier = Modifier,
        )
    }
}

@Composable
private fun MoveIconCardContent(
    move: Move,
    style: MoveCardStyle,
    squareSide: Dp,
    compact: Boolean,
    large: Boolean,
    enabled: Boolean,
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier,
) {
    val shape = moveSlotShape(squareSide, compact)
    val borderWidth = if (selected) 2.dp else 1.dp
    val borderColor = when {
        !enabled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        selected -> style.borderColor
        else -> style.borderColor.copy(alpha = 0.75f)
    }
    val iconSize = moveSlotContentSize(squareSide, compact, large)

    Box(
        modifier = modifier
            .size(squareSide)
            .alpha(if (enabled) 1f else MoveSlotLockedAlpha)
            .shadow(if (compact) 2.dp else 3.dp, shape)
            .clip(shape)
            .background(style.gradient)
            .border(BorderStroke(borderWidth, borderColor), shape)
            .then(
                if (enabled && onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = move.icon,
            contentDescription = move.label,
            tint = style.labelColor,
            modifier = Modifier.size(iconSize),
        )
    }
}

val Move.icon: ImageVector
    get() = when (this) {
        Move.ROCK -> Icons.Default.Landscape
        Move.PAPER -> Icons.Default.Description
        Move.SCISSORS -> Icons.Default.ContentCut
    }

data class MoveCardStyle(
    val gradient: Brush,
    val labelColor: Color,
    val borderColor: Color,
)

/** Accent fill for compact indicators (e.g. match win bars) — matches move picker labels. */
@Composable
fun moveBarFillColor(move: Move): Color = moveCardStyle(move).labelColor

@Composable
fun moveCardStyle(move: Move): MoveCardStyle {
    val scheme = MaterialTheme.colorScheme
    return when (move) {
        Move.ROCK -> MoveCardStyle(
            gradient = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF4A5568),
                    Color(0xFF2A3344),
                    Color(0xFF12182A),
                ),
            ),
            labelColor = scheme.primary,
            borderColor = scheme.primary.copy(alpha = 0.75f),
        )
        Move.PAPER -> MoveCardStyle(
            gradient = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF5A2868),
                    Color(0xFF3A1848),
                    Color(0xFF1A0828),
                ),
            ),
            labelColor = scheme.secondary,
            borderColor = scheme.secondary.copy(alpha = 0.75f),
        )
        Move.SCISSORS -> MoveCardStyle(
            gradient = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF6A5A10),
                    Color(0xFF4A3A08),
                    Color(0xFF2A2000),
                ),
            ),
            labelColor = scheme.tertiary,
            borderColor = scheme.tertiary.copy(alpha = 0.75f),
        )
    }
}
