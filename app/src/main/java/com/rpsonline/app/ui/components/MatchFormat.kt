package com.rpsonline.app.ui.components

import com.rpsonline.app.domain.MatchMode
import java.util.Locale
import kotlin.math.roundToInt

/** Shared match score format (e.g. `2–1`). */
fun formatMatchScore(myWins: Int, opponentWins: Int): String = "$myWins–$opponentWins"

fun formatEloDelta(delta: Int): String =
    if (delta >= 0) "+$delta" else "$delta"

/** ELO delta with one decimal place (e.g. `+2.7`, `-1.0`). */
fun formatEloDeltaOneDecimal(delta: Double): String {
    val rounded = (delta * 10.0).roundToInt() / 10.0
    val body = String.format(Locale.US, "%.1f", kotlin.math.abs(rounded))
    return if (rounded >= 0) "+$body" else "-$body"
}

fun formatMatchResultLine(
    outcomeLabel: String,
    myWins: Int,
    opponentWins: Int,
    eloDelta: Int?,
): String = buildString {
    append(outcomeLabel)
    append(' ')
    append(formatMatchScore(myWins, opponentWins))
    if (eloDelta != null) {
        append(" (${formatEloDelta(eloDelta)})")
    }
}

fun postMatchElo(preMatchElo: Int?, eloDelta: Int?): Int? {
    if (preMatchElo == null || eloDelta == null) return null
    return preMatchElo + eloDelta
}

fun formatMatchMode(mode: MatchMode): String = mode.label

/** e.g. `BO10` for the in-match sub-header. */
fun formatMatchModeCode(mode: MatchMode): String = mode.name

/** e.g. `Bo5 · to 3` or `Best of 5 (first to 3)`. */
fun formatMatchSeriesDetail(mode: MatchMode, compact: Boolean = false): String {
    if (compact) {
        val base = "Bo${mode.bestOfRounds} · to ${mode.winsToFinish}"
        val tied = mode.tiedSeriesScore
        return if (tied != null) {
            "$base · ${tied}–${tied}"
        } else {
            base
        }
    }
    val base = "${mode.label} (first to ${mode.winsToFinish})"
    val tied = mode.tiedSeriesScore
    return if (tied != null) {
        "$base, $tied–$tied draw"
    } else {
        base
    }
}

fun formatMatchModes(modes: Set<MatchMode>): String =
    modes.sortedBy { it.ordinal }.joinToString(" / ") { it.label }
