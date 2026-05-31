package com.rpsonline.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rpsonline.app.data.preferences.AppThemeStyle
import kotlin.random.Random

@Composable
fun Modifier.appBackground(style: AppThemeStyle): Modifier = when (style) {
    AppThemeStyle.CYBERPUNK -> cyberpunkBackground()
    AppThemeStyle.COSMOS -> cosmosBackground()
    AppThemeStyle.FIRE -> fireBackground()
    AppThemeStyle.LIGHT,
    AppThemeStyle.DARK,
    -> plainBackground()
}

@Composable
private fun Modifier.plainBackground(): Modifier {
    val base = MaterialTheme.colorScheme.background
    return drawBehind { drawRect(base) }
}

@Composable
fun Modifier.cyberpunkBackground(): Modifier {
    val gridColor = CyberGrid.copy(alpha = 0.07f)
    val vignetteTop = NeonMagenta.copy(alpha = 0.14f)
    val vignetteBottom = NeonCyan.copy(alpha = 0.10f)

    return drawBehind {
        drawRect(CyberDeepBg)
        drawRadialGlow(vignetteTop, Offset(size.width * 0.15f, size.height * 0.1f), 0.65f)
        drawRadialGlow(vignetteBottom, Offset(size.width * 0.85f, size.height * 0.9f), 0.55f)
        drawGrid(gridColor)
    }
}

@Composable
private fun Modifier.cosmosBackground(): Modifier {
    val stars = rememberStarField(seed = 42, count = 48)
    return drawBehind {
        drawRect(Color(0xFF050510))
        drawRadialGlow(
            Color(0xFF6A28A8).copy(alpha = 0.22f),
            Offset(size.width * 0.2f, size.height * 0.25f),
            0.7f,
        )
        drawRadialGlow(
            Color(0xFF1A5080).copy(alpha = 0.18f),
            Offset(size.width * 0.8f, size.height * 0.75f),
            0.6f,
        )
        stars.forEach { star ->
            drawCircle(
                color = Color.White.copy(alpha = star.alpha),
                radius = star.radius,
                center = Offset(star.x * size.width, star.y * size.height),
            )
        }
    }
}

@Composable
private fun Modifier.fireBackground(): Modifier {
    return drawBehind {
        drawRect(Color(0xFF120606))
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF3A0800).copy(alpha = 0.5f),
                    Color.Transparent,
                    Color(0xFFFF5722).copy(alpha = 0.12f),
                ),
                startY = size.height * 0.55f,
                endY = size.height,
            ),
        )
        drawRadialGlow(
            Color(0xFFFF5722).copy(alpha = 0.2f),
            Offset(size.width * 0.5f, size.height * 0.92f),
            0.45f,
        )
        drawRadialGlow(
            Color(0xFFFFD54F).copy(alpha = 0.08f),
            Offset(size.width * 0.3f, size.height * 0.4f),
            0.35f,
        )
    }
}

private data class Star(val x: Float, val y: Float, val radius: Float, val alpha: Float)

@Composable
private fun rememberStarField(seed: Int, count: Int): List<Star> {
    val random = Random(seed)
    return androidx.compose.runtime.remember(seed, count) {
        List(count) {
            Star(
                x = random.nextFloat(),
                y = random.nextFloat(),
                radius = 0.6f + random.nextFloat() * 1.4f,
                alpha = 0.25f + random.nextFloat() * 0.55f,
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRadialGlow(
    color: Color,
    center: Offset,
    radiusFactor: Float,
) {
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(color, Color.Transparent),
            center = center,
            radius = size.maxDimension * radiusFactor,
        ),
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGrid(gridColor: Color) {
    val spacing = 28.dp.toPx()
    var x = 0f
    while (x <= size.width) {
        drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        x += spacing
    }
    var y = 0f
    while (y <= size.height) {
        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += spacing
    }
}
