package com.rpsonline.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
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
    val labelSpacing = if (compact) 4.dp else 6.dp

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Move.entries.forEach { move ->
            MoveChoiceColumn(
                move = move,
                onClick = { onMove(move) },
                compact = compact,
                cardHeight = cardHeight,
                labelSpacing = labelSpacing,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MoveChoiceColumn(
    move: Move,
    onClick: () -> Unit,
    compact: Boolean,
    cardHeight: androidx.compose.ui.unit.Dp,
    labelSpacing: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val style = moveCardStyle(move)
    val shape = RoundedCornerShape(if (compact) 14.dp else 16.dp)
    val imageScale = if (compact) 1.35f else 1.45f

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(cardHeight)
                .shadow(if (compact) 2.dp else 3.dp, shape)
                .clip(shape)
                .background(style.gradient)
                .border(BorderStroke(1.dp, style.borderColor), shape)
                .clickable(onClick = onClick),
        ) {
            val imageSize = maxWidth * imageScale
            Image(
                painter = painterResource(move.iconRes),
                contentDescription = move.label,
                modifier = Modifier
                    .size(imageSize)
                    .align(Alignment.Center),
                contentScale = ContentScale.Fit,
            )
        }
        Text(
            text = move.label,
            style = if (compact) {
                MaterialTheme.typography.labelMedium
            } else {
                MaterialTheme.typography.labelLarge
            },
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            color = style.labelColor,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = labelSpacing),
        )
    }
}

private val Move.iconRes: Int
    get() = when (this) {
        Move.ROCK -> R.drawable.ic_move_rock
        Move.PAPER -> R.drawable.ic_move_paper
        Move.SCISSORS -> R.drawable.ic_move_scissors
    }

private data class MoveCardStyle(
    val gradient: Brush,
    val labelColor: Color,
    val borderColor: Color,
)

@Composable
private fun moveCardStyle(move: Move): MoveCardStyle {
    val scheme = MaterialTheme.colorScheme
    return when (move) {
        Move.ROCK -> MoveCardStyle(
            gradient = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF8E979E),
                    Color(0xFF5C656D),
                    Color(0xFF3A4248),
                ),
            ),
            labelColor = scheme.onBackground,
            borderColor = Color(0xFF9AA3AA).copy(alpha = 0.65f),
        )
        Move.PAPER -> MoveCardStyle(
            gradient = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFFFFF3D4),
                    Color(0xFFE8CF8A),
                    Color(0xFFC9A44E),
                ),
            ),
            labelColor = scheme.onBackground,
            borderColor = Color(0xFFE6C878).copy(alpha = 0.75f),
        )
        Move.SCISSORS -> MoveCardStyle(
            gradient = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFFFFE0B2),
                    Color(0xFFE6A85C),
                    Color(0xFFB8742E),
                ),
            ),
            labelColor = scheme.onBackground,
            borderColor = Color(0xFFF0B56A).copy(alpha = 0.75f),
        )
    }
}
