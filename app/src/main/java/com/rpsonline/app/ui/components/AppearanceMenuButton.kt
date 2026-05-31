package com.rpsonline.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.rpsonline.app.R
import com.rpsonline.app.data.preferences.AppThemeStyle
import com.rpsonline.app.ui.theme.colorSchemeFor
import com.rpsonline.app.ui.theme.themeIconPreviewScheme
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceMenuButton(
    currentStyle: AppThemeStyle,
    onStyleSelected: (AppThemeStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSheet by remember { mutableStateOf(false) }
    val appearanceLabel = stringResource(R.string.appearance)

    TopBarSegmentedSlot(
        onClick = { showSheet = true },
        active = true,
        contentDescription = appearanceLabel,
        modifier = modifier,
    ) { litColor, ghostColor, contentSize ->
        ThemeSelectorKnob(
            currentStyle = currentStyle,
            litColor = litColor,
            ghostColor = ghostColor,
            modifier = Modifier.size(contentSize),
        )
    }

    if (showSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = appearanceLabel,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                AppThemeStyle.entries.forEach { style ->
                    AppearanceStyleRow(
                        style = style,
                        selected = style == currentStyle,
                        onClick = {
                            onStyleSelected(style)
                            showSheet = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeSelectorKnob(
    currentStyle: AppThemeStyle,
    litColor: Color,
    ghostColor: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scheme = colorSchemeFor(context, currentStyle)
    val styles = AppThemeStyle.entries
    val styleIndex = styles.indexOf(currentStyle).coerceAtLeast(0)
    val slotCount = styles.size
    val angleStep = if (slotCount <= 1) 0f else 270f / (slotCount - 1)

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val outerRadius = minOf(size.width, size.height) / 2f
        val faceRadius = outerRadius * 0.88f
        val hubRadius = outerRadius * 0.14f

        drawCircle(
            color = ghostColor.copy(alpha = 0.92f),
            radius = outerRadius,
            center = center,
        )
        drawCircle(
            brush = Brush.sweepGradient(
                0f to scheme.primary,
                0.33f to scheme.secondary,
                0.66f to scheme.tertiary,
                1f to scheme.primary,
                center = center,
            ),
            radius = faceRadius,
            center = center,
        )
        drawCircle(
            color = scheme.background.copy(alpha = 0.48f),
            radius = faceRadius * 0.58f,
            center = center,
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.16f),
            radius = faceRadius * 0.92f,
            center = center,
            style = Stroke(width = outerRadius * 0.06f),
        )

        styles.forEachIndexed { index, _ ->
            val angleRad = Math.toRadians(-90.0 + index * angleStep)
            val tickInner = faceRadius * 0.68f
            val tickOuter = faceRadius * 0.96f
            val selected = index == styleIndex
            val tickColor = if (selected) litColor else ghostColor.copy(alpha = 0.55f)
            val start = Offset(
                x = center.x + cos(angleRad).toFloat() * tickInner,
                y = center.y + sin(angleRad).toFloat() * tickInner,
            )
            val end = Offset(
                x = center.x + cos(angleRad).toFloat() * tickOuter,
                y = center.y + sin(angleRad).toFloat() * tickOuter,
            )
            drawLine(
                color = tickColor,
                start = start,
                end = end,
                strokeWidth = if (selected) outerRadius * 0.14f else outerRadius * 0.07f,
            )
        }

        val pointerAngleRad = Math.toRadians(-90.0 + styleIndex * angleStep)
        val pointerInner = faceRadius * 0.22f
        val pointerOuter = faceRadius * 0.62f
        drawLine(
            color = litColor,
            start = Offset(
                x = center.x + cos(pointerAngleRad).toFloat() * pointerInner,
                y = center.y + sin(pointerAngleRad).toFloat() * pointerInner,
            ),
            end = Offset(
                x = center.x + cos(pointerAngleRad).toFloat() * pointerOuter,
                y = center.y + sin(pointerAngleRad).toFloat() * pointerOuter,
            ),
            strokeWidth = outerRadius * 0.12f,
        )
        drawCircle(
            color = litColor.copy(alpha = 0.35f),
            radius = hubRadius * 1.35f,
            center = center,
        )
        drawCircle(
            color = litColor,
            radius = hubRadius,
            center = center,
        )
    }
}

@Composable
private fun AppearanceStyleRow(
    style: AppThemeStyle,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val scheme = themeIconPreviewScheme(context, style)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ThemePreviewSwatch(
            style = style,
            primary = scheme.primary,
            secondary = scheme.secondary,
            tertiary = scheme.tertiary,
            background = scheme.background,
            surface = scheme.surface,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = style.label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = when (style) {
                    AppThemeStyle.LIGHT -> "Light"
                    AppThemeStyle.DARK -> "Dark"
                    else -> if (style.isDark) "Dark" else "Light"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = stringResource(R.string.selected),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun ThemePreviewSwatch(
    style: AppThemeStyle,
    primary: Color,
    secondary: Color,
    tertiary: Color,
    background: Color,
    surface: Color,
    modifier: Modifier = Modifier,
) {
    val gradientColors = when (style) {
        AppThemeStyle.LIGHT -> listOf(background, surface, primary.copy(alpha = 0.8f), secondary.copy(alpha = 0.8f))
        AppThemeStyle.DARK -> listOf(background, surface, primary.copy(alpha = 0.85f), tertiary.copy(alpha = 0.85f))
        else -> listOf(background, primary, secondary, tertiary)
    }
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape)
            .background(
                brush = Brush.linearGradient(colors = gradientColors),
                shape = CircleShape,
            ),
    )
}
