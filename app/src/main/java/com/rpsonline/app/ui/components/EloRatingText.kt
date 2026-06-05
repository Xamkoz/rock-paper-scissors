package com.rpsonline.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.rpsonline.app.ui.leaderboard.eloRatingColor

/** Widest ELO label used to size the leaderboard/profile summary column. */
const val FourDigitEloWidthSample = "9999"

@Composable
fun rememberFourDigitEloColumnWidth(
    style: TextStyle = MaterialTheme.typography.titleLarge,
): Dp {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(style, textMeasurer, density) {
        val textWidthPx = textMeasurer.measure(
            text = FourDigitEloWidthSample,
            style = style,
            maxLines = 1,
        ).size.width
        with(density) { textWidthPx.toDp() }
    }
}

@Composable
fun EloRatingText(
    elo: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineMedium,
    textAlign: TextAlign? = null,
    color: Color? = null,
) {
    Text(
        text = "$elo",
        modifier = modifier,
        style = style,
        color = color ?: eloRatingColor(elo),
        textAlign = textAlign,
    )
}
