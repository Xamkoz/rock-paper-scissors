package com.rpsonline.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.rpsonline.app.ui.leaderboard.winRatePercent

@Composable
fun WinLossStatLine(
    wins: Int,
    losses: Int,
    draws: Int = 0,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    contentColors: OnlinePresenceContentColors? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val winRate = winRatePercent(wins, losses, draws)
    val separatorColor = contentColors?.muted ?: scheme.onSurfaceVariant
    val winRateColor = when {
        contentColors != null -> contentColors.statHighlight
        winRate != null -> profileStatValueColor()
        else -> scheme.onSurfaceVariant
    }
    val fontSize = textStyle.fontSize
    val fontFamily = textStyle.fontFamily
    val letterSpacing = textStyle.letterSpacing

    val line = buildAnnotatedString {
        appendStatToken(
            label = "W",
            value = wins.toString(),
            color = contentColors?.winColor ?: scheme.primary,
            fontSize = fontSize,
            fontFamily = fontFamily,
            letterSpacing = letterSpacing,
        )
        withStyle(
            SpanStyle(
                color = separatorColor,
                fontSize = fontSize,
                fontFamily = fontFamily,
                letterSpacing = letterSpacing,
            ),
        ) {
            append(" / ")
        }
        appendStatToken(
            label = "L",
            value = losses.toString(),
            color = contentColors?.lossColor ?: scheme.error,
            fontSize = fontSize,
            fontFamily = fontFamily,
            letterSpacing = letterSpacing,
        )
        withStyle(
            SpanStyle(
                color = separatorColor,
                fontSize = fontSize,
                fontFamily = fontFamily,
                letterSpacing = letterSpacing,
            ),
        ) {
            append(" / ")
        }
        appendStatToken(
            label = "D",
            value = draws.toString(),
            color = contentColors?.drawColor ?: scheme.tertiary,
            fontSize = fontSize,
            fontFamily = fontFamily,
            letterSpacing = letterSpacing,
        )
        if (winRate != null) {
            withStyle(
                SpanStyle(
                    color = separatorColor,
                    fontSize = fontSize,
                    fontFamily = fontFamily,
                    letterSpacing = letterSpacing,
                ),
            ) {
                append(" / ")
            }
            withStyle(
                SpanStyle(
                    color = winRateColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = fontSize,
                    fontFamily = fontFamily,
                    letterSpacing = letterSpacing,
                ),
            ) {
                append("$winRate%")
            }
        }
    }

    Text(
        text = line,
        modifier = modifier,
        style = textStyle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        softWrap = false,
    )
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendStatToken(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    letterSpacing: androidx.compose.ui.unit.TextUnit,
) {
    withStyle(
        SpanStyle(
            color = color,
            fontSize = fontSize,
            fontFamily = fontFamily,
            letterSpacing = letterSpacing,
        ),
    ) {
        append("$label ")
    }
    withStyle(
        SpanStyle(
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize,
            fontFamily = fontFamily,
            letterSpacing = letterSpacing,
        ),
    ) {
        append(value)
    }
}

@Composable
fun RoundWinRateLine(
    wins: Int,
    losses: Int,
    draws: Int = 0,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodySmall,
    label: String = "Round WR: ",
    contentColors: OnlinePresenceContentColors? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val roundWinRate = winRatePercent(wins = wins, losses = losses, draws = draws)
    val valueText = roundWinRate?.let { "$it%" } ?: "-"
    val muted = contentColors?.muted ?: scheme.onSurfaceVariant
    val valueColor = when {
        contentColors != null -> contentColors.statHighlight
        roundWinRate != null -> profileStatValueColor()
        else -> scheme.onSurfaceVariant
    }
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = muted)) {
                append(label)
            }
            withStyle(
                SpanStyle(
                    color = valueColor,
                    fontWeight = FontWeight.Bold,
                ),
            ) {
                append(valueText)
            }
        },
        modifier = modifier,
        style = textStyle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
