package com.rpsonline.app.ui.components

/**
 * When a segmented display consumer mounts (e.g. top bar after sign-in), start from the current
 * trigger so round-resolution pulses that already finished are not replayed across all digits.
 */
internal fun resolutionPulseConsumptionStart(currentTrigger: Int): Int = currentTrigger.coerceAtLeast(0)

internal fun shouldConsumeResolutionPulse(lastConsumed: Int, currentTrigger: Int): Boolean =
    currentTrigger > lastConsumed
