package com.rpsonline.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rpsonline.app.data.model.Move

@Composable
fun MovePicker(
    enabled: Boolean,
    onMove: (Move) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    selectedMove: Move? = null,
    roundKey: Int? = null,
) {
    val spacing = if (compact) 6.dp else 10.dp
    val labelSpacing = if (compact) 4.dp else 6.dp
    var clickLocked by remember(roundKey) { mutableStateOf(false) }

    LaunchedEffect(enabled, roundKey) {
        if (enabled) clickLocked = false
    }

    val pickerEnabled = enabled && !clickLocked

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Move.entries.forEach { move ->
            MoveChoiceColumn(
                move = move,
                onClick = {
                    if (!pickerEnabled) return@MoveChoiceColumn
                    clickLocked = true
                    onMove(move)
                },
                enabled = pickerEnabled,
                selected = move == selectedMove,
                compact = compact,
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
    enabled: Boolean,
    selected: Boolean,
    compact: Boolean,
    labelSpacing: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val style = moveCardStyle(move)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MoveIconCard(
            move = move,
            compact = compact,
            enabled = enabled,
            selected = selected,
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
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
            color = style.labelColor.copy(alpha = if (enabled) 1f else MoveSlotLockedAlpha),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = labelSpacing),
        )
    }
}
