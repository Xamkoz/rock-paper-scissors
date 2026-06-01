package com.rpsonline.app.ui.leaderboard

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.rpsonline.app.ui.theme.isRpsDarkTheme
import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/** Even split for rock / paper / scissors — anchor for the mid (yellow) point. */
private const val BalancedSharePercent = 33.333f

/** Neon spectrum: magenta (bad) → yellow (mid) → cyan (good). */
private const val MagentaHue = 320f
private const val YellowHue = 52f
private const val CyanHue = 186f

private const val LowSegmentGamma = 1.4f
private const val HighSegmentGamma = 0.68f

private fun lerpHue(from: Float, to: Float, fraction: Float): Float {
    var delta = to - from
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    return (from + delta * fraction + 360f) % 360f
}

/**
 * Smooth magenta → yellow → cyan neon spectrum.
 * [percent] is 0–100; ~33% is yellow. Low shares stay magenta; high shares lean cyan.
 */
fun leaderboardSpectrumColor(percent: Float, darkTheme: Boolean): Color {
    val p = percent.coerceIn(0f, 100f)
    val baseSaturation = if (darkTheme) 0.95f else 0.72f
    val baseLightness = if (darkTheme) 0.58f else 0.42f

    val hue = when {
        p <= BalancedSharePercent -> {
            val t = if (BalancedSharePercent > 0f) p / BalancedSharePercent else 0f
            lerpHue(MagentaHue, YellowHue, t.pow(LowSegmentGamma))
        }
        else -> {
            val span = 100f - BalancedSharePercent
            val t = if (span > 0f) (p - BalancedSharePercent) / span else 1f
            lerpHue(YellowHue, CyanHue, t.pow(HighSegmentGamma))
        }
    }

    val saturation = if (darkTheme) {
        when {
            hue >= 300f || hue <= 20f -> baseSaturation.coerceAtLeast(0.98f)
            hue in 45f..200f -> (baseSaturation + 0.02f).coerceAtMost(1f)
            else -> baseSaturation
        }
    } else {
        when {
            hue >= 300f || hue <= 20f -> (baseSaturation + 0.04f).coerceAtMost(0.78f)
            hue in 45f..200f -> (baseSaturation + 0.06f).coerceAtMost(0.8f)
            else -> baseSaturation
        }
    }

    return Color.hsl(hue, saturation, baseLightness)
}

@Composable
fun leaderboardSpectrumColor(percent: Float): Color =
    leaderboardSpectrumColor(percent, isRpsDarkTheme())

/** Fewer throws per round is better; 2 cyan → 7.5 yellow → 13 magenta. */
private const val RpsPerRoundBest = 2f
private const val RpsPerRoundMid = 7.5f
private const val RpsPerRoundWorst = 13f

fun rpsPerRoundColor(throwsPerRound: Double, darkTheme: Boolean): Color {
    val value = throwsPerRound.toFloat().coerceIn(RpsPerRoundBest, RpsPerRoundWorst)
    val percent = when {
        value <= RpsPerRoundMid -> {
            val span = RpsPerRoundMid - RpsPerRoundBest
            val t = if (span > 0f) (value - RpsPerRoundBest) / span else 0f
            (1f - t) * 100f + t * BalancedSharePercent
        }
        else -> {
            val span = RpsPerRoundWorst - RpsPerRoundMid
            val t = if (span > 0f) (value - RpsPerRoundMid) / span else 1f
            BalancedSharePercent * (1f - t)
        }
    }
    return leaderboardSpectrumColor(percent, darkTheme)
}

@Composable
fun rpsPerRoundColor(throwsPerRound: Double): Color =
    rpsPerRoundColor(throwsPerRound, isRpsDarkTheme())

/** Faster moves are better; ~2s cyan → ~15s yellow → 60s magenta. */
private const val RecapMoveSecondsBest = 2f
private const val RecapMoveSecondsMid = 15f
private const val RecapMoveSecondsWorst = 60f

internal fun recapMoveTimeSpectrumPercent(moveMs: Int): Float {
    val seconds = (moveMs / 1000f).coerceIn(RecapMoveSecondsBest, RecapMoveSecondsWorst)
    return when {
        seconds <= RecapMoveSecondsMid -> {
            val span = RecapMoveSecondsMid - RecapMoveSecondsBest
            val t = if (span > 0f) (seconds - RecapMoveSecondsBest) / span else 0f
            (1f - t) * 100f + t * BalancedSharePercent
        }
        else -> {
            val span = RecapMoveSecondsWorst - RecapMoveSecondsMid
            val t = if (span > 0f) (seconds - RecapMoveSecondsMid) / span else 1f
            BalancedSharePercent * (1f - t)
        }
    }
}

fun recapMoveTimeColor(moveMs: Int, darkTheme: Boolean): Color =
    leaderboardSpectrumColor(recapMoveTimeSpectrumPercent(moveMs), darkTheme)

@Composable
fun recapMoveTimeColor(moveMs: Int?): Color =
    when (moveMs) {
        null -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> recapMoveTimeColor(moveMs, isRpsDarkTheme())
    }

private const val EloRatingMin = 900f
private const val EloRatingMid = 1000f
private const val EloRatingMax = 1250f

/** Maps player ELO to 0–100 spectrum; clamped to the current [900, 1250] population spread. */
internal fun eloRatingSpectrumPercent(elo: Int): Float {
    val value = elo.toFloat().coerceIn(EloRatingMin, EloRatingMax)
    return when {
        value <= EloRatingMid -> {
            val span = EloRatingMid - EloRatingMin
            val t = if (span > 0f) (value - EloRatingMin) / span else 0f
            t * BalancedSharePercent
        }
        else -> {
            val span = EloRatingMax - EloRatingMid
            val t = if (span > 0f) (value - EloRatingMid) / span else 1f
            BalancedSharePercent + t * (100f - BalancedSharePercent)
        }
    }
}

/** Lower ELO → magenta, ~1000 → yellow, ~1250 → cyan (900–1250 player spread). */
fun eloRatingColor(elo: Int, darkTheme: Boolean): Color {
    return leaderboardSpectrumColor(eloRatingSpectrumPercent(elo), darkTheme)
}

@Composable
fun eloRatingColor(elo: Int): Color = eloRatingColor(elo, isRpsDarkTheme())
