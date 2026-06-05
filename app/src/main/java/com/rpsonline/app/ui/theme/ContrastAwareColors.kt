package com.rpsonline.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** WCAG AA large-text minimum — enough for bold stat digits on profile badges. */
const val MinProfileAccentContrast = 3f

internal fun contrastRatio(foreground: Color, background: Color): Float {
    val fg = foreground.luminance() + 0.05f
    val bg = background.luminance() + 0.05f
    return maxOf(fg, bg) / minOf(fg, bg)
}

/**
 * Keeps [semantic] hue when readable on [background]; otherwise blends minimally toward
 * [readableFallback] until contrast meets [minContrast].
 */
fun contrastAwareAccent(
    semantic: Color,
    background: Color,
    readableFallback: Color,
    minContrast: Float = MinProfileAccentContrast,
): Color {
    if (contrastRatio(semantic, background) >= minContrast) return semantic
    var low = 0f
    var high = 1f
    repeat(10) {
        val mid = (low + high) / 2f
        val candidate = lerp(semantic, readableFallback, mid)
        if (contrastRatio(candidate, background) >= minContrast) {
            high = mid
        } else {
            low = mid
        }
    }
    return lerp(semantic, readableFallback, high)
}
