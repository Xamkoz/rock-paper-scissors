package com.rpsonline.app.ui.segment

import kotlin.math.roundToInt

enum class SegmentedSpinnerStyle {
    QUEUE,
    MATCH,
    MATCH_CLOCK_STOPPED,
}

internal data class SegmentLayout(
    val id: Char,
    val left: Float,
    val top: Float,
    val length: Float,
    val thickness: Float,
    val horizontal: Boolean,
)

internal object SevenSegmentGeometry {
    val allSegments: Set<Char> = setOf('a', 'b', 'c', 'd', 'e', 'f', 'g')

    fun layout(width: Float, height: Float): List<SegmentLayout> {
        val thickness = (width * 0.15f).coerceAtLeast(1f)
        val gap = thickness * 0.42f
        val sideInset = width * 0.05f

        val leftX = sideInset
        val rightX = width - sideInset - thickness
        val barLeft = leftX + thickness + gap
        val barWidth = (rightX - gap - barLeft).coerceAtLeast(thickness)

        val topY = height * 0.05f
        val bottomY = height * 0.95f - thickness
        val midY = height * 0.5f - thickness / 2f

        val upperVertTop = topY + thickness + gap
        val upperVertBottom = midY - gap
        val upperVertLen = (upperVertBottom - upperVertTop).coerceAtLeast(thickness)

        val lowerVertTop = midY + thickness + gap
        val lowerVertBottom = bottomY - gap
        val lowerVertLen = (lowerVertBottom - lowerVertTop).coerceAtLeast(thickness)

        return listOf(
            SegmentLayout('a', barLeft, topY, barWidth, thickness, horizontal = true),
            SegmentLayout('g', barLeft, midY, barWidth, thickness, horizontal = true),
            SegmentLayout('d', barLeft, bottomY, barWidth, thickness, horizontal = true),
            SegmentLayout('f', leftX, upperVertTop, upperVertLen, thickness, horizontal = false),
            SegmentLayout('b', rightX, upperVertTop, upperVertLen, thickness, horizontal = false),
            SegmentLayout('e', leftX, lowerVertTop, lowerVertLen, thickness, horizontal = false),
            SegmentLayout('c', rightX, lowerVertTop, lowerVertLen, thickness, horizontal = false),
        )
    }

    fun segmentsFor(digit: Char): Set<Char> = when (digit) {
        '0' -> setOf('a', 'b', 'c', 'd', 'e', 'f')
        '1' -> setOf('b', 'c')
        '2' -> setOf('a', 'b', 'd', 'e', 'g')
        '3' -> setOf('a', 'b', 'c', 'd', 'g')
        '4' -> setOf('b', 'c', 'f', 'g')
        '5' -> setOf('a', 'c', 'd', 'f', 'g')
        '6' -> setOf('a', 'c', 'd', 'e', 'f', 'g')
        '7' -> setOf('a', 'b', 'c')
        '8' -> setOf('a', 'b', 'c', 'd', 'e', 'f', 'g')
        '9' -> setOf('a', 'b', 'c', 'd', 'f', 'g')
        else -> emptySet()
    }
}

object SegmentedSpinnerSteps {
    private val queueSteps = listOf(
        setOf('f'),
        setOf('a'),
        setOf('b'),
        setOf('g'),
        setOf('c'),
        setOf('d'),
        setOf('e'),
    )

    private val matchSteps = listOf(
        setOf('a', 'd'),
        setOf('f', 'b'),
        setOf('g'),
        setOf('e', 'c'),
        setOf('a', 'g'),
        setOf('d', 'g'),
        setOf('f', 'e'),
        setOf('b', 'c'),
    )

    private val matchClockStoppedSteps = listOf(
        setOf('a', 'd'),
        setOf('a', 'd'),
        setOf('g'),
        setOf('g'),
        setOf('f', 'e'),
        setOf('b', 'c'),
        setOf('f', 'e'),
        setOf('b', 'c'),
    )

    fun steps(style: SegmentedSpinnerStyle): List<Set<Char>> = when (style) {
        SegmentedSpinnerStyle.QUEUE -> queueSteps
        SegmentedSpinnerStyle.MATCH -> matchSteps
        SegmentedSpinnerStyle.MATCH_CLOCK_STOPPED -> matchClockStoppedSteps
    }

    fun stepDelayMs(style: SegmentedSpinnerStyle): Long = when (style) {
        SegmentedSpinnerStyle.QUEUE -> 500L
        SegmentedSpinnerStyle.MATCH -> 500L
        SegmentedSpinnerStyle.MATCH_CLOCK_STOPPED -> 500L
    }

    fun stepIndex(
        style: SegmentedSpinnerStyle,
        timeMs: Long = System.currentTimeMillis(),
        timerAnchorMs: Long? = null,
    ): Int {
        val stepList = steps(style)
        val delayMs = stepDelayMs(style)
        val cycleMs = stepList.size.toLong() * delayMs
        val offsetMs = when (timerAnchorMs) {
            null -> timeMs
            else -> timeMs - timerAnchorMs
        }
        val phaseMs = ((offsetMs % cycleMs) + cycleMs) % cycleMs
        return (phaseMs / delayMs).toInt() % stepList.size
    }

    fun segmentsAt(
        style: SegmentedSpinnerStyle,
        timeMs: Long = System.currentTimeMillis(),
        timerAnchorMs: Long? = null,
    ): Set<Char> {
        val stepList = steps(style)
        return stepList[stepIndex(style, timeMs, timerAnchorMs)]
    }
}

object SevenSegmentTimerDigits {
    fun formatMmSsChars(elapsedSeconds: Long): List<Char> {
        val totalSeconds = elapsedSeconds.coerceAtLeast(0)
        val minutes = (totalSeconds / 60).coerceAtMost(99)
        val seconds = (totalSeconds % 60).coerceAtMost(59)
        return "%02d%02d".format(minutes, seconds).toList()
    }

    fun firstSignificantIndex(digits: List<Char>): Int {
        return digits.indexOfFirst { it != '0' }.let { index ->
            if (index < 0) digits.lastIndex else index
        }
    }

    fun formatFourDigitChars(value: Int?): List<Char>? {
        if (value == null) return null
        return value.coerceIn(0, 9_999).toString().padStart(4, '0').toList()
    }

    fun fourDigitFirstSignificantIndex(digits: List<Char>): Int {
        return digits.indexOfFirst { it != '0' }.let { index ->
            if (index < 0) 3 else index
        }
    }
}

object SevenSegmentStatusRowLayout {
    /** Matches [TopBarSegmentedStatusRow]: 4 count + blank + spinner + blank + MM + colon + SS. */
    fun widthPx(digitWidthPx: Int, spacingPx: Int): Int {
        val colonWidthPx = SevenSegmentColonLayout.widthPx(digitWidthPx)
        return digitWidthPx * 11 + colonWidthPx + spacingPx * 11
    }

    fun widthDp(digitWidthDp: Float, spacingDp: Float): Float {
        return digitWidthDp * 11f + SevenSegmentColonLayout.widthDp(digitWidthDp) + spacingDp * 11f
    }

    fun widthDp(): Float {
        return widthDp(TopBarSegmentDimensions.DIGIT_WIDTH_DP, TopBarSegmentDimensions.SPACING_DP)
    }
}

/** MM:SS colon blink — 500ms on / 500ms off, phase-locked to [timerAnchorMs] second ticks. */
object SevenSegmentColonBlink {
    const val ON_MS = 500L
    const val PERIOD_MS = 1_000L

    fun msIntoSecond(timerAnchorMs: Long, nowMs: Long): Long =
        ((nowMs - timerAnchorMs) % PERIOD_MS + PERIOD_MS) % PERIOD_MS

    fun isLit(
        showLiveTime: Boolean,
        timerAnchorMs: Long?,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!showLiveTime) return false
        if (timerAnchorMs == null) return (nowMs % PERIOD_MS) < ON_MS
        return msIntoSecond(timerAnchorMs, nowMs) < ON_MS
    }

    fun delayUntilToggle(
        timerAnchorMs: Long?,
        nowMs: Long = System.currentTimeMillis(),
    ): Long {
        val msIntoSecond = msIntoSecondForAnchor(timerAnchorMs, nowMs)
        return if (msIntoSecond < ON_MS) ON_MS - msIntoSecond else PERIOD_MS - msIntoSecond
    }

    /** Wait until the next anchor-aligned second tick (when MM:SS seconds digit advances). */
    fun delayMsUntilNextSecondBoundary(
        timerAnchorMs: Long?,
        nowMs: Long = System.currentTimeMillis(),
    ): Long {
        val msIntoSecond = msIntoSecondForAnchor(timerAnchorMs, nowMs)
        return if (msIntoSecond == 0L) PERIOD_MS else PERIOD_MS - msIntoSecond
    }

    private fun msIntoSecondForAnchor(timerAnchorMs: Long?, nowMs: Long): Long =
        when (timerAnchorMs) {
            null -> nowMs % PERIOD_MS
            else -> msIntoSecond(timerAnchorMs, nowMs)
        }
}

/** MM:SS colon slot — narrower than a digit (matches Compose colon slot width). */
object SevenSegmentColonLayout {
    const val WIDTH_RATIO = 0.4f

    fun widthPx(digitWidthPx: Int): Int {
        return (digitWidthPx * WIDTH_RATIO).roundToInt().coerceAtLeast(2)
    }

    fun widthDp(digitWidthDp: Float): Float = digitWidthDp * WIDTH_RATIO
}

enum class SegmentedNotificationStatus {
    IN_QUEUE,
    MATCH_FOUND,
    IN_MATCH,
}

data class TopBarStatusRowSpec(
    val status: SegmentedNotificationStatus,
    val onlineCount: Int?,
    val showLiveTime: Boolean,
    val elapsedSeconds: Long,
    val spinnerStyle: SegmentedSpinnerStyle,
    val animateSpinner: Boolean = true,
    /** Anchor for MM:SS second ticks and colon blink phase (e.g. queue joinedAt, match createdAt). */
    val timerAnchorMs: Long? = null,
)

/** @see TopBarStatusRowSpec */
typealias CompactQueueStatusSpec = TopBarStatusRowSpec

object TopBarSegmentDimensions {
    const val DIGIT_WIDTH_DP = 10f
    const val DIGIT_HEIGHT_DP = 36f
    const val SPACING_DP = 1f
}

/** Compact notification row; shorter digits than the top bar (less vertical in the shade). */
object NotificationSegmentDimensions {
    const val DIGIT_WIDTH_DP = 8f
    const val DIGIT_HEIGHT_DP = 15f
    const val SPACING_DP = 1f
    const val HEADER_TEXT_SP = 16f
    /** Fixed header band so composite bitmap height matches layout dimens. */
    const val HEADER_LINE_DP = 17f
    const val HEADER_ROW_GAP_DP = 5f
    const val VERTICAL_PAD_DP = 1f
    /** Lit-segment glow extends past slot bounds; keep off the bitmap edges. */
    const val HORIZONTAL_BLEED_DP = 4f

    fun rowWidthDp(): Float =
        SevenSegmentStatusRowLayout.widthDp(DIGIT_WIDTH_DP, SPACING_DP)

    fun compositeWidthDp(): Float = rowWidthDp() + HORIZONTAL_BLEED_DP * 2f

    fun compositeHeightDp(): Float =
        VERTICAL_PAD_DP * 2f + HEADER_LINE_DP + HEADER_ROW_GAP_DP + DIGIT_HEIGHT_DP
}
