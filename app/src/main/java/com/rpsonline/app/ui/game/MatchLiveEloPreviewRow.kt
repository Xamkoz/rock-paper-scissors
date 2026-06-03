package com.rpsonline.app.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.rpsonline.app.domain.LiveEloPreview
import com.rpsonline.app.ui.components.formatEloDelta
import com.rpsonline.app.ui.components.profileStatValueColor

@Composable
fun MatchLiveEloPreviewRow(
    preview: LiveEloPreview,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    colorDeltasBySign: Boolean = false,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "${preview.myElo} ",
            style = style,
            fontWeight = FontWeight.Bold,
            color = profileStatValueColor(),
        )
        EloPreviewDeltaText(
            delta = preview.myWinDelta,
            style = style,
            colorDeltasBySign = colorDeltasBySign,
        )
        Text(
            text = " | ",
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        EloPreviewDeltaText(
            delta = preview.myLossDelta,
            style = style,
            colorDeltasBySign = colorDeltasBySign,
        )
        Text(
            text = " ${preview.opponentElo}",
            style = style,
            fontWeight = FontWeight.Bold,
            color = profileStatValueColor(),
        )
    }
}

@Composable
private fun EloPreviewDeltaText(
    delta: Int,
    style: TextStyle,
    colorDeltasBySign: Boolean,
) {
    val color = when {
        !colorDeltasBySign -> MaterialTheme.colorScheme.primary
        delta > 0 -> MaterialTheme.colorScheme.primary
        delta < 0 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = "(${formatEloDelta(delta)})",
        style = style,
        fontWeight = FontWeight.Bold,
        color = color,
        maxLines = 1,
    )
}
