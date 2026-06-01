package com.rpsonline.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.keyframes
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
import androidx.compose.runtime.mutableFloatStateOf
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
import com.rpsonline.app.data.model.Move
import com.rpsonline.app.data.monitoring.NetworkDataActivityKind
import com.rpsonline.app.ui.segment.SegmentLayout
import com.rpsonline.app.ui.segment.SegmentedSpinnerSteps
import com.rpsonline.app.ui.segment.SevenSegmentColonLayout
import com.rpsonline.app.ui.segment.SevenSegmentGeometry
import com.rpsonline.app.ui.segment.SevenSegmentPainter
import com.rpsonline.app.ui.segment.asSevenSegmentTarget
import com.rpsonline.app.ui.theme.isRpsDarkTheme
import com.rpsonline.app.ui.util.LocalSegmentedDisplayPulseMove
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Current half-lit pulse blend for round-resolution bursts (0 = normal). */
val LocalResolutionPulseAlpha = compositionLocalOf { 0f }

/** Fill progress for round-resolution bursts. */
val LocalResolutionPulseFill = compositionLocalOf { 0f }

/** Active Firebase I/O kinds for static bridge-slot segment overlays. */
val LocalNetworkActivityKinds = compositionLocalOf { emptySet<NetworkDataActivityKind>() }

/** Connection probe lights the connection pattern on bridge slot 4 when no queue I/O is active. */
val LocalConnectionProbeActive = compositionLocalOf { false }

/** @deprecated Use [LocalResolutionPulseAlpha]. */
val LocalSegmentedDisplayPulseAlpha = LocalResolutionPulseAlpha

/** @deprecated Use [LocalResolutionPulseFill]. */
val LocalSegmentedDisplayPulseFill = LocalResolutionPulseFill

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

/** Network I/O half-lit overlays: blank, spinner, blank (digits 5–7). */
val TopBarNetworkActivitySlotIndices: Set<Int> = setOf(4, 5, 6)

const val TopBarTimerDigitsSlotStart = 7

/** @deprecated Use [TopBarNetworkActivitySlotIndices]. */
val TopBarDataBridgeSlotIndices: Set<Int> = TopBarNetworkActivitySlotIndices

private const val ResolutionPulseDurationMs = 520
/** Fill sequence completes quickly so segments reach half-lit sooner. */
private const val ResolutionPulseFillCompleteAtMs = 240
private const val ResolutionPulseAlphaPeakAtMs = 180
private const val ResolutionPulseHoldUntilMs = 400

private fun resolutionPulseFillAnimationSpec(move: Move) = keyframes {
    durationMillis = ResolutionPulseDurationMs
    val stepCount = resolutionBurstFillSequence(move).size
    if (stepCount <= 1) {
        0f at 0
        1f at ResolutionPulseFillCompleteAtMs
        1f at ResolutionPulseDurationMs
        return@keyframes
    }
    for (index in 0 until stepCount) {
        val progress = index.toFloat() / (stepCount - 1)
        val stepStartMs = (ResolutionPulseFillCompleteAtMs * index / (stepCount - 1)).toInt()
        val stepEndMs = if (index < stepCount - 1) {
            (ResolutionPulseFillCompleteAtMs * (index + 1) / (stepCount - 1)).toInt() - 1
        } else {
            ResolutionPulseDurationMs
        }
        progress at stepStartMs
        if (index < stepCount - 1) {
            progress at stepEndMs.coerceAtLeast(stepStartMs)
        }
    }
    1f at ResolutionPulseDurationMs
}

private val allSevenSegments = setOf('a', 'b', 'c', 'd', 'e', 'f', 'g')

private fun cumulativeFillSteps(additions: List<Set<Char>>): List<Set<Char>> {
    var accumulated = emptySet<Char>()
    return additions.map { step ->
        accumulated = accumulated + step
        accumulated
    }
}

/** Move-specific fill steps; each step adds segments until all are lit at peak. */
fun resolutionBurstFillSequence(move: Move): List<Set<Char>> = when (move) {
    Move.ROCK -> cumulativeFillSteps(
        listOf(
            setOf('g'),
            setOf('a', 'd'),
            setOf('f'),
            setOf('b'),
            setOf('e'),
            setOf('c'),
        ),
    )
    Move.PAPER -> cumulativeFillSteps(
        listOf(
            setOf('a'),
            setOf('f', 'b'),
            setOf('g'),
            setOf('d'),
            setOf('e'),
            setOf('c'),
        ),
    )
    Move.SCISSORS -> cumulativeFillSteps(
        listOf(
            setOf('f', 'b'),
            setOf('e', 'c'),
            setOf('g'),
            setOf('a'),
            setOf('d'),
        ),
    )
}

/**
 * Move-specific activation order across the 12 top-bar slots.
 * 0–3 count, 4 blank, 5 spinner, 6 blank, 7–8 MM, 9 colon, 10–11 SS.
 */
fun resolutionBurstSlotActivationOrder(move: Move): List<Int> = when (move) {
    Move.ROCK -> listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)
    Move.PAPER -> listOf(5, 6, 4, 7, 3, 8, 2, 9, 1, 10, 0, 11)
    Move.SCISSORS -> listOf(0, 11, 1, 10, 2, 9, 3, 8, 4, 7, 5, 6)
}

private val cachedBurstFillSequenceByMove =
    Move.entries.associateWith(::resolutionBurstFillSequence)

private val cachedBurstSlotActivationOrderByMove =
    Move.entries.associateWith(::resolutionBurstSlotActivationOrder)

/** slotIndex → position in activation order (-1 if not in sweep). */
private val cachedBurstSlotPositionByMove: Map<Move, IntArray> =
    Move.entries.associateWith { move ->
        val order = cachedBurstSlotActivationOrderByMove.getValue(move)
        IntArray(TopBarSegmentedSlotCount) { slotIndex -> order.indexOf(slotIndex) }
    }

private const val ResolutionPulseInactiveThreshold = 0.001f

/** Per-slot fill progress for a global burst progress in [0, 1]. */
fun resolutionBurstSlotFillProgress(
    globalFillProgress: Float,
    slotIndex: Int,
    move: Move,
): Float {
    if (globalFillProgress <= 0f) return 0f
    if (globalFillProgress >= 1f) return 1f

    val fillComplete = ResolutionPulseFillCompleteAtMs.toFloat() / ResolutionPulseDurationMs
    if (globalFillProgress >= fillComplete) return 1f

    val orderSize = cachedBurstSlotActivationOrderByMove.getValue(move).size
    val position = if (slotIndex in 0 until TopBarSegmentedSlotCount) {
        cachedBurstSlotPositionByMove.getValue(move)[slotIndex]
    } else {
        -1
    }
    if (position < 0) return globalFillProgress / fillComplete

    val normalizedGlobal = (globalFillProgress / fillComplete).coerceIn(0f, 1f)
    val slotCenter = (position + 0.5f) / orderSize
    val slotWidth = 1.55f / orderSize
    val distance = kotlin.math.abs(normalizedGlobal - slotCenter)
    return (1f - distance / slotWidth).coerceIn(0f, 1f)
}

/** Effective segment fill progress for one display slot. */
fun resolutionBurstEffectiveFillProgress(
    globalFillProgress: Float,
    slotIndex: Int,
    move: Move,
): Float {
    if (slotIndex < 0) return globalFillProgress.coerceIn(0f, 1f)
    val slotProgress = resolutionBurstSlotFillProgress(globalFillProgress, slotIndex, move)
    if (slotProgress <= 0f) return 0f
    if (slotProgress >= 1f) return 1f
    val sequence = cachedBurstFillSequenceByMove.getValue(move)
    val phase = (slotIndex % sequence.size) / sequence.size.toFloat() * 0.22f
    return (slotProgress + phase).coerceIn(0f, 1f)
}

/** Segments lit at burst fill progress in [0, 1]; peak uses all segments. */
fun resolutionBurstSegmentsAtProgress(
    move: Move,
    fillProgress: Float,
    slotIndex: Int = -1,
): Set<Char> {
    if (fillProgress <= 0f) return emptySet()
    if (fillProgress >= 1f) return allSevenSegments

    val sequence = cachedBurstFillSequenceByMove.getValue(move)
    val fillCompleteProgress = ResolutionPulseFillCompleteAtMs.toFloat() / ResolutionPulseDurationMs

    if (slotIndex < 0 && fillProgress >= fillCompleteProgress) {
        return sequence.last()
    }

    val effectiveProgress = resolutionBurstEffectiveFillProgress(fillProgress, slotIndex, move)
    if (effectiveProgress <= 0f) return emptySet()
    if (effectiveProgress >= 1f || fillProgress >= fillCompleteProgress) {
        return sequence.last()
    }

    val index = (effectiveProgress * (sequence.size - 1))
        .toInt()
        .coerceIn(0, sequence.lastIndex)
    return sequence[index]
}

/** Burst segments that may be half-lit without touching protected full-lit segments. */
fun resolutionBurstSegmentsExcluding(
    move: Move,
    progress: Float,
    protectedSegments: Set<Char>,
    slotIndex: Int = -1,
): Set<Char> = resolutionBurstSegmentsAtProgress(move, progress, slotIndex) - protectedSegments

fun isBridgePulseSlot(slotIndex: Int): Boolean = slotIndex in TopBarNetworkActivitySlotIndices

/** Mirror outer-blank segments (f↔b, e↔c; a and g stay on both sides). */
private fun mirrorOuterSegments(left: Set<Char>): Set<Char> =
    left.map { segment ->
        when (segment) {
            'f' -> 'b'
            'e' -> 'c'
            else -> segment
        }
    }.toSet()

/** Blank | spinner | blank — center-symmetric; spinner never uses top/bottom/middle (`a`/`d`/`g`). */
private const val TopBarNetworkLeftSlot = 4
private const val TopBarNetworkSpinnerSlot = 5
private const val TopBarNetworkRightSlot = 6

private val spinnerForbiddenSegments = setOf('a', 'd', 'g')
private val spinnerAllowedSegments = setOf('b', 'c', 'e', 'f')
private val spinnerLeftVerts = setOf('f', 'e')
private val spinnerRightVerts = setOf('b', 'c')

/** Left (`f`/`e`) and right (`b`/`c`) verticals on the spinner light as pairs. */
fun ensureSpinnerSideVertsTogether(segments: Set<Char>): Set<Char> {
    var result = segments
    if (result.any { it in spinnerLeftVerts }) {
        result += spinnerLeftVerts
    }
    if (result.any { it in spinnerRightVerts }) {
        result += spinnerRightVerts
    }
    return result
}

/** Spinner digit: only side verticals; `a`/`d`/`g` belong on outer digits only. */
private fun resolveSpinnerBurstSegments(raw: Set<Char>): Set<Char> =
    ensureSpinnerSideVertsTogether(raw - spinnerForbiddenSegments)

private data class NetworkActivitySignature(
    /** Spinner slot: subset of `b`/`c`/`e`/`f` (center-symmetric pairs). */
    val spinner: Set<Char>,
    /** Left outer slot; right outer mirrors (`f`↔`b`, `e`↔`c`). May include `a`/`d`/`g`. */
    val leftOuter: Set<Char>,
)

private fun symmetricNetworkSlotPattern(signature: NetworkActivitySignature): Map<Int, Set<Char>> {
    val left = signature.leftOuter
    return mapOf(
        TopBarNetworkLeftSlot to left,
        TopBarNetworkSpinnerSlot to signature.spinner,
        TopBarNetworkRightSlot to mirrorOuterSegments(left),
    )
}

/**
 * Per-slot half-lit segments for each network I/O kind on slots 4–6 (digits 5–7).
 * Center-symmetric outers; spinner uses only `b`/`c`/`e`/`f`. Four patterns partition the
 * 14-segment burst universe with pairwise overlap of exactly two (slot, segment) pairs.
 */
private val networkActivitySlotPatterns: Map<NetworkDataActivityKind, Map<Int, Set<Char>>> = mapOf(
    NetworkDataActivityKind.Queue to symmetricNetworkSlotPattern(
        NetworkActivitySignature(spinner = setOf('b', 'c', 'e', 'f'), leftOuter = setOf('a', 'd', 'e', 'f')),
    ),
    NetworkDataActivityKind.Match to symmetricNetworkSlotPattern(
        NetworkActivitySignature(spinner = emptySet(), leftOuter = setOf('a', 'g')),
    ),
    NetworkDataActivityKind.Presence to symmetricNetworkSlotPattern(
        NetworkActivitySignature(spinner = emptySet(), leftOuter = setOf('d', 'g')),
    ),
    NetworkDataActivityKind.Connection to symmetricNetworkSlotPattern(
        NetworkActivitySignature(spinner = emptySet(), leftOuter = setOf('e', 'g')),
    ),
)

/** Union of all segments this kind lights across digits 5–7 (resolved per slot). */
fun networkActivityHalfLitSegments(kind: NetworkDataActivityKind): Set<Char> =
    TopBarNetworkActivitySlotIndices
        .flatMap { slot -> networkActivitySlotHalfLitSegments(kind, slot) }
        .toSet()

fun networkActivitySlotHalfLitSegments(
    kind: NetworkDataActivityKind,
    slotIndex: Int,
): Set<Char> {
    val raw = networkActivitySlotPatterns[kind]?.get(slotIndex).orEmpty()
    return if (slotIndex == TopBarNetworkSpinnerSlot) {
        resolveSpinnerBurstSegments(raw)
    } else {
        raw
    }
}

fun bridgeSlotNetworkHalfLitSegments(
    slotIndex: Int,
    activeKinds: Set<NetworkDataActivityKind>,
    connectionProbeActive: Boolean,
): Set<Char> {
    if (slotIndex !in TopBarNetworkActivitySlotIndices) return emptySet()
    val segments = mutableSetOf<Char>()
    for (kind in activeKinds) {
        segments += networkActivitySlotHalfLitSegments(kind, slotIndex)
    }
    if (
        connectionProbeActive &&
        NetworkDataActivityKind.Queue !in activeKinds &&
        NetworkDataActivityKind.Connection !in activeKinds
    ) {
        segments += networkActivitySlotHalfLitSegments(NetworkDataActivityKind.Connection, slotIndex)
    }
    return segments
}

/** Final move-specific shape one step before the all-segment peak. */
fun resolutionBurstSegments(move: Move): Set<Char> =
    resolutionBurstFillSequence(move).let { sequence ->
        if (sequence.size < 2) sequence.lastOrNull().orEmpty() else sequence[sequence.lastIndex - 1]
    }

/** Drives segmented pulses: resolution (all slots), network overlays (digits 5–7), connection probe. */
@Composable
fun SegmentedDisplayPulseEffect(
    resolutionPulseTrigger: Int,
    pulseMove: Move,
    activeNetworkKinds: Set<NetworkDataActivityKind> = emptySet(),
    connectionProbeActive: Boolean = false,
    content: @Composable () -> Unit,
) {
    var resolutionAlpha by remember { mutableFloatStateOf(0f) }
    var resolutionFill by remember { mutableFloatStateOf(0f) }
    var lastPulseTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(resolutionPulseTrigger) {
        if (resolutionPulseTrigger <= lastPulseTrigger) return@LaunchedEffect
        while (lastPulseTrigger < resolutionPulseTrigger) {
            lastPulseTrigger++
            try {
                resolutionAlpha = 0f
                resolutionFill = 0f
                coroutineScope {
                    val fillJob = launch {
                        animate(
                            initialValue = 0f,
                            targetValue = 0f,
                            animationSpec = resolutionPulseFillAnimationSpec(pulseMove),
                        ) { value, _ ->
                            resolutionFill = value
                        }
                    }
                    animate(
                        initialValue = 0f,
                        targetValue = 0f,
                        animationSpec = keyframes {
                            durationMillis = ResolutionPulseDurationMs
                            0f at 0
                            1f at ResolutionPulseAlphaPeakAtMs using LinearOutSlowInEasing
                            1f at ResolutionPulseHoldUntilMs
                            0f at ResolutionPulseDurationMs using FastOutSlowInEasing
                        },
                    ) { value, _ ->
                        resolutionAlpha = value
                    }
                    fillJob.join()
                }
            } finally {
                resolutionAlpha = 0f
                resolutionFill = 0f
            }
        }
    }

    CompositionLocalProvider(
        LocalResolutionPulseAlpha provides resolutionAlpha,
        LocalResolutionPulseFill provides resolutionFill,
        LocalNetworkActivityKinds provides activeNetworkKinds,
        LocalConnectionProbeActive provides connectionProbeActive,
        LocalSegmentedDisplayPulseMove provides pulseMove,
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

private data class AppliedSlotBurst(
    val segments: Set<Char>,
    val strength: Float,
    val uniformAlpha: Float = 0f,
)

@Composable
private fun resolveResolutionSlotBurst(
    slotIndex: Int,
    protectedSegments: Set<Char>,
): AppliedSlotBurst {
    val resolutionAlpha = LocalResolutionPulseAlpha.current
    if (resolutionAlpha <= ResolutionPulseInactiveThreshold) {
        return AppliedSlotBurst(emptySet(), 0f)
    }
    val resolutionFill = LocalResolutionPulseFill.current
    if (resolutionFill <= ResolutionPulseInactiveThreshold) {
        return AppliedSlotBurst(emptySet(), 0f)
    }
    val fill = resolutionFill.coerceIn(0f, 1f)
    val pulseMove = LocalSegmentedDisplayPulseMove.current
    val slotProgress = if (slotIndex >= 0) {
        resolutionBurstSlotFillProgress(fill, slotIndex, pulseMove)
    } else {
        1f
    }
    val strength = (resolutionAlpha * slotProgress).coerceIn(0f, 1f)
    if (strength <= ResolutionPulseInactiveThreshold) {
        return AppliedSlotBurst(emptySet(), 0f)
    }
    return AppliedSlotBurst(
        segments = resolutionBurstSegmentsExcluding(
            pulseMove,
            fill,
            protectedSegments,
            slotIndex,
        ),
        strength = strength,
    )
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
    val protectedSegments = if (dimAllSegments) {
        fullLitSegments
    } else {
        fullLitSegments + combinedHalfLit
    }
    val resolutionBurst = resolveResolutionSlotBurst(slotIndex, protectedSegments)

    SevenSegmentDisplayWithPulse(
        fullLitSegments = fullLitSegments,
        halfLitSegments = combinedHalfLit,
        dimAllSegments = dimAllSegments,
        burstSegments = resolutionBurst.segments,
        burstAlpha = resolutionBurst.strength,
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
    val resolutionAlpha = LocalResolutionPulseAlpha.current
    val burstStrength = if (
        slotIndex >= 0 &&
        resolutionAlpha > ResolutionPulseInactiveThreshold
    ) {
        val resolutionFill = LocalResolutionPulseFill.current
        if (resolutionFill > ResolutionPulseInactiveThreshold) {
            val pulseMove = LocalSegmentedDisplayPulseMove.current
            resolutionBurstSlotFillProgress(
                resolutionFill.coerceIn(0f, 1f),
                slotIndex,
                pulseMove,
            ) * resolutionAlpha
        } else {
            0f
        }
    } else {
        0f
    }
    val halfLitColor = sevenSegmentHalfLitColor()
    val pipColor = when {
        burstStrength > 0.001f -> lerp(offColor, halfLitColor, burstStrength.coerceIn(0f, 1f))
        lit -> litColor
        else -> offColor
    }
    WithOptionalPulseSlotIndex(slotIndex) {
        SevenSegmentTimeColon(
            color = pipColor,
            lit = lit || burstStrength > 0.001f,
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
    burstSegments: Set<Char>,
    burstAlpha: Float,
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
                segment.id in burstSegments && burstAlpha > 0.001f -> {
                    drawSevenSegment(
                        segment,
                        lerp(offColor, halfLitColor, burstAlpha),
                    )
                }
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
    val slotIndex = LocalSegmentedDisplayPulseSlotIndex.current
    val networkHalfLit = bridgeSlotNetworkHalfLitSegments(
        slotIndex = slotIndex,
        activeKinds = LocalNetworkActivityKinds.current,
        connectionProbeActive = LocalConnectionProbeActive.current,
    )
    val spinnerHalfLit = if (animate) steps[step] else emptySet()
    SegmentedDisplayPulseSlot(
        offColor = offColor,
        halfLitSegments = spinnerHalfLit + networkHalfLit,
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
