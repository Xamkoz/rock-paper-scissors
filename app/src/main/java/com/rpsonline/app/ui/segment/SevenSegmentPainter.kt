package com.rpsonline.app.ui.segment

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max

fun interface SevenSegmentDrawTarget {
    fun drawRoundRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        cornerRadius: Float,
        colorArgb: Int,
    )
}

data class SevenSegmentPalette(
    val litArgb: Int,
    val ghostArgb: Int,
    val halfLitArgb: Int,
)

object SevenSegmentPainter {
    enum class SlotMode {
        Ghost,
        HalfLit,
        Lit,
    }

    fun drawSlot(
        target: SevenSegmentDrawTarget,
        slotLeft: Float,
        slotTop: Float,
        width: Float,
        height: Float,
        mode: SlotMode,
        litSegments: Set<Char>,
        palette: SevenSegmentPalette,
    ) {
        val layout = SevenSegmentGeometry.layout(width, height)
        layout.forEach { segment ->
            when (mode) {
                SlotMode.Ghost -> drawSegment(
                    target,
                    slotLeft,
                    slotTop,
                    segment,
                    palette.ghostArgb,
                )
                SlotMode.HalfLit -> {
                    val color = if (segment.id in litSegments) palette.halfLitArgb else palette.ghostArgb
                    drawSegment(target, slotLeft, slotTop, segment, color)
                }
                SlotMode.Lit -> {
                    val isLit = segment.id in litSegments
                    if (isLit) {
                        drawLitSegment(target, slotLeft, slotTop, segment, palette.litArgb)
                    } else {
                        drawSegment(target, slotLeft, slotTop, segment, palette.ghostArgb)
                    }
                }
            }
        }
    }

    fun drawColon(
        target: SevenSegmentDrawTarget,
        slotLeft: Float,
        slotTop: Float,
        width: Float,
        height: Float,
        lit: Boolean,
        palette: SevenSegmentPalette,
    ) {
        val centerX = slotLeft + width / 2f
        val pipColor = if (lit) palette.litArgb else palette.ghostArgb
        drawColonPip(target, centerX, slotTop + height * 0.36f, width, pipColor, glow = lit)
        drawColonPip(target, centerX, slotTop + height * 0.64f, width, pipColor, glow = lit)
    }

    fun drawTopBarStatusRow(
        target: SevenSegmentDrawTarget,
        left: Float,
        top: Float,
        digitWidthPx: Float,
        digitHeightPx: Float,
        spacingPx: Float,
        spec: TopBarStatusRowSpec,
        palette: SevenSegmentPalette,
        timeMs: Long = System.currentTimeMillis(),
    ) {
        var x = left

        fun gap() {
            x += spacingPx
        }

        fun slot(mode: SlotMode, litSegments: Set<Char> = emptySet()) {
            drawSlot(target, x, top, digitWidthPx, digitHeightPx, mode, litSegments, palette)
            x += digitWidthPx
        }

        fun drawValueDigit(char: Char, isLeadingZero: Boolean, lit: Boolean) {
            val segments = SevenSegmentGeometry.segmentsFor(char)
            when {
                !lit -> slot(SlotMode.HalfLit, segments)
                isLeadingZero -> slot(SlotMode.HalfLit, segments)
                else -> slot(SlotMode.Lit, segments)
            }
        }

        val countDigits = SevenSegmentTimerDigits.formatFourDigitChars(spec.onlineCount)
        if (countDigits == null) {
            repeat(4) {
                slot(SlotMode.Ghost)
                if (it < 3) gap()
            }
        } else {
            val countFirstSig = SevenSegmentTimerDigits.fourDigitFirstSignificantIndex(countDigits)
            countDigits.forEachIndexed { index, char ->
                drawValueDigit(char, index < countFirstSig, lit = true)
                if (index < 3) gap()
            }
        }

        gap()
        slot(SlotMode.Ghost)
        gap()

        val spinnerSegments = if (spec.animateSpinner) {
            SegmentedSpinnerSteps.segmentsAt(spec.spinnerStyle, timeMs, spec.timerAnchorMs)
        } else {
            emptySet()
        }
        slot(SlotMode.HalfLit, spinnerSegments)
        gap()

        slot(SlotMode.Ghost)
        gap()

        val timerDigits = if (spec.showLiveTime) {
            val displaySeconds = resolveSegmentedTimerSeconds(
                elapsedSeconds = spec.elapsedSeconds,
                timerMode = spec.timerMode,
                timerAnchorMs = spec.timerAnchorMs,
                nowMs = timeMs,
            )
            SevenSegmentTimerDigits.formatMmSsChars(displaySeconds)
        } else {
            "0000".toList()
        }
        val timerFirstSig = SevenSegmentTimerDigits.firstSignificantIndex(timerDigits)
        val colonWidthPx = digitWidthPx * SevenSegmentColonLayout.WIDTH_RATIO
        timerDigits.forEachIndexed { index, digitChar ->
            if (index == 2) {
                drawColon(
                    target,
                    x,
                    top,
                    colonWidthPx,
                    digitHeightPx,
                    lit = SevenSegmentColonBlink.isLit(spec.showLiveTime, spec.timerAnchorMs, timeMs),
                    palette = palette,
                )
                x += colonWidthPx
                gap()
            }
            drawValueDigit(
                char = digitChar,
                isLeadingZero = index < timerFirstSig,
                lit = spec.showLiveTime,
            )
            if (index < timerDigits.lastIndex) gap()
        }
    }

    /** @see drawTopBarStatusRow */
    fun drawCompactQueueStatusRow(
        target: SevenSegmentDrawTarget,
        left: Float,
        top: Float,
        digitWidthPx: Float,
        digitHeightPx: Float,
        spacingPx: Float,
        spec: TopBarStatusRowSpec,
        palette: SevenSegmentPalette,
        timeMs: Long = System.currentTimeMillis(),
    ) {
        drawTopBarStatusRow(
            target = target,
            left = left,
            top = top,
            digitWidthPx = digitWidthPx,
            digitHeightPx = digitHeightPx,
            spacingPx = spacingPx,
            spec = spec,
            palette = palette,
            timeMs = timeMs,
        )
    }

    internal fun drawSegment(
        target: SevenSegmentDrawTarget,
        slotLeft: Float,
        slotTop: Float,
        segment: SegmentLayout,
        colorArgb: Int,
        inflateFactor: Float = 0f,
        thicknessScale: Float = 1f,
    ) {
        val thickness = segment.thickness * thicknessScale
        val inflate = segment.thickness * inflateFactor
        val corner = thickness * 0.22f
        if (segment.horizontal) {
            val width = segment.length + inflate * 2f
            val height = thickness + inflate * 0.5f
            target.drawRoundRect(
                left = slotLeft + segment.left - inflate,
                top = slotTop + segment.top - (height - segment.thickness) / 2f,
                right = slotLeft + segment.left - inflate + width,
                bottom = slotTop + segment.top - (height - segment.thickness) / 2f + height,
                cornerRadius = corner,
                colorArgb = colorArgb,
            )
        } else {
            val width = thickness + inflate * 0.5f
            val height = segment.length + inflate * 2f
            target.drawRoundRect(
                left = slotLeft + segment.left - (width - segment.thickness) / 2f,
                top = slotTop + segment.top - inflate,
                right = slotLeft + segment.left - (width - segment.thickness) / 2f + width,
                bottom = slotTop + segment.top - inflate + height,
                cornerRadius = corner,
                colorArgb = colorArgb,
            )
        }
    }

    internal fun drawLitSegment(
        target: SevenSegmentDrawTarget,
        slotLeft: Float,
        slotTop: Float,
        segment: SegmentLayout,
        colorArgb: Int,
    ) {
        val base = segment.thickness
        drawSegment(target, slotLeft, slotTop, segment, applyAlpha(colorArgb, 0.2f), 0.72f, 1f)
        drawSegment(target, slotLeft, slotTop, segment, applyAlpha(colorArgb, 0.34f), 0.46f, 1f)
        drawSegment(target, slotLeft, slotTop, segment, applyAlpha(colorArgb, 0.52f), 0.24f, 1f)
        drawSegment(target, slotLeft, slotTop, segment, colorArgb, 0.06f, 1.16f)
    }

    private fun drawColonPip(
        target: SevenSegmentDrawTarget,
        centerX: Float,
        centerY: Float,
        slotWidth: Float,
        colorArgb: Int,
        glow: Boolean,
    ) {
        val width = slotWidth * 0.55f
        val height = width * 0.5f
        if (glow) {
            target.drawRoundRect(
                left = centerX - width * 0.7f,
                top = centerY - height * 0.7f,
                right = centerX + width * 0.7f,
                bottom = centerY + height * 0.7f,
                cornerRadius = height * 0.7f,
                colorArgb = applyAlpha(colorArgb, 0.24f),
            )
        }
        target.drawRoundRect(
            left = centerX - width / 2f,
            top = centerY - height / 2f,
            right = centerX + width / 2f,
            bottom = centerY + height / 2f,
            cornerRadius = height / 2f,
            colorArgb = colorArgb,
        )
    }

    private fun applyAlpha(colorArgb: Int, alpha: Float): Int {
        val a = (android.graphics.Color.alpha(colorArgb) * alpha).toInt().coerceIn(0, 255)
        return android.graphics.Color.argb(
            a,
            android.graphics.Color.red(colorArgb),
            android.graphics.Color.green(colorArgb),
            android.graphics.Color.blue(colorArgb),
        )
    }
}

fun DrawScope.asSevenSegmentTarget(): SevenSegmentDrawTarget {
    return SevenSegmentDrawTarget { left, top, right, bottom, cornerRadius, colorArgb ->
        drawRoundRect(
            color = Color(colorArgb),
            topLeft = Offset(left, top),
            size = Size(max(0f, right - left), max(0f, bottom - top)),
            cornerRadius = CornerRadius(cornerRadius, cornerRadius),
        )
    }
}

class AndroidCanvasDrawTarget(
    private val canvas: Canvas,
) : SevenSegmentDrawTarget {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun drawRoundRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        cornerRadius: Float,
        colorArgb: Int,
    ) {
        paint.color = colorArgb
        canvas.drawRoundRect(RectF(left, top, right, bottom), cornerRadius, cornerRadius, paint)
    }
}

fun Color.toSevenSegmentPalette(ghost: Color, halfLit: Color): SevenSegmentPalette {
    return SevenSegmentPalette(
        litArgb = toArgb(),
        ghostArgb = ghost.toArgb(),
        halfLitArgb = halfLit.toArgb(),
    )
}
