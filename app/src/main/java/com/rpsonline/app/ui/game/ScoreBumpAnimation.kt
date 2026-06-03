package com.rpsonline.app.ui.game

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

/** Snappy digit flash + color pop — finishes well inside the 1s gate. */
private const val SCORE_DIGIT_OUT_MS = 65
private const val SCORE_DIGIT_IN_MS = 130
private const val SCORE_COLOR_FLASH_MS = 200
private const val SCORE_COLOR_HOLD_MS = 90L

data class AnimatedMatchScores(
    val myWins: Int,
    val opponentWins: Int,
    val myScoringActive: Boolean,
    val opponentScoringActive: Boolean,
    /** True for the full [DUAL_SELECTION_MIN_DISPLAY_MS] recap score gate. */
    val gateOpen: Boolean,
)

@Composable
fun rememberScoreGateOpen(
    roundKey: Int?,
    gateRequested: Boolean,
): Boolean {
    var gateOpen by remember(roundKey) { mutableStateOf(false) }
    LaunchedEffect(gateRequested, roundKey) {
        if (!gateRequested) {
            gateOpen = false
            return@LaunchedEffect
        }
        gateOpen = true
        delay(DUAL_SELECTION_MIN_DISPLAY_MS)
        gateOpen = false
    }
    return gateRequested && gateOpen
}

/** Snaps the displayed score on gate open so [AnimatedPlayerScoreText] can flash once per side. */
@Composable
fun rememberAnimatedMatchScores(
    gateOpen: Boolean,
    heldMy: Int,
    targetMy: Int,
    heldOpponent: Int,
    targetOpponent: Int,
    roundKey: Int?,
): AnimatedMatchScores {
    val myScoringActive = gateOpen && targetMy > heldMy
    val opponentScoringActive = gateOpen && targetOpponent > heldOpponent

    var myWins by remember(roundKey) { mutableIntStateOf(targetMy) }
    var opponentWins by remember(roundKey) { mutableIntStateOf(targetOpponent) }

    LaunchedEffect(gateOpen, roundKey, heldMy, targetMy, heldOpponent, targetOpponent) {
        if (!gateOpen) {
            myWins = targetMy
            opponentWins = targetOpponent
            return@LaunchedEffect
        }
        myWins = heldMy
        opponentWins = heldOpponent
        yield()
        if (targetMy > heldMy) {
            myWins = targetMy
        }
        if (targetOpponent > heldOpponent) {
            opponentWins = targetOpponent
        }
    }

    return AnimatedMatchScores(
        myWins = myWins,
        opponentWins = opponentWins,
        myScoringActive = myScoringActive,
        opponentScoringActive = opponentScoringActive,
        gateOpen = gateOpen,
    )
}

@Composable
fun AnimatedPlayerScoreText(
    score: Int,
    accentColor: Color,
    scoringActive: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    var colorTarget by remember(accentColor) { mutableStateOf(accentColor) }
    LaunchedEffect(scoringActive, accentColor) {
        if (scoringActive) {
            colorTarget = lerp(accentColor, Color.White, 0.72f).copy(alpha = 1f)
            delay(SCORE_COLOR_HOLD_MS)
            colorTarget = accentColor
        } else {
            colorTarget = accentColor
        }
    }
    val displayColor by animateColorAsState(
        targetValue = colorTarget,
        animationSpec = if (scoringActive) {
            tween(durationMillis = SCORE_COLOR_FLASH_MS, easing = LinearEasing)
        } else {
            snap()
        },
        label = "playerScoreColor",
    )
    AnimatedContent(
        targetState = score,
        transitionSpec = {
            (
                scaleIn(
                    initialScale = 0.72f,
                    animationSpec = tween(SCORE_DIGIT_IN_MS, easing = LinearOutSlowInEasing),
                ) + fadeIn(
                    animationSpec = tween(SCORE_DIGIT_IN_MS, easing = LinearEasing),
                )
                ) togetherWith (
                scaleOut(
                    targetScale = 1.12f,
                    animationSpec = tween(SCORE_DIGIT_OUT_MS, easing = LinearEasing),
                ) + fadeOut(
                    animationSpec = tween(SCORE_DIGIT_OUT_MS, easing = LinearEasing),
                )
                )
        },
        label = "playerScoreDigit",
        modifier = modifier,
    ) { animatedScore ->
        Text(
            text = "$animatedScore",
            style = style,
            color = displayColor,
            textAlign = TextAlign.Center,
        )
    }
}
