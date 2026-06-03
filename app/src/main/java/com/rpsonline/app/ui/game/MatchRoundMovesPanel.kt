package com.rpsonline.app.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.rpsonline.app.R
import com.rpsonline.app.data.model.Move
import com.rpsonline.app.data.preferences.MoveDisplayPreferences
import com.rpsonline.app.ui.components.MoveIconCard
import com.rpsonline.app.ui.components.RpsCard
import com.rpsonline.app.ui.components.moveBarFillColor
import com.rpsonline.app.ui.components.moveSlotContentSize
import com.rpsonline.app.ui.components.moveSlotLockedSurface
import com.rpsonline.app.ui.components.moveSlotSquareSide

data class MatchRoundOutcome(
    val kind: RoundBannerKind,
    val roundNumber: Int,
    val subtitle: String,
)

enum class PanelMoveDisplay {
    /** No move yet this round — show waiting clock. */
    Waiting,
    /** Move submitted but hidden — show secret marker or own icon when known. */
    Secret,
    /** Round resolved or previous-round recap — show move icon. */
    Revealed,
}

data class PanelMovePresentation(
    val move: Move? = null,
    val display: PanelMoveDisplay = PanelMoveDisplay.Waiting,
)

@Composable
fun MatchRoundMovesPanel(
    opponentLabel: String,
    opponentMove: PanelMovePresentation,
    myMove: PanelMovePresentation,
    myWins: Int,
    myScoreScoringActive: Boolean = false,
    myWinMoves: List<Move>,
    opponentWins: Int,
    opponentScoreScoringActive: Boolean = false,
    opponentWinMoves: List<Move>,
    winsToFinish: Int,
    outcome: MatchRoundOutcome?,
    roundNumber: Int,
    compact: Boolean,
    tight: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val cardPadding = when {
        tight -> 8.dp to 4.dp
        compact -> 10.dp to 6.dp
        else -> 12.dp to 8.dp
    }
    val contentSpacing = when {
        tight -> 4.dp
        compact -> 5.dp
        else -> 6.dp
    }
    val context = LocalContext.current
    val moveDisplayPreferences = remember {
        MoveDisplayPreferences(context.applicationContext)
    }
    var ownMoveRevealed by rememberSaveable {
        mutableStateOf(moveDisplayPreferences.isOwnMoveRevealed())
    }
    var cachedSecretMove by remember(roundNumber) { mutableStateOf<Move?>(null) }
    LaunchedEffect(myMove.move, myMove.display, roundNumber) {
        when {
            myMove.display != PanelMoveDisplay.Secret -> cachedSecretMove = null
            myMove.move != null -> cachedSecretMove = myMove.move
            else -> cachedSecretMove = null
        }
    }
    val panelMyMove = if (
        myMove.display == PanelMoveDisplay.Secret &&
        myMove.move == null &&
        cachedSecretMove != null
    ) {
        myMove.copy(move = cachedSecretMove)
    } else {
        myMove
    }
    val allowOwnMoveTapToReveal =
        panelMyMove.display == PanelMoveDisplay.Secret && panelMyMove.move != null

    val colorScheme = MaterialTheme.colorScheme
    val outcomeChrome = outcome?.let { roundOutcomeBannerColors(it.kind) }
    val cardContainerColor = outcomeChrome?.containerColor
        ?: colorScheme.surfaceContainerHigh.copy(alpha = 0.94f)
    val cardBorderColor = outcomeChrome?.contentColor?.copy(alpha = 0.38f)
        ?: colorScheme.outline.copy(alpha = 0.45f)
    val onCardContent = outcomeChrome?.contentColor ?: colorScheme.onSurface
    val onCardLabel = outcomeChrome?.contentColor?.copy(alpha = 0.82f)
        ?: colorScheme.onSurfaceVariant
    val playerScoreColors = matchPanelPlayerScoreColors(cardBackground = cardContainerColor)

    RpsCard(
        modifier = modifier.fillMaxWidth(),
        containerColor = cardContainerColor,
        borderColor = cardBorderColor,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = cardPadding.first, vertical = cardPadding.second),
            verticalArrangement = Arrangement.spacedBy(contentSpacing),
        ) {
            RoundOutcomeHeader(
                outcome = outcome,
                contentColor = onCardContent,
                compact = compact,
                tight = tight,
            )

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val slotGap = 6.dp
                val slotWidth = (maxWidth - slotGap) / 2
                val slotCompact = compact || tight
                val moveSquareSide = moveSlotSquareSide(slotWidth, slotCompact)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(slotGap),
                    verticalAlignment = Alignment.Top,
                ) {
                    PlayerScoreMoveColumn(
                        playerLabel = stringResource(R.string.you),
                        score = myWins,
                        scoreScoringActive = myScoreScoringActive,
                        winsToFinish = winsToFinish,
                        winMoves = myWinMoves,
                        presentation = panelMyMove,
                        moveRevealed = ownMoveRevealed,
                        allowTapToReveal = allowOwnMoveTapToReveal,
                        onTapToReveal = {
                            val next = !ownMoveRevealed
                            ownMoveRevealed = next
                            moveDisplayPreferences.setOwnMoveRevealed(next)
                        },
                        moveSquareSide = moveSquareSide,
                        compact = compact,
                        tight = tight,
                        emphasized = !compact && !tight,
                        scoreColor = playerScoreColors.you,
                        labelColor = playerScoreColors.you.copy(alpha = 0.82f),
                        progressEmptyFill = progressBarEmptyFill(outcomeChrome, colorScheme),
                        progressEmptyBorder = progressBarEmptyBorder(outcomeChrome, colorScheme),
                        modifier = Modifier.weight(1f),
                    )
                    ScoreColon(
                        compact = compact,
                        tight = tight,
                        emphasized = !compact && !tight,
                        moveSquareSide = moveSquareSide,
                        color = onCardContent,
                    )
                    PlayerScoreMoveColumn(
                        playerLabel = opponentLabel,
                        score = opponentWins,
                        scoreScoringActive = opponentScoreScoringActive,
                        winsToFinish = winsToFinish,
                        winMoves = opponentWinMoves,
                        presentation = opponentMove,
                        moveSquareSide = moveSquareSide,
                        compact = compact,
                        tight = tight,
                        emphasized = !compact && !tight,
                        scoreColor = playerScoreColors.opponent,
                        labelColor = playerScoreColors.opponent.copy(alpha = 0.82f),
                        progressEmptyFill = progressBarEmptyFill(outcomeChrome, colorScheme),
                        progressEmptyBorder = progressBarEmptyBorder(outcomeChrome, colorScheme),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private fun progressBarEmptyFill(
    outcomeChrome: RoundOutcomeBannerColors?,
    colorScheme: ColorScheme,
): Color =
    outcomeChrome?.contentColor?.copy(alpha = 0.14f) ?: colorScheme.surface

private fun progressBarEmptyBorder(
    outcomeChrome: RoundOutcomeBannerColors?,
    colorScheme: ColorScheme,
): Color =
    outcomeChrome?.contentColor?.copy(alpha = 0.32f) ?: colorScheme.outline

@Composable
private fun RoundOutcomeHeader(
    outcome: MatchRoundOutcome?,
    contentColor: Color,
    compact: Boolean,
    tight: Boolean,
) {
    val headerMinHeight = when {
        tight -> 22.dp
        compact -> 26.dp
        else -> 30.dp
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = headerMinHeight),
        contentAlignment = Alignment.Center,
    ) {
        if (outcome != null) {
            Text(
                text = panelRoundOutcomeHeadline(outcome.kind, outcome.roundNumber),
                style = when {
                    tight -> MaterialTheme.typography.labelLarge
                    compact -> MaterialTheme.typography.titleSmall
                    else -> MaterialTheme.typography.titleMedium
                }.copy(
                    fontWeight = FontWeight.Bold,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                color = contentColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PlayerScoreMoveColumn(
    playerLabel: String,
    score: Int,
    scoreScoringActive: Boolean,
    winsToFinish: Int,
    winMoves: List<Move>,
    presentation: PanelMovePresentation,
    moveSquareSide: Dp,
    compact: Boolean,
    tight: Boolean,
    scoreColor: Color,
    labelColor: Color,
    progressEmptyFill: Color,
    progressEmptyBorder: Color,
    emphasized: Boolean = false,
    moveRevealed: Boolean = false,
    allowTapToReveal: Boolean = false,
    onTapToReveal: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (tight) 3.dp else 4.dp),
    ) {
        PlayerMoveSlot(
            presentation = presentation,
            moveSquareSide = moveSquareSide,
            moveRevealed = moveRevealed,
            allowTapToReveal = allowTapToReveal,
            onTapToReveal = onTapToReveal,
            compact = compact,
            tight = tight,
        )
        PlayerScoreLine(
            playerLabel = playerLabel,
            score = score,
            scoreScoringActive = scoreScoringActive,
            compact = compact,
            tight = tight,
            emphasized = emphasized,
            scoreColor = scoreColor,
            labelColor = labelColor,
        )
        MatchWinProgressBar(
            wins = score,
            winsToFinish = winsToFinish,
            winMoves = winMoves,
            emptyFill = progressEmptyFill,
            emptyBorder = progressEmptyBorder,
        )
    }
}

private fun compactScoreTextStyle(
    compact: Boolean,
    base: androidx.compose.ui.text.TextStyle,
) = base.copy(
    lineHeight = if (compact) 1.1.em else 1.15.em,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

@Composable
private fun scoreNumberStyle(
    compact: Boolean,
    tight: Boolean,
    emphasized: Boolean,
): androidx.compose.ui.text.TextStyle {
    val typography = MaterialTheme.typography
    return when {
        emphasized -> typography.displaySmall
        compact -> typography.headlineMedium
        tight -> typography.headlineSmall
        else -> typography.headlineLarge
    }
}

@Composable
private fun PlayerScoreLine(
    playerLabel: String,
    score: Int,
    scoreScoringActive: Boolean,
    compact: Boolean,
    tight: Boolean,
    emphasized: Boolean,
    scoreColor: Color,
    labelColor: Color,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (tight) 0.dp else 2.dp),
    ) {
        AnimatedPlayerScoreText(
            score = score,
            accentColor = scoreColor,
            scoringActive = scoreScoringActive,
            style = compactScoreTextStyle(
                compact,
                scoreNumberStyle(compact, tight, emphasized),
            ),
        )
        Text(
            text = playerLabel,
            style = when {
                tight -> MaterialTheme.typography.labelSmall
                else -> MaterialTheme.typography.labelMedium
            },
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ScoreColon(
    compact: Boolean,
    tight: Boolean,
    emphasized: Boolean,
    moveSquareSide: Dp,
    color: Color,
) {
    Text(
        text = ":",
        style = compactScoreTextStyle(
            compact,
            scoreColonStyle(compact, tight, emphasized),
        ),
        color = color,
        modifier = Modifier.padding(top = scoreColonTopPadding(moveSquareSide, compact, tight)),
    )
}

@Composable
private fun scoreColonStyle(
    compact: Boolean,
    tight: Boolean,
    emphasized: Boolean,
): androidx.compose.ui.text.TextStyle {
    val typography = MaterialTheme.typography
    return when {
        emphasized && !tight -> typography.headlineSmall
        compact -> typography.titleMedium
        else -> typography.titleMedium
    }
}

private fun scoreColonTopPadding(moveSquareSide: Dp, compact: Boolean, tight: Boolean): Dp {
    val moveToScoreGap = when {
        tight -> 4.dp
        compact -> 5.dp
        else -> 6.dp
    }
    val scoreBlock = when {
        tight -> 28.dp
        compact -> 34.dp
        else -> 40.dp
    }
    return moveSquareSide + moveToScoreGap + (scoreBlock / 2)
}

@Composable
private fun MatchWinProgressBar(
    wins: Int,
    winsToFinish: Int,
    winMoves: List<Move>,
    emptyFill: Color,
    emptyBorder: Color,
    modifier: Modifier = Modifier,
) {
    val segmentShape = RoundedCornerShape(3.dp)
    val fallbackFill = MaterialTheme.colorScheme.onSurfaceVariant
    val barHeight = 8.dp
    val segmentGap = 3.dp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight),
        horizontalArrangement = Arrangement.spacedBy(segmentGap),
    ) {
        repeat(winsToFinish) { index ->
            val filled = index < wins
            val fillColor = winMoves.getOrNull(index)?.let { moveBarFillColor(it) } ?: fallbackFill
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(barHeight)
                    .clip(segmentShape)
                    .then(
                        if (filled) {
                            Modifier.background(fillColor)
                        } else {
                            Modifier
                                .background(emptyFill)
                                .border(1.dp, emptyBorder, segmentShape)
                        },
                    ),
            )
        }
    }
}

@Composable
private fun PlayerMoveSlot(
    presentation: PanelMovePresentation,
    moveSquareSide: Dp,
    compact: Boolean,
    tight: Boolean,
    moveRevealed: Boolean = false,
    allowTapToReveal: Boolean = false,
    onTapToReveal: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val move = presentation.move
    val hiddenDescription = stringResource(R.string.your_move_hidden)
    val showOwnMoveIcon = moveRevealed && move != null
    val slotCompact = compact || tight

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (tight) 4.dp else 5.dp),
    ) {
        when (presentation.display) {
            PanelMoveDisplay.Revealed -> {
                if (move != null) {
                    PanelMoveIconCard(
                        move = move,
                        squareSide = moveSquareSide,
                        slotCompact = slotCompact,
                        modifier = Modifier.semantics { contentDescription = move.label },
                    )
                } else {
                    WaitingMovePlaceholder(
                        squareSide = moveSquareSide,
                        compact = slotCompact,
                    )
                }
            }
            PanelMoveDisplay.Secret -> {
                if (showOwnMoveIcon) {
                    PanelMoveIconCard(
                        move = move!!,
                        squareSide = moveSquareSide,
                        slotCompact = slotCompact,
                        modifier = Modifier
                            .semantics { contentDescription = move.label }
                            .then(
                                if (allowTapToReveal && onTapToReveal != null) {
                                    Modifier.clickable(onClick = onTapToReveal)
                                } else {
                                    Modifier
                                },
                            ),
                    )
                } else {
                    SecretMovePlaceholder(
                        squareSide = moveSquareSide,
                        compact = slotCompact,
                        tight = tight,
                        onClick = onTapToReveal.takeIf { allowTapToReveal && move != null },
                        contentDescription = if (allowTapToReveal) hiddenDescription else "?",
                    )
                }
            }
            PanelMoveDisplay.Waiting -> {
                WaitingMovePlaceholder(
                    squareSide = moveSquareSide,
                    compact = slotCompact,
                )
            }
        }
    }
}

@Composable
private fun PanelMoveIconCard(
    move: Move,
    squareSide: Dp,
    slotCompact: Boolean,
    modifier: Modifier = Modifier,
) {
    MoveIconCard(
        move = move,
        compact = slotCompact,
        large = true,
        enabled = false,
        squareSide = squareSide,
        modifier = modifier,
    )
}

@Composable
private fun WaitingMovePlaceholder(
    squareSide: Dp,
    compact: Boolean,
) {
    Box(
        modifier = Modifier
            .moveSlotLockedSurface(squareSide, compact)
            .semantics { contentDescription = "Waiting" },
        contentAlignment = Alignment.Center,
    ) {
        val iconSize = moveSlotContentSize(squareSide, compact = compact, large = true)
        Icon(
            imageVector = Icons.Default.AccessTime,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun SecretMovePlaceholder(
    squareSide: Dp,
    compact: Boolean,
    tight: Boolean,
    onClick: (() -> Unit)? = null,
    contentDescription: String? = null,
) {
    Box(
        modifier = Modifier
            .moveSlotLockedSurface(squareSide, compact)
            .then(
                if (onClick != null) {
                    Modifier
                        .clickable(onClick = onClick)
                        .semantics {
                            this.contentDescription = contentDescription ?: "?"
                        }
                } else {
                    Modifier.semantics {
                        this.contentDescription = contentDescription ?: "?"
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val contentSize = moveSlotContentSize(squareSide, compact = compact, large = true)
        val fontSize = (contentSize.value * 0.72f).coerceAtLeast(if (tight) 20f else 24f).sp
        Text(
            text = "?",
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
