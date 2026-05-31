package com.rpsonline.app.ui.game

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus
import com.rpsonline.app.data.model.Move
import com.rpsonline.app.data.model.RoundResult
import com.rpsonline.app.R
import com.rpsonline.app.ui.components.formatMatchModeCode
import com.rpsonline.app.ui.components.MovePicker
import com.rpsonline.app.ui.components.RpsLoadingColumn
import com.rpsonline.app.ui.components.rpsScreenPadding
import com.rpsonline.app.data.repository.MatchSessionMonitor
import com.rpsonline.app.data.repository.PresenceRepository
import com.rpsonline.app.domain.GameRules
import com.rpsonline.app.ui.LocalClockSoundMuted
import com.rpsonline.app.ui.util.LocalRoundResolutionPulse
import com.rpsonline.app.ui.util.MATCH_END_NAVIGATION_DELAY_MS
import com.rpsonline.app.ui.util.awaitMatchEndResolutionFeedback
import com.rpsonline.app.viewmodel.GameUiState
import com.rpsonline.app.viewmodel.GameViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun GameScreen(
    matchId: String,
    onMatchComplete: (String) -> Unit,
    viewModel: GameViewModel = viewModel(factory = GameViewModel.factory(matchId)),
) {
    val uiState by viewModel.uiState.collectAsState()
    val match = uiState.match
    val monitorMatch by MatchSessionMonitor.activeMatch.collectAsStateWithLifecycle()
    val userId = uiState.userId
    val pulseNotifier = LocalRoundResolutionPulse.current
    val clockSoundMuted = LocalClockSoundMuted.current
    val terminalMatch = when {
        match?.status == MatchStatus.COMPLETED || match?.status == MatchStatus.ABANDONED -> match
        monitorMatch?.id == matchId &&
            (monitorMatch?.status == MatchStatus.COMPLETED || monitorMatch?.status == MatchStatus.ABANDONED) ->
            monitorMatch
        else -> null
    }

    val presenceRepository = remember { PresenceRepository() }
    val scope = rememberCoroutineScope()
    var navigatedToResult by remember(matchId) { mutableStateOf(false) }
    var frozenEndTransition by remember(matchId) { mutableStateOf<MatchEndTransitionUi?>(null) }
    val endTransition = frozenEndTransition ?: run {
        val terminal = terminalMatch ?: return@run null
        val uid = userId ?: return@run null
        val liveMatch = match?.takeIf { it.status == MatchStatus.ACTIVE }
        buildMatchEndTransitionUi(
            displayMatch = liveMatch ?: terminal,
            terminal = terminal,
            userId = uid,
            uiState = uiState,
        ).also { frozenEndTransition = it }
    }

    DisposableEffect(matchId) {
        pulseNotifier?.enterLiveMatch(matchId)
        onDispose {
            pulseNotifier?.leaveLiveMatch(matchId)
        }
    }

    LifecycleResumeEffect(matchId, userId) {
        userId?.let { uid ->
            scope.launch {
                runCatching {
                    presenceRepository.touchPresence(uid, forceAuthRefresh = true, awaitServerAck = true)
                }
            }
        }
        viewModel.refreshOnResume()
        onPauseOrDispose { }
    }

    LaunchedEffect(userId) {
        val uid = userId ?: return@LaunchedEffect
        presenceRepository.touchPresence(uid, forceAuthRefresh = true, awaitServerAck = true)
    }

    LaunchedEffect(monitorMatch?.status, monitorMatch?.id, match?.status, matchId) {
        val monitor = monitorMatch ?: return@LaunchedEffect
        if (monitor.id != matchId) return@LaunchedEffect
        if (monitor.status != MatchStatus.COMPLETED && monitor.status != MatchStatus.ABANDONED) {
            return@LaunchedEffect
        }
        if (match?.status == monitor.status) return@LaunchedEffect
        viewModel.refreshOnResume()
    }

    LaunchedEffect(endTransition?.roundKey, matchId) {
        if (navigatedToResult) return@LaunchedEffect
        if (endTransition == null) return@LaunchedEffect
        val current = terminalMatch
            ?: match?.takeIf { it.status == MatchStatus.COMPLETED || it.status == MatchStatus.ABANDONED }
            ?: monitorMatch?.takeIf {
                it.id == matchId &&
                    (it.status == MatchStatus.COMPLETED || it.status == MatchStatus.ABANDONED)
            }
            ?: return@LaunchedEffect
        when (current.status) {
            MatchStatus.COMPLETED -> {
                awaitMatchEndResolutionFeedback(
                    pulseNotifier = pulseNotifier,
                    match = current,
                    userId = userId,
                    muted = clockSoundMuted,
                )
                delay(MATCH_END_NAVIGATION_DELAY_MS)
                onMatchComplete(matchId)
                navigatedToResult = true
            }
            MatchStatus.ABANDONED -> {
                delay(MATCH_END_NAVIGATION_DELAY_MS)
                onMatchComplete(matchId)
                navigatedToResult = true
            }
            else -> Unit
        }
    }

    Column(
        modifier = Modifier
            .rpsScreenPadding()
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (match == null || userId == null) {
            RpsLoadingColumn(modifier = Modifier.weight(1f))
        } else if (match.status == MatchStatus.LOBBY) {
            RpsLoadingColumn(
                modifier = Modifier.weight(1f),
                message = stringResource(R.string.waiting_for_opponent),
            )
        } else {
        val inMatchEndTransition = endTransition != null
        val screenMatch = if (inMatchEndTransition) endTransition!!.displayMatch else match
        val currentRound = screenMatch.currentRoundData()
        val drawReplay = screenMatch.pendingDrawReplay()
        val pendingOutcome = screenMatch.pendingRoundOutcome()
        val openRound = if (inMatchEndTransition) null else screenMatch.openRound()
        val showDrawReveal = !inMatchEndTransition &&
            currentRound?.winner == "tie" &&
            currentRound.player1Choice != null &&
            currentRound.player2Choice != null
        val showOutcomeReveal = !inMatchEndTransition &&
            currentRound?.winner != null &&
            currentRound.winner != "tie" &&
            currentRound.player1Choice != null &&
            currentRound.player2Choice != null
        val awaitingNextRound = !inMatchEndTransition && pendingOutcome != null && openRound != null
        val showPreviousRoundRecap = !inMatchEndTransition &&
            !uiState.hasSubmittedMove &&
            !uiState.isSubmitting &&
            when {
                drawReplay != null -> true
                awaitingNextRound -> true
                else -> false
            }
        val showMovePicker = !inMatchEndTransition &&
            screenMatch.status == MatchStatus.ACTIVE &&
            !uiState.hasSubmittedMove &&
            !uiState.isSubmitting &&
            openRound != null &&
            when {
                showDrawReveal || showOutcomeReveal -> false
                else -> true
            }
        val configuration = LocalConfiguration.current
        val compactLayout = configuration.screenHeightDp < 800 || configuration.screenWidthDp <= 360
        val tightLayout = configuration.screenHeightDp <= 720
        val opponentScoreLabel =
            if (configuration.screenWidthDp < 360) {
                stringResource(R.string.opponent_short)
            } else {
                stringResource(R.string.opponent)
            }

        val lockedChoice = myLockedChoice(
            userId = userId,
            match = screenMatch,
            openRound = openRound,
            lockedMove = uiState.lockedMove,
        )
        val myMove = when {
            inMatchEndTransition -> endTransition!!.selectedMove
            uiState.hasSubmittedMove || uiState.isSubmitting ->
                uiState.lockedMove ?: uiState.pendingMove ?: Move.fromString(lockedChoice)
            else ->
                Move.fromString(lockedChoice) ?: uiState.pendingMove ?: uiState.lockedMove
        }
        val movePickerEnabled = showMovePicker && !uiState.isSubmitting
        val selectedPickerMove = when {
            inMatchEndTransition -> endTransition!!.selectedMove
            uiState.hasSubmittedMove || uiState.isSubmitting -> myMove
            else -> null
        }
        val panelHasSubmittedMove = if (inMatchEndTransition) {
            endTransition!!.hasSubmittedMove
        } else {
            uiState.hasSubmittedMove
        }
        val panelOpponentHasSubmitted = if (inMatchEndTransition) {
            endTransition!!.opponentHasSubmitted
        } else {
            uiState.opponentHasSubmitted
        }
        val panelIsSubmitting = if (inMatchEndTransition) false else uiState.isSubmitting

        val resolvedRound = when {
            showDrawReveal -> currentRound
            drawReplay != null && showPreviousRoundRecap -> drawReplay
            showOutcomeReveal -> currentRound
            pendingOutcome != null && showPreviousRoundRecap -> pendingOutcome
            else -> null
        }
        val (resolvedMyChoice, resolvedOpponentChoice) = when {
            inMatchEndTransition -> {
                endTransition!!.finalResolvedRound?.choicesFor(
                    userId,
                    endTransition!!.revealMatch,
                ) ?: (null to null)
            }
            else -> resolvedRound?.choicesFor(userId, screenMatch) ?: (null to null)
        }
        val panelOutcome = if (inMatchEndTransition) {
            endTransition!!.finalResolvedRound?.let { round ->
                val kind = when (round.winner) {
                    "tie" -> RoundBannerKind.Draw
                    userId -> RoundBannerKind.Win
                    else -> RoundBannerKind.Lose
                }
                MatchRoundOutcome(
                    kind = kind,
                    roundNumber = round.roundNumber,
                    subtitle = "",
                )
            }
        } else when {
            showDrawReveal -> MatchRoundOutcome(
                kind = RoundBannerKind.Draw,
                roundNumber = requireNotNull(currentRound).roundNumber,
                subtitle = roundBannerSubtitle(
                    kind = RoundBannerKind.Draw,
                    compact = compactLayout,
                    showFollowUpHint = false,
                ),
            )
            drawReplay != null && showPreviousRoundRecap -> MatchRoundOutcome(
                kind = RoundBannerKind.Draw,
                roundNumber = drawReplay.roundNumber,
                subtitle = roundBannerSubtitle(
                    kind = RoundBannerKind.Draw,
                    compact = compactLayout,
                    showFollowUpHint = true,
                ),
            )
            showOutcomeReveal -> {
                val round = requireNotNull(currentRound)
                val kind = if (round.winner == userId) {
                    RoundBannerKind.Win
                } else {
                    RoundBannerKind.Lose
                }
                MatchRoundOutcome(
                    kind = kind,
                    roundNumber = round.roundNumber,
                    subtitle = roundBannerSubtitle(
                        kind = kind,
                        compact = compactLayout,
                        showFollowUpHint = awaitingNextRound,
                    ),
                )
            }
            pendingOutcome != null && !showOutcomeReveal && showPreviousRoundRecap -> {
                val kind = if (pendingOutcome.winner == userId) {
                    RoundBannerKind.Win
                } else {
                    RoundBannerKind.Lose
                }
                MatchRoundOutcome(
                    kind = kind,
                    roundNumber = pendingOutcome.roundNumber,
                    subtitle = roundBannerSubtitle(
                        kind = kind,
                        compact = compactLayout,
                        showFollowUpHint = awaitingNextRound,
                    ),
                )
            }
            else -> null
        }
        val (panelMyPresentation, panelOpponentPresentation) = resolvePanelMovePresentations(
            match = screenMatch,
            userId = userId,
            openRound = openRound,
            hasSubmittedMove = panelHasSubmittedMove,
            isSubmitting = panelIsSubmitting,
            opponentHasSubmitted = panelOpponentHasSubmitted,
            myMove = myMove,
            panelOutcome = panelOutcome,
            resolvedMyChoice = resolvedMyChoice,
            resolvedOpponentChoice = resolvedOpponentChoice,
            lockLiveSubmittedPanel = false,
        )
        val panelHeaderOutcome = resolvePanelHeaderOutcome(
            panelOutcome = panelOutcome,
            myMove = panelMyPresentation,
            opponentMove = panelOpponentPresentation,
            match = screenMatch,
            userId = userId,
        )
        val panelStatusMessage = when {
            inMatchEndTransition -> null
            uiState.isSubmitting -> stringResource(R.string.communicating_to_server)
            uiState.hasSubmittedMove && panelOutcome == null -> stringResource(R.string.waiting_for_opponent)
            else -> null
        }
        val pickPrompt = when {
            inMatchEndTransition -> null
            uiState.hasSubmittedMove -> null
            drawReplay != null || awaitingNextRound || showMovePicker ->
                stringResource(R.string.pick_move_per_round)
            else -> null
        }
        val pickerTitle = panelStatusMessage ?: pickPrompt

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.vs_user, screenMatch.opponentName(userId)),
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
                text = stringResource(
                    R.string.round_series,
                    screenMatch.currentRound,
                    formatMatchModeCode(screenMatch.matchMode),
                ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(if (compactLayout) 8.dp else 12.dp))

            val hasClockSnapshot =
                uiState.myClockSeconds != null && uiState.opponentClockSeconds != null
            val inPostFinalRoundPause = !inMatchEndTransition &&
                match.status == MatchStatus.ACTIVE &&
                match.openRound() == null &&
                hasClockSnapshot
            val showTimers = when {
                inMatchEndTransition -> true
                inPostFinalRoundPause -> true
                else ->
                    match.status == MatchStatus.ACTIVE &&
                        hasClockSnapshot &&
                        match.openRound()?.roundStartMs() != null &&
                        uiState.countdownSeconds != null
            }
            if (showTimers) {
                val myClockSeconds = if (inMatchEndTransition) {
                    endTransition!!.myClockSeconds
                } else {
                    uiState.myClockSeconds!!
                }
                val opponentClockSeconds = if (inMatchEndTransition) {
                    endTransition!!.opponentClockSeconds
                } else {
                    uiState.opponentClockSeconds!!
                }
                val roundSecondsRemaining = if (inMatchEndTransition) {
                    endTransition!!.countdownSeconds
                } else {
                    uiState.countdownSeconds
                }
                val opponentSubmitted = if (inMatchEndTransition) {
                    endTransition!!.opponentHasSubmitted
                } else {
                    uiState.opponentHasSubmitted
                }
                val serverMoveSubmitted = if (inMatchEndTransition) {
                    endTransition!!.serverMoveSubmitted
                } else {
                    uiState.serverMoveSubmitted
                }
                val hasSubmittedMove = if (inMatchEndTransition) {
                    endTransition!!.hasSubmittedMove
                } else {
                    uiState.hasSubmittedMove
                }
                GameTimerRow(
                    myClockSeconds = myClockSeconds,
                    opponentClockSeconds = opponentClockSeconds,
                    myClockRunning = !inMatchEndTransition && !serverMoveSubmitted,
                    opponentClockRunning = !inMatchEndTransition && !opponentSubmitted,
                    roundSecondsRemaining = roundSecondsRemaining,
                    isResolvingTimeout = if (inMatchEndTransition) false else uiState.isResolvingTimeout,
                    hasSubmittedMove = hasSubmittedMove,
                    compact = compactLayout,
                    modifier = Modifier.fillMaxWidth(),
                    roundClockRunning = !inMatchEndTransition && !uiState.isResolvingTimeout,
                )
                Spacer(modifier = Modifier.height(if (tightLayout) 6.dp else if (compactLayout) 8.dp else 12.dp))
            } else {
                Spacer(modifier = Modifier.height(if (tightLayout) 4.dp else 8.dp))
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                MatchRoundMovesPanel(
                    opponentLabel = opponentScoreLabel,
                    opponentMove = panelOpponentPresentation,
                    myMove = panelMyPresentation,
                    myWins = screenMatch.myWins(userId),
                    myWinMoves = screenMatch.winMovesFor(userId),
                    opponentWins = screenMatch.opponentWins(userId),
                    opponentWinMoves = screenMatch.winMovesFor(screenMatch.opponentId(userId)),
                    winsToFinish = screenMatch.matchMode.winsToFinish,
                    outcome = panelHeaderOutcome,
                    roundNumber = if (inMatchEndTransition) {
                        endTransition!!.roundKey
                    } else {
                        openRound?.roundNumber ?: screenMatch.currentRound
                    },
                    compact = compactLayout,
                    tight = tightLayout,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            uiState.error?.let { error ->
                Spacer(modifier = Modifier.height(if (tightLayout) 6.dp else 12.dp))
                Text(text = error, color = MaterialTheme.colorScheme.error)
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MovePickerActionTitle(
                title = pickerTitle,
                compact = compactLayout,
                tight = tightLayout,
            )
            MovePicker(
                enabled = movePickerEnabled,
                selectedMove = selectedPickerMove,
                onMove = viewModel::submitMove,
                compact = compactLayout,
                roundKey = if (inMatchEndTransition) {
                    endTransition!!.roundKey
                } else {
                    openRound?.roundNumber
                },
            )
        }
        }
    }
}

@Composable
private fun MovePickerActionTitle(
    title: String?,
    compact: Boolean,
    tight: Boolean,
) {
    val slotHeight = when {
        tight -> 40.dp
        compact -> 48.dp
        else -> 56.dp
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(slotHeight),
        contentAlignment = Alignment.Center,
    ) {
        if (title != null) {
            Text(
                text = title,
                style = when {
                    tight -> MaterialTheme.typography.titleMedium
                    compact -> MaterialTheme.typography.titleLarge
                    else -> MaterialTheme.typography.headlineSmall
                },
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Returns (you, opponent) presentations for left/right panel slots. */
private fun resolvePanelMovePresentations(
    match: Match,
    userId: String,
    openRound: RoundResult?,
    hasSubmittedMove: Boolean,
    isSubmitting: Boolean,
    opponentHasSubmitted: Boolean,
    myMove: Move?,
    panelOutcome: MatchRoundOutcome?,
    resolvedMyChoice: String?,
    resolvedOpponentChoice: String?,
    lockLiveSubmittedPanel: Boolean = false,
): Pair<PanelMovePresentation, PanelMovePresentation> {
    if (lockLiveSubmittedPanel) {
        return PanelMovePresentation(move = myMove, display = PanelMoveDisplay.Secret) to
            PanelMovePresentation(display = PanelMoveDisplay.Secret)
    }
    val lastResolved = match.lastResolvedRound()
    val revealResolvedRound = shouldRevealResolvedRoundMoves(match, userId, openRound)
    val showOpenRoundMoves = openRoundShowsLiveMoves(
        openRound = openRound,
        match = match,
        userId = userId,
        hasSubmittedMove = hasSubmittedMove,
        isSubmitting = isSubmitting,
        opponentHasSubmitted = opponentHasSubmitted,
    )

    if ((panelOutcome != null || revealResolvedRound) && !showOpenRoundMoves) {
        val (myChoice, oppChoice) = when {
            resolvedMyChoice != null || resolvedOpponentChoice != null ->
                resolvedMyChoice to resolvedOpponentChoice
            lastResolved != null -> lastResolved.choicesFor(userId, match)
            else -> null to null
        }
        return PanelMovePresentation(
            move = Move.fromString(myChoice) ?: myMove,
            display = PanelMoveDisplay.Revealed,
        ) to PanelMovePresentation(
            move = Move.fromString(oppChoice),
            display = PanelMoveDisplay.Revealed,
        )
    }

    val mySubmittedOnOpen = openRound?.hasSubmittedFor(userId, match.player1) == true
    val oppSubmittedOnOpen = openRound?.opponentHasSubmittedFor(userId, match.player1) == true
    val mySubmitted = when {
        openRound != null -> mySubmittedOnOpen || isSubmitting
        else -> hasSubmittedMove || isSubmitting
    }
    val opponentSubmitted = when {
        openRound != null -> oppSubmittedOnOpen || opponentHasSubmitted
        else -> opponentHasSubmitted
    }
    val myPresentationMove = if (mySubmitted) myMove else null

    return when {
        !mySubmitted && !opponentSubmitted -> {
            PanelMovePresentation(display = PanelMoveDisplay.Waiting) to
                PanelMovePresentation(display = PanelMoveDisplay.Waiting)
        }
        mySubmitted && !opponentSubmitted -> {
            PanelMovePresentation(move = myPresentationMove, display = PanelMoveDisplay.Secret) to
                PanelMovePresentation(display = PanelMoveDisplay.Waiting)
        }
        !mySubmitted && opponentSubmitted -> {
            PanelMovePresentation(display = PanelMoveDisplay.Waiting) to
                PanelMovePresentation(display = PanelMoveDisplay.Secret)
        }
        else -> {
            PanelMovePresentation(move = myPresentationMove, display = PanelMoveDisplay.Secret) to
                PanelMovePresentation(display = PanelMoveDisplay.Secret)
        }
    }
}

private fun resolvePanelHeaderOutcome(
    panelOutcome: MatchRoundOutcome?,
    myMove: PanelMovePresentation,
    opponentMove: PanelMovePresentation,
    match: Match,
    userId: String,
): MatchRoundOutcome? {
    if (panelOutcome != null) return panelOutcome
    if (
        myMove.display != PanelMoveDisplay.Revealed ||
        opponentMove.display != PanelMoveDisplay.Revealed
    ) {
        return null
    }
    val lastResolved = match.lastResolvedRound() ?: return null
    val kind = when (lastResolved.winner) {
        "tie" -> RoundBannerKind.Draw
        userId -> RoundBannerKind.Win
        else -> RoundBannerKind.Lose
    }
    return MatchRoundOutcome(
        kind = kind,
        roundNumber = lastResolved.roundNumber,
        subtitle = "",
    )
}

/** Show both move icons after a round resolves and before anyone picks the next open round. */
private fun shouldRevealResolvedRoundMoves(
    match: Match,
    userId: String,
    openRound: RoundResult?,
): Boolean {
    val lastResolved = match.lastResolvedRound() ?: return false
    if (
        lastResolved.resolvedAt == null ||
        lastResolved.player1Choice == null ||
        lastResolved.player2Choice == null
    ) {
        return false
    }
    if (openRound == null) {
        return true
    }
    if (openRound.roundNumber <= lastResolved.roundNumber) {
        return false
    }
    return !openRound.hasSubmittedFor(userId, match.player1) &&
        !openRound.opponentHasSubmittedFor(userId, match.player1)
}

/** Once the open round has a submission, show blind-play slots instead of last round's icons. */
private fun openRoundShowsLiveMoves(
    openRound: RoundResult?,
    match: Match,
    userId: String,
    hasSubmittedMove: Boolean,
    isSubmitting: Boolean,
    opponentHasSubmitted: Boolean,
): Boolean {
    if (openRound == null) return false
    return hasSubmittedMove ||
        isSubmitting ||
        opponentHasSubmitted ||
        openRound.hasSubmittedFor(userId, match.player1) ||
        openRound.opponentHasSubmittedFor(userId, match.player1)
}

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

/** Frozen regular in-match layout for the post-final-round pause before results. */
private data class MatchEndTransitionUi(
    val displayMatch: Match,
    val revealMatch: Match,
    val finalResolvedRound: RoundResult?,
    val myClockSeconds: Int,
    val opponentClockSeconds: Int,
    val countdownSeconds: Int?,
    val selectedMove: Move?,
    val hasSubmittedMove: Boolean,
    val serverMoveSubmitted: Boolean,
    val opponentHasSubmitted: Boolean,
    val roundKey: Int,
)

private fun buildMatchEndTransitionUi(
    displayMatch: Match,
    terminal: Match,
    userId: String,
    uiState: GameUiState,
): MatchEndTransitionUi {
    val maxClockSeconds = (GameRules.MAX_CLOCK_MS / 1_000).toInt()
    val myClockSeconds = uiState.myClockSeconds
        ?: clockSecondsFromMatch(displayMatch, userId, myPlayer = true, maxClockSeconds)
    val opponentClockSeconds = uiState.opponentClockSeconds
        ?: clockSecondsFromMatch(displayMatch, userId, myPlayer = false, maxClockSeconds)
    val finalResolvedRound = terminal.lastResolvedRound()
    val selectedMove = finalResolvedRound?.let { round ->
        val choice = if (userId == terminal.player1) round.player1Choice else round.player2Choice
        Move.fromString(choice)
    } ?: uiState.lockedMove ?: uiState.pendingMove
    val roundKey = finalResolvedRound?.roundNumber ?: displayMatch.currentRound
    return MatchEndTransitionUi(
        displayMatch = displayMatch,
        revealMatch = terminal,
        finalResolvedRound = finalResolvedRound,
        myClockSeconds = myClockSeconds,
        opponentClockSeconds = opponentClockSeconds,
        countdownSeconds = uiState.countdownSeconds,
        selectedMove = selectedMove,
        hasSubmittedMove = true,
        serverMoveSubmitted = uiState.serverMoveSubmitted || selectedMove != null,
        opponentHasSubmitted = uiState.opponentHasSubmitted || finalResolvedRound != null,
        roundKey = roundKey,
    )
}

private fun clockSecondsFromMatch(
    match: Match,
    userId: String,
    myPlayer: Boolean,
    maxClockSeconds: Int,
): Int {
    val ms = if (myPlayer) match.myClockMs(userId) else match.opponentClockMs(userId)
    return ((ms + 999) / 1_000).toInt().coerceIn(0, maxClockSeconds)
}

