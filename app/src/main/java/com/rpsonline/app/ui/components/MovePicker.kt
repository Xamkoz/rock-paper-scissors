package com.rpsonline.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rpsonline.app.data.model.Move

@Composable
fun MovePicker(
    isSubmitting: Boolean,
    onMove: (Move) -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (isSubmitting) {
        CircularProgressIndicator(modifier = modifier)
        return
    }

    val spacing = if (compact) 6.dp else 10.dp
    val cardMinHeight = if (compact) 76.dp else 96.dp
    val iconSize = if (compact) 28.dp else 36.dp

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Move.entries.forEach { move ->
            MoveChoiceCard(
                move = move,
                onClick = { onMove(move) },
                compact = compact,
                minHeight = cardMinHeight,
                iconSize = iconSize,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MoveChoiceCard(
    move: Move,
    onClick: () -> Unit,
    compact: Boolean,
    minHeight: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val style = moveCardStyle(move)
    val shape = RoundedCornerShape(if (compact) 14.dp else 16.dp)

    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = minHeight)
            .fillMaxWidth(),
        shape = shape,
        color = style.containerColor,
        contentColor = style.contentColor,
        border = BorderStroke(1.dp, style.borderColor),
        shadowElevation = if (compact) 0.dp else 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = if (compact) 6.dp else 8.dp,
                vertical = if (compact) 10.dp else 14.dp,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = move.icon,
                contentDescription = move.label,
                modifier = Modifier.size(iconSize),
                tint = style.iconTint,
            )
            Text(
                text = move.label,
                style = if (compact) {
                    MaterialTheme.typography.labelLarge
                } else {
                    MaterialTheme.typography.titleSmall
                },
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = if (compact) 6.dp else 8.dp),
            )
        }
    }
}

private val Move.icon: ImageVector
    get() = when (this) {
        Move.ROCK -> Icons.Default.Landscape
        Move.PAPER -> Icons.Default.Description
        Move.SCISSORS -> Icons.Default.ContentCut
    }

private data class MoveCardStyle(
    val containerColor: Color,
    val contentColor: Color,
    val iconTint: Color,
    val borderColor: Color,
)

@Composable
private fun moveCardStyle(move: Move): MoveCardStyle {
    val scheme = MaterialTheme.colorScheme
    return when (move) {
        Move.ROCK -> MoveCardStyle(
            containerColor = scheme.surfaceContainerHighest,
            contentColor = scheme.onSurface,
            iconTint = scheme.onSurfaceVariant,
            borderColor = scheme.outline.copy(alpha = 0.6f),
        )
        Move.PAPER -> MoveCardStyle(
            containerColor = scheme.primaryContainer,
            contentColor = scheme.onPrimaryContainer,
            iconTint = scheme.primary,
            borderColor = scheme.primary.copy(alpha = 0.35f),
        )
        Move.SCISSORS -> MoveCardStyle(
            containerColor = scheme.tertiaryContainer,
            contentColor = scheme.onTertiaryContainer,
            iconTint = scheme.tertiary,
            borderColor = scheme.tertiary.copy(alpha = 0.35f),
        )
    }
}
