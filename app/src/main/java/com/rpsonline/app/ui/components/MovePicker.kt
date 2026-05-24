package com.rpsonline.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rpsonline.app.R
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
    val cardHeight = if (compact) 72.dp else 84.dp

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Move.entries.forEach { move ->
            MoveChoiceCard(
                move = move,
                onClick = { onMove(move) },
                compact = compact,
                cardHeight = cardHeight,
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
    cardHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val style = moveCardStyle(move)
    val shape = RoundedCornerShape(if (compact) 14.dp else 16.dp)
    val imageScale = if (compact) 1.35f else 1.45f

    Surface(
        onClick = onClick,
        modifier = modifier
            .height(cardHeight)
            .fillMaxWidth(),
        shape = shape,
        color = style.containerColor,
        contentColor = style.contentColor,
        border = BorderStroke(1.dp, style.borderColor),
        shadowElevation = if (compact) 0.dp else 1.dp,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val imageSize = maxWidth * imageScale
            Image(
                painter = painterResource(move.iconRes),
                contentDescription = move.label,
                modifier = Modifier
                    .size(imageSize)
                    .align(Alignment.Center),
                contentScale = ContentScale.Fit,
            )
            Text(
                text = move.label,
                style = if (compact) {
                    MaterialTheme.typography.labelMedium
                } else {
                    MaterialTheme.typography.labelLarge
                },
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = if (compact) 4.dp else 6.dp),
            )
        }
    }
}

private val Move.iconRes: Int
    get() = when (this) {
        Move.ROCK -> R.drawable.ic_move_rock
        Move.PAPER -> R.drawable.ic_move_paper
        Move.SCISSORS -> R.drawable.ic_move_scissors
    }

private data class MoveCardStyle(
    val containerColor: Color,
    val contentColor: Color,
    val borderColor: Color,
)

@Composable
private fun moveCardStyle(move: Move): MoveCardStyle {
    val scheme = MaterialTheme.colorScheme
    return when (move) {
        Move.ROCK -> MoveCardStyle(
            containerColor = scheme.surfaceContainerHighest,
            contentColor = scheme.onSurface,
            borderColor = scheme.outline.copy(alpha = 0.6f),
        )
        Move.PAPER -> MoveCardStyle(
            containerColor = scheme.primaryContainer,
            contentColor = scheme.onPrimaryContainer,
            borderColor = scheme.primary.copy(alpha = 0.35f),
        )
        Move.SCISSORS -> MoveCardStyle(
            containerColor = scheme.tertiaryContainer,
            contentColor = scheme.onTertiaryContainer,
            borderColor = scheme.tertiary.copy(alpha = 0.35f),
        )
    }
}
