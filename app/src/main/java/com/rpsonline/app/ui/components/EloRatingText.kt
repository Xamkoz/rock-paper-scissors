package com.rpsonline.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import com.rpsonline.app.ui.leaderboard.eloRatingColor

@Composable
fun EloRatingText(
    elo: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineMedium,
) {
    Text(
        text = "$elo",
        modifier = modifier,
        style = style,
        color = eloRatingColor(elo),
    )
}
