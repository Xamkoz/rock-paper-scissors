package com.rpsonline.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rpsonline.app.data.monitoring.NetworkDataActivityKind
import com.rpsonline.app.ui.segment.SegmentLayout
import com.rpsonline.app.ui.segment.SegmentedSpinnerSteps
import com.rpsonline.app.ui.segment.SevenSegmentColonLayout
import com.rpsonline.app.ui.segment.SevenSegmentGeometry
import com.rpsonline.app.ui.segment.SevenSegmentPainter
import com.rpsonline.app.ui.segment.asSevenSegmentTarget
import com.rpsonline.app.ui.theme.isRpsDarkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Active Firebase I/O kinds for static bridge-slot segment overlays. */
val LocalNetworkActivityKinds = compositionLocalOf { emptySet<NetworkDataActivityKind>() }

/** Connection probe tiles the connection pattern on all digit slots when no queue I/O is active. */
val LocalConnectionProbeActive = compositionLocalOf { false }

/** Index of the current slot in the 12-position top-bar row (-1 = sync all slots). */
val LocalSegmentedDisplayPulseSlotIndex = compositionLocalOf { -1 }

/**
 * Top-bar layout (12 slots, 0-based):
 * - Slots 0–3 / digits 1–4: online player count
 * - Slots 4–6 / digits 5–7: network activity + spinner (blank, spinner, blank)
 * - Slots 7–11 / digits 8–11 + colon: MM:SS (slots 7–8 MM, 9 colon, 10–11 SS)
 */
const val TopBarSegmentedSlotCount = 12

const val TopBarOnlineCountSlotStart = 0
const val TopBarOnlineCountSlotEnd = 3

const val TopBarTimerDigitsSlotStart = 7
const val TopBarColonSlotIndex = TopBarTimerDigitsSlotStart + 2
/** Bridge spinner digit — network bursts never overlay this slot. */
const val TopBarSpinnerDigitSlotIndex = 5

/** Network I/O half-lit overlays on every digit slot (4 + 3 + 4), excluding the colon. */
val TopBarNetworkActivitySlotIndices: Set<Int> =
    (0 until TopBarSegmentedSlotCount).filter { it != TopBarColonSlotIndex }.toSet()

/** Burst overlay slots: all digit slots except the colon and bridge spinner. */
val TopBarNetworkBurstSlotIndices: Set<Int> =
    TopBarNetworkActivitySlotIndices.filter { it != TopBarSpinnerDigitSlotIndex }.toSet()

fun isNetworkBurstSlot(slotIndex: Int): Boolean = slotIndex in TopBarNetworkBurstSlotIndices

/** @deprecated Use [TopBarNetworkActivitySlotIndices]. */
val TopBarDataBridgeSlotIndices: Set<Int> = TopBarNetworkActivitySlotIndices

fun isBridgePulseSlot(slotIndex: Int): Boolean = slotIndex in TopBarNetworkActivitySlotIndices

/** Side verticals — upper pair (`f`/`b`) and lower pair (`e`/`c`) in [SevenSegmentGeometry]. */
val NetworkBurstVerticalSegments: Set<Char> = setOf('b', 'c', 'e', 'f')

/** Top, middle, and bottom bars (`a` top; `g` middle; `d` bottom in [SevenSegmentGeometry]). */
val NetworkBurstHorizontalSegments: Set<Char> = setOf('a', 'g', 'd')

private const val BurstTopBar = 'a'
private const val BurstMiddleBar = 'g'
private const val BurstBottomBar = 'd'

/** Per-digit burst segments; identical on every burst digit slot except the colon and spinner. */
fun networkActivityBurstSegments(kind: NetworkDataActivityKind): Set<Char> =
    networkActivityBurstSegmentsByKind[kind].orEmpty()

/**
 * Symmetric bar + vertical combinations (no left/right mirroring):
 * - Connection: top bar
 * - Match: bottom bar
 * - Presence: middle bar
 * - Queue: all vertical bars
 */
private val networkActivityBurstSegmentsByKind: Map<NetworkDataActivityKind, Set<Char>> = mapOf(
    NetworkDataActivityKind.Connection to setOf(BurstTopBar),
    NetworkDataActivityKind.Match to setOf(BurstBottomBar),
    NetworkDataActivityKind.Presence to setOf(BurstMiddleBar),
    NetworkDataActivityKind.Queue to NetworkBurstVerticalSegments,
)

/** Union of all segments this kind lights across every overlay digit slot. */
fun networkActivityHalfLitSegments(kind: NetworkDataActivityKind): Set<Char> =
    TopBarNetworkBurstSlotIndices
        .flatMap { slot -> networkActivitySlotHalfLitSegments(kind, slot) }
        .toSet()

fun networkActivitySlotHalfLitSegments(
    kind: NetworkDataActivityKind,
    slotIndex: Int,
): Set<Char> {
    if (!isNetworkBurstSlot(slotIndex)) return emptySet()
    return networkActivityBurstSegments(kind)
}

fun bridgeSlotNetworkHalfLitSegments(
    slotIndex: Int,
    activeKinds: Set<NetworkDataActivityKind>,
    connectionProbeActive: Boolean,
): Set<Char> {
    if (!isNetworkBurstSlot(slotIndex)) return emptySet()
    val kinds = activeNetworkBurstKinds(activeKinds, connectionProbeActive)
    if (kinds.isEmpty()) return emptySet()

    val kindsBySegment = mutableMapOf<Char, MutableSet<NetworkDataActivityKind>>()
    for (kind in kinds) {
        for (segment in networkActivityBurstSegments(kind)) {
            kindsBySegment.getOrPut(segment) { mutableSetOf() }.add(kind)
        }
    }
    return kindsBySegment.filterValues { it.size == 1 }.keys
}

/** Active burst kinds for a slot, including connection probe when eligible. */
fun activeNetworkBurstKinds(
    activeKinds: Set<NetworkDataActivityKind>,
    connectionProbeActive: Boolean,
): Set<NetworkDataActivityKind> {
    val kinds = activeKinds.toMutableSet()
    if (
        connectionProbeActive &&
        NetworkDataActivityKind.Queue !in kinds &&
        NetworkDataActivityKind.Connection !in kinds
    ) {
        kinds += NetworkDataActivityKind.Connection
    }
    return kinds
}

/** Provides network I/O overlays for bridge slots on the top-bar segmented display. */
@Composable
fun SegmentedDisplayPulseEffect(
    activeNetworkKinds: Set<NetworkDataActivityKind> = emptySet(),
    connectionProbeActive: Boolean = false,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalNetworkActivityKinds provides activeNetworkKinds,
        LocalConnectionProbeActive provides connectionProbeActive,
    ) {
        content()
    }
}

/** Default seven-segment digit size for top-bar indicators. */
val SegmentedDigitWidth = 11.dp
val SegmentedDigitHeight = 18.dp
val SegmentedDisplayHeight = 36.dp
/** Slightly narrower digits so the full top-bar row fits without clipping. */
internal val TopBarSegmentedDigitWidth = 10.dp
/** Fixed top-bar digit height — width scales to fill the ear; height stays compact. */
internal val TopBarSegmentedDigitHeight = 16.dp
internal val SegmentedDigitSpacing = 1.dp

private fun colonSlotWidth(digitWidth: Dp): Dp =
    digitWidth * SevenSegmentColonLayout.WIDTH_RATIO

/** Glow extends past digit bounds; keep the scaled row inside the top-bar ear. */
private val TopBarSegmentGlowBleedDp = 2.dp

/** Scale top-bar status digits to fill [containerWidth] while preserving layout ratios. */
fun computeTopBarStatusDigitWidth(containerWidth: Dp): Dp {
    val spacing = SegmentedDigitSpacing
    val availableWidth = containerWidth - TopBarSegmentGlowBleedDp
    if (availableWidth <= spacing * 11) return TopBarSegmentedDigitWidth
    return ((availableWidth - spacing * 11) / (11f + SevenSegmentColonLayout.WIDTH_RATIO))
        .coerceAtLeast(TopBarSegmentedDigitWidth)
}

@Composable
private fun SegmentedPulseSlotIndex(
    slotIndex: Int,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalSegmentedDisplayPulseSlotIndex provides slotIndex) {
        content()
    }
}

@Composable
private fun WithOptionalPulseSlotIndex(
    slotIndex: Int,
    content: @Composable () -> Unit,
) {
    if (slotIndex >= 0) {
        SegmentedPulseSlotIndex(slotIndex, content)
    } else {
        content()
    }
}

@Composable
private fun sevenSegmentGhostColor(): Color {
    val scheme = MaterialTheme.colorScheme
    return if (isRpsDarkTheme()) {
        lerp(
            lerp(scheme.surfaceContainerLow, scheme.surface, 0.38f),
            scheme.outlineVariant,
            0.06f,
        )
    } else {
        lerp(scheme.surfaceContainerHigh, scheme.outlineVariant, 0.36f)
            .copy(alpha = 0.87f)
    }
}

@Composable
private fun sevenSegmentLitColor(): Color {
    val scheme = MaterialTheme.colorScheme
    return if (isRpsDarkTheme()) {
        lerp(scheme.primary, scheme.onPrimaryContainer, 0.48f)
    } else {
        lerp(scheme.primary, scheme.onPrimaryContainer, 0.12f)
    }
}

@Composable
private fun sevenSegmentHalfLitColor(): Color {
    return lerp(sevenSegmentGhostColor(), sevenSegmentLitColor(), 0.58f)
}

/** Ghost segment color for top-bar controls (shared with seven-segment displays). */
@Composable
fun segmentedDisplayGhostColor(): Color = sevenSegmentGhostColor()

/** Lit segment color for top-bar controls (shared with seven-segment displays). */
@Composable
fun segmentedDisplayLitColor(): Color = sevenSegmentLitColor()

@Composable
fun SevenSegmentBlankSlot(
    modifier: Modifier = Modifier,
    digitWidth: Dp = SegmentedDigitWidth,
    digitHeight: Dp = SegmentedDigitHeight,
) {
    val ghost = sevenSegmentGhostColor()
    SegmentedDisplayPulseSlot(
        offColor = ghost,
        digitWidth = digitWidth,
        digitHeight = digitHeight,
        modifier = modifier,
    )
}

@Composable
private fun SevenSegmentValuePulseSlot(
    digit: Char,
    isLeadingZero: Boolean,
    offColor: Color,
    digitWidth: Dp,
    digitHeight: Dp,
) {
    val segments = SevenSegmentGeometry.segmentsFor(digit)
    SegmentedDisplayPulseSlot(
        offColor = offColor,
        fullLitSegments = if (isLeadingZero) emptySet() else segments,
        halfLitSegments = if (isLeadingZero) segments else emptySet(),
        digitWidth = digitWidth,
        digitHeight = digitHeight,
    )
}

@Composable
fun FourDigitSegmentedDisplay(
    value: Int?,
    modifier: Modifier = Modifier,
    digitWidth: Dp = SegmentedDigitWidth,
    digitHeight: Dp = SegmentedDigitHeight,
    baseSlotIndex: Int = -1,
) {
    val offColor = sevenSegmentGhostColor()

    if (value == null) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(SegmentedDigitSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(4) { index ->
                WithOptionalPulseSlotIndex(
                    if (baseSlotIndex >= 0) baseSlotIndex + index else -1,
                ) {
                    SegmentedDisplayPulseSlot(
                        offColor = offColor,
                        digitWidth = digitWidth,
                        digitHeight = digitHeight,
                    )
                }
            }
        }
        return
    }

    FourDigitCountSegmentedDisplay(
        value = value,
        modifier = modifier,
        digitWidth = digitWidth,
        digitHeight = digitHeight,
        baseSlotIndex = baseSlotIndex,
    )
}

/** All four digits fully half-lit — disconnected / server unreachable. */
@Composable
fun FourDigitOfflineSegmentedDisplay(
    modifier: Modifier = Modifier,
    digitWidth: Dp = SegmentedDigitWidth,
    digitHeight: Dp = SegmentedDigitHeight,
    baseSlotIndex: Int = -1,
) {
    val offColor = sevenSegmentGhostColor()
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(SegmentedDigitSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(4) { index ->
            WithOptionalPulseSlotIndex(
                if (baseSlotIndex >= 0) baseSlotIndex + index else -1,
            ) {
                SegmentedDisplayPulseSlot(
                    offColor = offColor,
                    dimAllSegments = true,
                    digitWidth = digitWidth,
                    digitHeight = digitHeight,
                )
            }
        }
    }
}

@Composable
private fun FourDigitCountSegmentedDisplay(
    value: Int,
    modifier: Modifier = Modifier,
    digitWidth: Dp = SegmentedDigitWidth,
    digitHeight: Dp = SegmentedDigitHeight,
    baseSlotIndex: Int = -1,
) {
    val offColor = sevenSegmentGhostColor()
    val clamped = value.coerceIn(0, 9_999)
    val digits = clamped.toString().padStart(4, '0')
    val firstSignificantIndex = digits.indexOfFirst { it != '0' }.let { index ->
        if (index < 0) 3 else index
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(SegmentedDigitSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        digits.forEachIndexed { index, char ->
            WithOptionalPulseSlotIndex(
                if (baseSlotIndex >= 0) baseSlotIndex + index else -1,
            ) {
                SevenSegmentValuePulseSlot(
                    digit = char,
                    isLeadingZero = index < firstSignificantIndex,
                    offColor = offColor,
                    digitWidth = digitWidth,
                    digitHeight = digitHeight,
                )
            }
        }
    }
}

@Composable
fun ThreeDigitSegmentedDisplay(
    value: Int?,
    modifier: Modifier = Modifier,
    digitWidth: Dp = SegmentedDigitWidth,
    digitHeight: Dp = SegmentedDigitHeight,
) {
    val offColor = sevenSegmentGhostColor()

    if (value == null) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(SegmentedDigitSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) {
                SegmentedDisplayPulseSlot(
                    offColor = offColor,
                    digitWidth = digitWidth,
                    digitHeight = digitHeight,
                )
            }
        }
        return
    }

    val clamped = value.coerceIn(0, 999)
    val digits = clamped.toString().padStart(3, '0')
    val firstSignificantIndex = digits.indexOfFirst { it != '0' }.let { index ->
        if (index < 0) 2 else index
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(SegmentedDigitSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        digits.forEachIndexed { index, char ->
            SevenSegmentValuePulseSlot(
                digit = char,
                isLeadingZero = index < firstSignificantIndex,
                offColor = offColor,
                digitWidth = digitWidth,
                digitHeight = digitHeight,
            )
        }
    }
}

/** @see com.rpsonline.app.ui.segment.SegmentedSpinnerStyle */
typealias SegmentedSpinnerStyle = com.rpsonline.app.ui.segment.SegmentedSpinnerStyle

/** Spinner segment + four digits for queue elapsed time (MM:SS). */
@Composable
fun QueueTimeSegmentedDisplay(
    elapsedSeconds: Long,
    modifier: Modifier = Modifier,
    digitWidth: Dp = SegmentedDigitWidth,
    digitHeight: Dp = SegmentedDigitHeight,
    litColor: Color = sevenSegmentLitColor(),
    offColor: Color = sevenSegmentGhostColor(),
    showLiveTime: Boolean = true,
    animateSpinner: Boolean = true,
    spinnerStyle: SegmentedSpinnerStyle = SegmentedSpinnerStyle.QUEUE,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpinningSevenSegmentPulseSlot(
            offColor = offColor,
            digitWidth = digitWidth,
            digitHeight = digitHeight,
            animate = animateSpinner,
            style = spinnerStyle,
        )
        SpacerBetweenSegments()
        SevenSegmentBlankSlot(
            digitWidth = digitWidth,
            digitHeight = digitHeight,
        )
        SpacerBetweenSegments()
        QueueTimerDigitsSegmentedDisplay(
            elapsedSeconds = elapsedSeconds,
            showLiveTime = showLiveTime,
            digitWidth = digitWidth,
            digitHeight = digitHeight,
            litColor = litColor,
            offColor = offColor,
        )
    }
}

/**
 * Top bar: digits 1–4 count, digits 5–7 network/spinner, digits 8–11 MM:SS timer.
 */
@Composable
fun TopBarSegmentedStatusRow(
    onlineCount: TopBarOnlineCountDisplay,
    inMatch: Boolean,
    inQueue: Boolean,
    elapsedSeconds: Long,
    playerClockStopped: Boolean = false,
    modifier: Modifier = Modifier,
    digitWidth: Dp = TopBarSegmentedDigitWidth,
    digitHeight: Dp = SegmentedDigitHeight,
) {
    val offColor = sevenSegmentGhostColor()
    val showLiveTime = inQueue || inMatch
    val animateSpinner = inQueue || inMatch
    val spinnerStyle = when {
        !inMatch -> SegmentedSpinnerStyle.QUEUE
        playerClockStopped -> SegmentedSpinnerStyle.MATCH_CLOCK_STOPPED
        else -> SegmentedSpinnerStyle.MATCH
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (onlineCount) {
            TopBarOnlineCountDisplay.Offline -> {
                FourDigitOfflineSegmentedDisplay(
                    digitWidth = digitWidth,
                    digitHeight = digitHeight,
                    baseSlotIndex = 0,
                )
            }
            TopBarOnlineCountDisplay.Loading -> {
                FourDigitSegmentedDisplay(
                    value = null,
                    digitWidth = digitWidth,
                    digitHeight = digitHeight,
                    baseSlotIndex = 0,
                )
            }
            is TopBarOnlineCountDisplay.Value -> {
                FourDigitSegmentedDisplay(
                    value = onlineCount.count,
                    digitWidth = digitWidth,
                    digitHeight = digitHeight,
                    baseSlotIndex = 0,
                )
            }
        }
        SpacerBetweenSegments()
        SegmentedPulseSlotIndex(4) {
            SevenSegmentBlankSlot(
                digitWidth = digitWidth,
                digitHeight = digitHeight,
            )
        }
        SpacerBetweenSegments()
        SegmentedPulseSlotIndex(5) {
            SpinningSevenSegmentPulseSlot(
                offColor = offColor,
                digitWidth = digitWidth,
                digitHeight = digitHeight,
                animate = animateSpinner,
                style = spinnerStyle,
            )
        }
        SpacerBetweenSegments()
        SegmentedPulseSlotIndex(6) {
            SevenSegmentBlankSlot(
                digitWidth = digitWidth,
                digitHeight = digitHeight,
            )
        }
        SpacerBetweenSegments()
        QueueTimerDigitsSegmentedDisplay(
            elapsedSeconds = elapsedSeconds,
            showLiveTime = showLiveTime,
            digitWidth = digitWidth,
            digitHeight = digitHeight,
            baseSlotIndex = TopBarTimerDigitsSlotStart,
            colonSlotIndex = TopBarTimerDigitsSlotStart + 2,
        )
    }
}

@Composable
private fun QueueTimerDigitsSegmentedDisplay(
    elapsedSeconds: Long,
    showLiveTime: Boolean,
    digitWidth: Dp,
    digitHeight: Dp,
    baseSlotIndex: Int = -1,
    colonSlotIndex: Int = -1,
    litColor: Color = sevenSegmentLitColor(),
    offColor: Color = sevenSegmentGhostColor(),
) {
    val totalSeconds = elapsedSeconds.coerceAtLeast(0)
    val minutes = (totalSeconds / 60).coerceAtMost(99)
    val seconds = (totalSeconds % 60).coerceAtMost(59)
    val digits = "%02d%02d".format(minutes, seconds).toList()
    val firstSignificantIndex = digits.indexOfFirst { it != '0' }.let { index ->
        if (index < 0) digits.lastIndex else index
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(SegmentedDigitSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        digits.forEachIndexed { index, digit ->
            if (index == 2) {
                SegmentedColonPulseSlot(
                    lit = showLiveTime,
                    litColor = litColor,
                    offColor = offColor,
                    digitWidth = digitWidth,
                    digitHeight = digitHeight,
                    slotIndex = colonSlotIndex,
                )
            }
            val slotIndex = if (baseSlotIndex >= 0) {
                baseSlotIndex + index + if (index >= 2) 1 else 0
            } else {
                -1
            }
            WithOptionalPulseSlotIndex(slotIndex) {
                if (showLiveTime) {
                    SevenSegmentValuePulseSlot(
                        digit = digit,
                        isLeadingZero = index < firstSignificantIndex,
                        offColor = offColor,
                        digitWidth = digitWidth,
                        digitHeight = digitHeight,
                    )
                } else {
                    SegmentedDisplayPulseSlot(
                        offColor = offColor,
                        digitWidth = digitWidth,
                        digitHeight = digitHeight,
                    )
                }
            }
        }
    }
}

@Composable
private fun SegmentedDisplayPulseSlot(
    offColor: Color,
    digitWidth: Dp,
    digitHeight: Dp,
    modifier: Modifier = Modifier,
    fullLitSegments: Set<Char> = emptySet(),
    halfLitSegments: Set<Char> = emptySet(),
    dimAllSegments: Boolean = false,
) {
    val slotIndex = LocalSegmentedDisplayPulseSlotIndex.current
    val networkHalfLit = if (isBridgePulseSlot(slotIndex)) {
        bridgeSlotNetworkHalfLitSegments(
            slotIndex = slotIndex,
            activeKinds = LocalNetworkActivityKinds.current,
            connectionProbeActive = LocalConnectionProbeActive.current,
        )
    } else {
        emptySet()
    }
    val combinedHalfLit = (halfLitSegments + networkHalfLit) - fullLitSegments

    SevenSegmentDisplayWithPulse(
        fullLitSegments = fullLitSegments,
        halfLitSegments = combinedHalfLit,
        dimAllSegments = dimAllSegments,
        offColor = offColor,
        modifier = modifier.size(digitWidth, digitHeight),
    )
}

@Composable
private fun SegmentedColonPulseSlot(
    lit: Boolean,
    litColor: Color,
    offColor: Color,
    digitWidth: Dp,
    digitHeight: Dp,
    modifier: Modifier = Modifier,
    slotIndex: Int = -1,
) {
    val colonWidth = colonSlotWidth(digitWidth)
    val pipColor = if (lit) litColor else offColor
    WithOptionalPulseSlotIndex(slotIndex) {
        SevenSegmentTimeColon(
            color = pipColor,
            lit = lit,
            digitHeight = digitHeight,
            modifier = modifier.size(width = colonWidth, height = digitHeight),
        )
    }
}

@Composable
private fun SevenSegmentDisplayWithPulse(
    fullLitSegments: Set<Char>,
    halfLitSegments: Set<Char>,
    dimAllSegments: Boolean,
    offColor: Color,
    modifier: Modifier = Modifier,
) {
    val fullLitColor = sevenSegmentLitColor()
    val halfLitColor = sevenSegmentHalfLitColor()
    val dimColor = lerp(offColor, halfLitColor, 0.42f)
    Canvas(modifier = modifier.padding(horizontal = 1.dp)) {
        val layout = SevenSegmentGeometry.layout(size.width, size.height)
        for (segment in layout) {
            when {
                segment.id in fullLitSegments -> drawSevenSegmentLit(segment, fullLitColor)
                segment.id in halfLitSegments -> drawSevenSegment(segment, halfLitColor)
                dimAllSegments -> drawSevenSegment(segment, dimColor)
                else -> drawSevenSegment(segment, offColor)
            }
        }
    }
}

@Composable
private fun SpacerBetweenSegments() {
    Spacer(modifier = Modifier.width(SegmentedDigitSpacing))
}

/** Colon pips only — no full digit ghost slot. */
@Composable
private fun SevenSegmentTimeColon(
    color: Color,
    digitHeight: Dp,
    lit: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val centerX = size.width / 2f
        drawColonPip(Offset(centerX, size.height * 0.36f), color, lit)
        drawColonPip(Offset(centerX, size.height * 0.64f), color, lit)
    }
}

private fun DrawScope.drawColonPip(center: Offset, color: Color, lit: Boolean) {
    val width = size.width * 0.55f
    val height = width * 0.5f
    if (!lit) {
        drawRoundRect(
            color = color,
            topLeft = Offset(center.x - width / 2f, center.y - height / 2f),
            size = Size(width, height),
            cornerRadius = CornerRadius(height / 2f, height / 2f),
        )
        return
    }
    val glowLayers = listOf(
        Triple(0.24f, 0.16f, 0.98f),
        Triple(0.12f, 0.28f, 1.0f),
        Triple(0.05f, 0.42f, 1.0f),
    )
    glowLayers.forEach { (inflate, alpha, scale) ->
        val glowW = width + height * inflate * 2f
        val glowH = height * scale + height * inflate * 0.45f
        drawRoundRect(
            color = color.copy(alpha = alpha),
            topLeft = Offset(center.x - glowW / 2f, center.y - glowH / 2f),
            size = Size(glowW, glowH),
            cornerRadius = CornerRadius(glowH / 2f, glowH / 2f),
        )
    }
    val coreH = height * 1.12f
    val coreW = width * 1.04f
    drawRoundRect(
        color = color,
        topLeft = Offset(center.x - coreW / 2f, center.y - coreH / 2f),
        size = Size(coreW, coreH),
        cornerRadius = CornerRadius(coreH / 2f, coreH / 2f),
    )
}

@Composable
private fun SpinningSevenSegmentPulseSlot(
    offColor: Color,
    digitWidth: Dp,
    digitHeight: Dp,
    animate: Boolean = true,
    style: SegmentedSpinnerStyle = SegmentedSpinnerStyle.QUEUE,
    modifier: Modifier = Modifier,
) {
    val steps = SegmentedSpinnerSteps.steps(style)
    val stepDelayMs = SegmentedSpinnerSteps.stepDelayMs(style)
    var step by remember(style) { mutableIntStateOf(0) }
    LaunchedEffect(animate, style) {
        if (!animate) {
            step = 0
            return@LaunchedEffect
        }
        var currentStep = 0
        while (true) {
            delay(stepDelayMs)
            currentStep = (currentStep + 1) % steps.size
            step = currentStep
        }
    }
    val spinnerHalfLit = if (animate) steps[step] else emptySet()
    SegmentedDisplayPulseSlot(
        offColor = offColor,
        halfLitSegments = spinnerHalfLit,
        digitWidth = digitWidth,
        digitHeight = digitHeight,
        modifier = modifier,
    )
}

private fun DrawScope.drawSevenSegment(
    segment: SegmentLayout,
    color: Color,
    inflate: Float = 0f,
    thicknessScale: Float = 1f,
) {
    SevenSegmentPainter.drawSegment(
        target = asSevenSegmentTarget(),
        slotLeft = 0f,
        slotTop = 0f,
        segment = segment,
        colorArgb = color.toArgb(),
        inflateFactor = inflate,
        thicknessScale = thicknessScale,
    )
}

private fun DrawScope.drawSevenSegmentLit(segment: SegmentLayout, color: Color) {
    SevenSegmentPainter.drawLitSegment(
        target = asSevenSegmentTarget(),
        slotLeft = 0f,
        slotTop = 0f,
        segment = segment,
        colorArgb = color.toArgb(),
    )
}
