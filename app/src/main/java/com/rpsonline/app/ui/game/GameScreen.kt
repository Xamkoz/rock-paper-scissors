package com.rpsonline.app.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus
import com.rpsonline.app.data.model.Move
import com.rpsonline.app.data.model.RoundResult
import com.rpsonline.app.domain.GameRules
import com.rpsonline.app.ui.components.MovePicker
import com.rpsonline.app.ui.components.rpsScreenPadding
import com.rpsonline.app.viewmodel.GameViewModel

@Composable
fun GameScreen(
    matchId: String,
    onMatchComplete: (String) -> Unit,
    onMatchAbandoned: () -> Unit,
    viewModel: GameViewModel = viewModel(factory = GameViewModel.factory(matchId)),
) {
    val uiState by viewModel.uiState.collectAsState()
    val match = uiState.match
    val userId = uiState.userId

    LaunchedEffect(match?.status, match?.id) {
        when (match?.status) {
            MatchStatus.COMPLETED -> onMatchComplete(matchId)
            MatchStatus.ABANDONED -> onMatchAbandoned()
            else -> Unit
        }
    }

    if (match?.status == MatchStatus.COMPLETED || match?.status == MatchStatus.ABANDONED) {
        return
    }

    Column(
        modifier = Modifier.rpsScreenPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (match == null || userId == null) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
            return
        }

        val currentRound = match.currentRoundData()
        val drawReplay = match.pendingDrawReplay()
        val pendingOutcome = match.pendingRoundOutcome()
        val openRound = match.openRound()
        val myLockedChoice = myLockedChoice(userId, match, openRound, uiState.lockedMove)
        val showDrawReveal = currentRound?.winner == "tie" &&
            currentRound.player1Choice != null &&
            currentRound.player2Choice != null
        val showOutcomeReveal = currentRound?.winner != null &&
            currentRound.winner != "tie" &&
            currentRound.player1Choice != null &&
            currentRound.player2Choice != null
        val awaitingNextRound = pendingOutcome != null && openRound != null
        val showMovePicker = !uiState.hasSubmittedMove && !uiState.isSubmitting && when {
            drawReplay != null -> true
            awaitingNextRound -> true
            showDrawReveal || showOutcomeReveal -> false
            else -> true
        }
        val pickPrompt = when {
            uiState.hasSubmittedMove -> null
            drawReplay != null ->
                "Pick your move to replay the round (${GameRules.ROUND_TIMEOUT_SECONDS}s limit)"
            awaitingNextRound ->
                "Pick your move for round ${openRound!!.roundNumber} (${GameRules.ROUND_TIMEOUT_SECONDS}s limit)"
            showMovePicker ->
                "Pick your move — ${GameRules.ROUND_TIMEOUT_SECONDS}s per round"
            else -> null
        }

        val compactLayout = LocalConfiguration.current.screenHeightDp < 700
        val opponentScoreLabel =
            if (LocalConfiguration.current.screenWidthDp < 360) "Opp." else "Opponent"

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "vs ${match.opponentName(userId)}",
                style = if (compactLayout) {
                    MaterialTheme.typography.titleLarge
                } else {
                    MaterialTheme.typography.headlineSmall
                },
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(if (compactLayout) 4.dp else 8.dp))
            Text(
                text = "Round ${match.currentRound}  •  Best of 3",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(if (compactLayout) 8.dp else 12.dp))

            val showCountdown = match.status == MatchStatus.ACTIVE &&
                match.openRound()?.deadline != null &&
                uiState.countdownSeconds != null
            if (showCountdown) {
                RoundCountdown(
                    secondsRemaining = uiState.countdownSeconds,
                    isResolvingTimeout = uiState.isResolvingTimeout,
                    hasSubmittedMove = uiState.hasSubmittedMove,
                    compact = compactLayout,
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            MatchScoreCard(
                myWins = match.myWins(userId),
                opponentWins = match.opponentWins(userId),
                opponentLabel = opponentScoreLabel,
                compact = compactLayout,
            )

            when {
                showDrawReveal || drawReplay != null -> {
                    val round = if (showDrawReveal) currentRound!! else drawReplay!!
                    val (myChoice, oppChoice) = round.choicesFor(userId, match)
                    Spacer(modifier = Modifier.height(if (compactLayout) 8.dp else 12.dp))
                    DrawRoundBanner(
                        myChoice = myChoice,
                        opponentChoice = oppChoice,
                        isReplay = drawReplay != null,
                        compact = compactLayout,
                        opponentLabel = opponentScoreLabel,
                    )
                }

                showOutcomeReveal || pendingOutcome != null -> {
                    val round = if (showOutcomeReveal) currentRound!! else pendingOutcome!!
                    val (myChoice, oppChoice) = round.choicesFor(userId, match)
                    val wonRound = round.winner == userId
                    Spacer(modifier = Modifier.height(if (compactLayout) 8.dp else 12.dp))
                    if (wonRound) {
                        WinRoundBanner(
                            myChoice = myChoice,
                            opponentChoice = oppChoice,
                            awaitingNextRound = awaitingNextRound,
                            compact = compactLayout,
                            opponentLabel = opponentScoreLabel,
                        )
                    } else {
                        LoseRoundBanner(
                            myChoice = myChoice,
                            opponentChoice = oppChoice,
                            awaitingNextRound = awaitingNextRound,
                            compact = compactLayout,
                            opponentLabel = opponentScoreLabel,
                        )
                    }
                }
            }

            uiState.error?.let { error ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = error, color = MaterialTheme.colorScheme.error)
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                uiState.hasSubmittedMove && myLockedChoice != null -> {
                    WaitingForOpponentCard(myChoice = myLockedChoice)
                }

                uiState.isSubmitting -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    CircularProgressIndicator()
                }

                showMovePicker -> {
                    pickPrompt?.let { prompt ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = prompt,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    MovePicker(
                        isSubmitting = uiState.isSubmitting,
                        onMove = viewModel::submitMove,
                        compact = compactLayout,
                    )
                }
            }
        }
    }
}

@Composable
private fun MatchScoreCard(
    myWins: Int,
    opponentWins: Int,
    opponentLabel: String,
    compact: Boolean,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScoreColumn(
            label = "You",
            score = myWins,
            progressColor = MaterialTheme.colorScheme.primary,
            progressSide = MatchScoreSide.YOU,
            compact = compact,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = ":",
            style = compactScoreTextStyle(compact, MaterialTheme.typography.titleMedium),
            color = MaterialTheme.colorScheme.onSurface,
        )
        ScoreColumn(
            label = opponentLabel,
            score = opponentWins,
            progressColor = MaterialTheme.colorScheme.tertiary,
            progressSide = MatchScoreSide.OPPONENT,
            compact = compact,
            modifier = Modifier.weight(1f),
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

private fun myLockedChoice(
    userId: String,
    match: Match,
    openRound: RoundResult?,
    lockedMove: Move?,
): String? {
    val fromServer = openRound?.let { round ->
        if (userId == match.player1) round.player1Choice else round.player2Choice
    }
    return fromServer ?: lockedMove?.name
}

private fun RoundResult.choicesFor(userId: String, match: Match): Pair<String?, String?> {
    val myChoice = if (userId == match.player1) player1Choice else player2Choice
    val oppChoice = if (userId == match.player1) player2Choice else player1Choice
    return myChoice to oppChoice
}

private enum class MatchScoreSide { YOU, OPPONENT }

@Composable
private fun ScoreColumn(
    label: String,
    score: Int,
    progressColor: Color,
    progressSide: MatchScoreSide,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Text(
            label,
            style = compactScoreTextStyle(compact, MaterialTheme.typography.labelMedium),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "$score",
            style = compactScoreTextStyle(
                compact,
                if (compact) {
                    MaterialTheme.typography.headlineMedium
                } else {
                    MaterialTheme.typography.headlineLarge
                },
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(2.dp))
        MatchWinProgressBar(
            wins = score,
            fillColor = progressColor,
            side = progressSide,
        )
    }
}

@Composable
private fun MatchWinProgressBar(
    wins: Int,
    fillColor: Color,
    side: MatchScoreSide,
    modifier: Modifier = Modifier,
) {
    val slots = GameRules.WINS_TO_FINISH
    val segmentShape = RoundedCornerShape(3.dp)
    val emptyFill = MaterialTheme.colorScheme.surface
    val emptyBorder = MaterialTheme.colorScheme.outline
    val barHeight = 8.dp
    val segmentGap = 3.dp
    val rowAlignment = when (side) {
        MatchScoreSide.YOU -> Alignment.End
        MatchScoreSide.OPPONENT -> Alignment.Start
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight),
        horizontalArrangement = Arrangement.spacedBy(segmentGap, rowAlignment),
    ) {
        repeat(slots) { index ->
            val filled = index < wins
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

