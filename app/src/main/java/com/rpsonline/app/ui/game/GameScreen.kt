package com.rpsonline.app.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import com.rpsonline.app.ui.components.PlayerDisplayNameText
import com.rpsonline.app.ui.components.ProvideOnlinePresence
import com.rpsonline.app.ui.components.RpsLoadingColumn
import com.rpsonline.app.ui.components.rpsScreenPadding
import com.rpsonline.app.data.repository.MatchSessionMonitor
import com.rpsonline.app.domain.GameRules
import com.rpsonline.app.ui.LocalSoundFeedbackMode
import com.rpsonline.app.ui.util.LocalRoundResolutionPulse
import com.rpsonline.app.ui.util.MATCH_END_NAVIGATION_DELAY_MS
import com.rpsonline.app.ui.util.MoveSoundPlayer
import com.rpsonline.app.ui.util.awaitMatchEndResolutionFeedback
import com.rpsonline.app.ui.util.playLiveRoundResolutionFeedback
import com.rpsonline.app.ui.util.triggerMatchFoundFeedback
import com.rpsonline.app.viewmodel.GameTimerUiState
import com.rpsonline.app.viewmodel.GameUiState
import com.rpsonline.app.viewmodel.GameViewModel
import com.rpsonline.app.viewmodel.shouldShowWaitingForOpponentMessage
import kotlinx.coroutines.delay

@Composable
fun GameScreen(
    matchId: String,
    onMatchComplete: (String) -> Unit,
    onOpponentProfile: (String) -> Unit = {},
    viewModel: GameViewModel = viewModel(factory = GameViewModel.factory(matchId)),
) {
    val uiState by viewModel.uiState.collectAsState()
    val monitorMatch by MatchSessionMonitor.activeMatch.collectAsStateWithLifecycle()
    val userId = uiState.userId
    val screenMatch = uiState.match?.takeIf { it.id == matchId }
        ?: monitorMatch?.takeIf { it.id == matchId }
    val context = LocalContext.current
    val pulseNotifier = LocalRoundResolutionPulse.current
    val soundFeedbackMode = LocalSoundFeedbackMode.current
    val moveSoundPlayer = remember(context) { MoveSoundPlayer(context) }
    val terminalMatch = when {
        screenMatch?.status == MatchStatus.COMPLETED || screenMatch?.status == MatchStatus.ABANDONED -> screenMatch
        monitorMatch?.id == matchId &&
            (monitorMatch?.status == MatchStatus.COMPLETED || monitorMatch?.status == MatchStatus.ABANDONED) ->
            monitorMatch
        else -> null
    }

    var navigatedToResult by remember(matchId) { mutableStateOf(false) }
    var frozenEndTransition by remember(matchId) { mutableStateOf<MatchEndTransitionUi?>(null) }
    val endTransition = frozenEndTransition ?: run {
        val terminal = terminalMatch ?: return@run null
        val uid = userId ?: return@run null
        val liveMatch = screenMatch?.takeIf { it.status == MatchStatus.ACTIVE }
        buildMatchEndTransitionUi(
            displayMatch = liveMatch ?: terminal,
            terminal = terminal,
            userId = uid,
            uiState = uiState,
            timerState = viewModel.timerUiState.value,
        ).also { frozenEndTransition = it }
    }

    DisposableEffect(matchId) {
        triggerMatchFoundFeedback(context, matchId)
        MatchSessionMonitor.setVisibleMatchScreenId(matchId)
        pulseNotifier?.enterLiveMatch(matchId)
        onDispose {
            MatchSessionMonitor.setVisibleMatchScreenId(null)
            pulseNotifier?.leaveLiveMatch(matchId)
            moveSoundPlayer.release()
        }
    }

    LifecycleResumeEffect(matchId, userId) {
        viewModel.refreshOnResume()
        onPauseOrDispose { }
    }

    LaunchedEffect(monitorMatch?.status, monitorMatch?.id, screenMatch?.status, matchId) {
        val monitor = monitorMatch ?: return@LaunchedEffect
        if (monitor.id != matchId) return@LaunchedEffect
        if (monitor.status != MatchStatus.COMPLETED && monitor.status != MatchStatus.ABANDONED) {
            return@LaunchedEffect
        }
        if (screenMatch?.status == monitor.status) return@LaunchedEffect
        viewModel.refreshOnResume()
    }

    LaunchedEffect(endTransition?.roundKey, matchId) {
        if (navigatedToResult) return@LaunchedEffect
        if (endTransition == null) return@LaunchedEffect
        val current = terminalMatch
            ?: screenMatch?.takeIf { it.status == MatchStatus.COMPLETED || it.status == MatchStatus.ABANDONED }
            ?: monitorMatch?.takeIf {
                it.id == matchId &&
                    (it.status == MatchStatus.COMPLETED || it.status == MatchStatus.ABANDONED)
            }
            ?: return@LaunchedEffect
        when (current.status) {
            MatchStatus.COMPLETED -> {
                val resolved = endTransition.finalResolvedRound
                val needsDualReveal = resolved?.player1Choice != null && resolved.player2Choice != null
                if (needsDualReveal) {
                    delay(DUAL_SELECTION_MIN_DISPLAY_MS + ROUND_RECAP_REVEALED_MIN_DISPLAY_MS)
                }
                awaitMatchEndResolutionFeedback(
                    pulseNotifier = pulseNotifier,
                    match = current,
                    userId = userId,
                    feedbackMode = soundFeedbackMode,
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
        if (screenMatch == null || userId == null) {
            RpsLoadingColumn(modifier = Modifier.weight(1f))
        } else if (screenMatch.status == MatchStatus.LOBBY) {
            RpsLoadingColumn(
                modifier = Modifier.weight(1f),
                message = stringResource(R.string.waiting_for_opponent),
            )
        } else {
        val opponentUid = screenMatch.opponentId(userId)
        ProvideOnlinePresence(uids = listOf(opponentUid)) {
        val inMatchEndTransition = endTransition != null
        val layoutMatch = if (inMatchEndTransition) endTransition!!.displayMatch else screenMatch
        val currentRound = layoutMatch.currentRoundData()
        val drawReplay = layoutMatch.pendingDrawReplay()
        val pendingOutcome = layoutMatch.pendingRoundOutcome()
        val openRound = if (inMatchEndTransition) null else layoutMatch.openRound()
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
        val showPendingRoundOutcome = shouldShowPendingRoundOutcome(
            awaitingNextRound = awaitingNextRound,
            pendingOutcome = pendingOutcome,
        )
        val showResolvedRoundRecap = showPreviousRoundRecap || showPendingRoundOutcome
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
            match = layoutMatch,
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
            drawReplay != null && showResolvedRoundRecap -> drawReplay
            showOutcomeReveal -> currentRound
            showPendingRoundOutcome -> pendingOutcome
            else -> null
        }
        val serverRoundSettled = isServerRoundSettled(
            showOutcomeReveal = showOutcomeReveal,
            showDrawReveal = showDrawReveal,
            awaitingNextRound = awaitingNextRound,
            hasPendingOutcome = pendingOutcome != null,
        )
        val betweenRoundsRecapRound = resolveBetweenRoundsRecapRound(
            pendingOutcome = pendingOutcome,
            lastResolved = layoutMatch.lastResolvedRound(),
            openRound = openRound,
            suppressWhileLocalBlindComplete = shouldSuppressBetweenRoundsRecap(
                openRound = openRound,
                localHasSubmitted = panelHasSubmittedMove,
                localOpponentSubmitted = panelOpponentHasSubmitted,
                serverRoundSettled = serverRoundSettled,
            ),
        )
        val recapRound = if (inMatchEndTransition) {
            endTransition!!.finalResolvedRound?.takeIf {
                it.player1Choice != null && it.player2Choice != null
            }
        } else {
            resolveRecapRound(
                resolvedRound = resolvedRound,
                pendingOutcome = pendingOutcome,
                lastResolved = layoutMatch.lastResolvedRound(),
                openRound = openRound,
            )
        }
        val recapChoices = recapRound?.choicesFor(userId, layoutMatch)
        val (resolvedMyChoice, resolvedOpponentChoice) = when {
            inMatchEndTransition -> {
                endTransition!!.finalResolvedRound?.choicesFor(
                    userId,
                    endTransition!!.revealMatch,
                ) ?: (null to null)
            }
            else -> recapChoices ?: (null to null)
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
            drawReplay != null && showResolvedRoundRecap -> MatchRoundOutcome(
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
            showPendingRoundOutcome && !showOutcomeReveal -> {
                val outcome = requireNotNull(pendingOutcome)
                val kind = if (outcome.winner == userId) {
                    RoundBannerKind.Win
                } else {
                    RoundBannerKind.Lose
                }
                MatchRoundOutcome(
                    kind = kind,
                    roundNumber = outcome.roundNumber,
                    subtitle = roundBannerSubtitle(
                        kind = kind,
                        compact = compactLayout,
                        showFollowUpHint = awaitingNextRound,
                    ),
                )
            }
            else -> recapRound?.let { round ->
                val kind = when (round.winner) {
                    "tie" -> RoundBannerKind.Draw
                    userId -> RoundBannerKind.Win
                    else -> RoundBannerKind.Lose
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
        }
        val openRoundResolving = !serverRoundSettled && isOpenRoundResolving(openRound)
        val shouldHoldRecap = if (inMatchEndTransition) {
            recapRound != null
        } else {
            shouldHoldForResolvedRound(
                resolvedRound = recapRound,
                player1Choice = recapRound?.player1Choice,
                player2Choice = recapRound?.player2Choice,
                showOutcomeReveal = showOutcomeReveal,
                showDrawReveal = showDrawReveal,
                awaitingNextRound = awaitingNextRound,
                betweenRoundsRecapRound = betweenRoundsRecapRound,
            )
        }
        val recapPhaseActive = if (inMatchEndTransition) {
            recapRound != null
        } else {
            shouldActivateRoundRecapPhase(
                shouldHoldForResolvedRound = shouldHoldRecap,
                openRoundResolving = openRoundResolving,
                serverRoundSettled = serverRoundSettled,
            )
        }
        val holdForResolvedRound = recapPhaseActive
        val dualRevealRoundKey = when {
            recapRound != null -> dualRevealHoldRoundKey(recapRound)
            inMatchEndTransition -> endTransition!!.roundKey
            else -> null
        }
        val recapPhaseOpen = isRecapPhaseOpen(
            recapRound = recapRound,
            recapPhaseActive = recapPhaseActive,
            serverRoundSettled = serverRoundSettled,
        )
        val dualRevealAllowed = rememberDualSelectionRevealAllowed(
            roundKey = dualRevealRoundKey,
            gateActive = recapPhaseOpen,
        )
        val recapRevealStarted = recapPhaseOpen && dualRevealAllowed
        val recapDismissed = rememberRoundRecapDismissed(
            roundKey = dualRevealRoundKey,
            recapRevealStarted = recapRevealStarted,
        )
        val holdMatchEndRecapGate = shouldHoldMatchEndRecapUntilPostMatch(
            inMatchEndTransition = inMatchEndTransition,
            recapRevealStarted = recapRevealStarted,
            navigatedToPostMatch = navigatedToResult,
        )
        val showRecapRoundMoves = shouldShowRecapRoundMovesInPhase(
            recapPhaseOpen = recapPhaseOpen,
            recapDismissed = recapDismissed,
            holdMatchEndRecapGate = holdMatchEndRecapGate,
        )
        val recapDismissedForUi = recapDismissedForUi(
            recapDismissed = recapDismissed,
            holdMatchEndRecapGate = holdMatchEndRecapGate,
        )
        val inDualSelectionHold = showRecapRoundMoves && !dualRevealAllowed
        LaunchedEffect(inMatchEndTransition, recapRevealStarted, dualRevealRoundKey, userId, terminalMatch?.id) {
            if (!inMatchEndTransition || !recapRevealStarted || userId == null) return@LaunchedEffect
            val terminal = terminalMatch ?: return@LaunchedEffect
            val resolved = endTransition?.finalResolvedRound ?: return@LaunchedEffect
            if (resolved.player1Choice == null || resolved.player2Choice == null) return@LaunchedEffect
            val notifier = pulseNotifier ?: return@LaunchedEffect
            playLiveRoundResolutionFeedback(
                context = context,
                match = terminal,
                resolved = resolved,
                userId = userId,
                mode = soundFeedbackMode,
                moveSoundPlayer = moveSoundPlayer,
                pulseNotifier = notifier,
            )
        }
        val scoreGateRequested = shouldHoldRecap && recapRound != null
        val scoreGateRoundKey = recapRound?.let { dualRevealHoldRoundKey(it) }
        val scoreGateOpen = rememberScoreGateOpen(
            roundKey = scoreGateRoundKey,
            gateRequested = scoreGateRequested,
        )
        val inRecapDualHold = inDualSelectionHold
        val preferResolvedRoundPanel = preferResolvedRoundPanel(
            showOutcomeReveal = showOutcomeReveal,
            showDrawReveal = showDrawReveal,
            showPreviousRoundRecap = showPreviousRoundRecap,
            showPendingRoundOutcome = showPendingRoundOutcome,
            hasDrawReplay = drawReplay != null,
            roundRecapComplete = recapPhaseActive,
            inRecapDualHold = inRecapDualHold,
            recapDismissed = recapDismissedForUi,
        )
        val openRoundAwaitingPicks = isOpenRoundAwaitingPicks(
            openRound = openRound,
            hasSubmittedMove = panelHasSubmittedMove,
            isSubmitting = panelIsSubmitting,
            opponentHasSubmitted = panelOpponentHasSubmitted,
            player1 = layoutMatch.player1,
            userId = userId,
        )
        val displayPanelOutcome = panelOutcome?.takeIf {
            showRecapRoundMoves && dualRevealAllowed
        }
        val blockLiveResolvedReveal = shouldBlockLiveResolvedMoveReveal(
            serverRoundSettled = serverRoundSettled,
            recapRound = recapRound,
            recapPhaseActive = recapPhaseActive,
            openRoundResolving = openRoundResolving,
        )
        val allowRoundMovePicker = shouldAllowRoundMovePicker(
            showRecapRoundMoves = showRecapRoundMoves,
            holdForResolvedRound = holdForResolvedRound,
            recapDismissed = recapDismissedForUi,
            showOutcomeReveal = showOutcomeReveal,
            showDrawReveal = showDrawReveal,
        )
        val showMovePicker = !inMatchEndTransition &&
            layoutMatch.status == MatchStatus.ACTIVE &&
            !uiState.error.orEmpty().contains("not active", ignoreCase = true) &&
            (!uiState.hasSubmittedMove || uiState.error != null) &&
            !uiState.isSubmitting &&
            openRound != null &&
            allowRoundMovePicker
        val movePickerEnabled = showMovePicker && !uiState.isSubmitting
        val selectedPickerMove = when {
            inMatchEndTransition -> endTransition!!.selectedMove
            uiState.hasSubmittedMove || uiState.isSubmitting -> myMove
            else -> null
        }
        val showOpenRoundLiveMoves = (
            openRoundShowsLiveMoves(
                openRound = openRound,
                match = layoutMatch,
                userId = userId,
                hasSubmittedMove = panelHasSubmittedMove,
                isSubmitting = panelIsSubmitting,
                opponentHasSubmitted = panelOpponentHasSubmitted,
            ) || (
                awaitingNextRound &&
                    recapDismissedForUi &&
                    openRoundAwaitingPicks
                )
            ) && !preferResolvedRoundPanel && !showRecapRoundMoves
        val (panelMyPresentationRaw, panelOpponentPresentationRaw) = if (
            showRecapRoundMoves && recapRound != null
        ) {
            resolveRecapMovePresentations(
                myChoice = resolvedMyChoice,
                opponentChoice = resolvedOpponentChoice,
            )
        } else {
            resolvePanelMovePresentations(
                match = layoutMatch,
                userId = userId,
                openRound = openRound,
                hasSubmittedMove = panelHasSubmittedMove,
                isSubmitting = panelIsSubmitting,
                opponentHasSubmitted = panelOpponentHasSubmitted,
                myMove = myMove,
                panelOutcome = displayPanelOutcome,
                resolvedMyChoice = resolvedMyChoice,
                resolvedOpponentChoice = resolvedOpponentChoice,
                showOpenRoundMoves = showOpenRoundLiveMoves,
                blockLiveResolvedReveal = blockLiveResolvedReveal,
            )
        }
        val panelMyPresentationFinal = if (inDualSelectionHold) {
            holdMoveReveal(panelMyPresentationRaw, revealAllowed = false)
        } else {
            panelMyPresentationRaw
        }
        val panelOpponentPresentation = if (inDualSelectionHold) {
            holdMoveReveal(panelOpponentPresentationRaw, revealAllowed = false)
        } else {
            panelOpponentPresentationRaw
        }
        val targetMyWins = layoutMatch.myWins(userId)
        val targetOpponentWins = layoutMatch.opponentWins(userId)
        val (heldMyWins, heldOpponentWins) = scoresBeforeRecapRound(
            myWins = targetMyWins,
            opponentWins = targetOpponentWins,
            recapRound = recapRound,
            userId = userId,
        )
        val animatedScores = rememberAnimatedMatchScores(
            gateOpen = scoreGateOpen,
            heldMy = heldMyWins,
            targetMy = targetMyWins,
            heldOpponent = heldOpponentWins,
            targetOpponent = targetOpponentWins,
            roundKey = scoreGateRoundKey,
        )
        val displayMyWins = animatedScores.myWins
        val displayOpponentWins = animatedScores.opponentWins
        val displayMyWinMoves = if (
            scoreGateOpen && targetMyWins > heldMyWins
        ) {
            winMovesBeforeRecapRound(layoutMatch, userId, recapRound)
        } else {
            layoutMatch.winMovesFor(userId)
        }
        val displayOpponentWinMoves = if (
            scoreGateOpen && targetOpponentWins > heldOpponentWins
        ) {
            winMovesBeforeRecapRound(layoutMatch, layoutMatch.opponentId(userId), recapRound)
        } else {
            layoutMatch.winMovesFor(layoutMatch.opponentId(userId))
        }
        val panelHeaderOutcome = resolvePanelHeaderOutcome(
            panelOutcome = displayPanelOutcome,
            myMove = panelMyPresentationFinal,
            opponentMove = panelOpponentPresentation,
            match = layoutMatch,
            userId = userId,
        )
        val panelStatusMessage = when {
            inMatchEndTransition -> null
            uiState.isSubmitting -> stringResource(R.string.communicating_to_server)
            shouldShowWaitingForOpponentMessage(
                hasSubmittedMove = uiState.hasSubmittedMove,
                opponentHasSubmitted = uiState.opponentHasSubmitted,
                isSubmitting = uiState.isSubmitting,
                isResolvingTimeout = uiState.isResolvingTimeout,
                hasOpenRound = openRound != null,
                hasPanelOutcome = displayPanelOutcome != null,
                roundRecapActive = showRecapRoundMoves,
                awaitingServerRoundResolve = openRoundResolving,
                opponentSubmittedOnServer = openRound?.opponentHasSubmittedFor(
                    userId,
                    layoutMatch.player1,
                ) == true,
            ) -> stringResource(R.string.waiting_for_opponent)
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.vs_label),
                    style = if (compactLayout) {
                        MaterialTheme.typography.titleLarge
                    } else {
                        MaterialTheme.typography.headlineSmall
                    },
                    color = MaterialTheme.colorScheme.onBackground,
                )
                PlayerDisplayNameText(
                    name = layoutMatch.opponentName(userId),
                    uid = opponentUid,
                    style = if (compactLayout) {
                        MaterialTheme.typography.titleLarge
                    } else {
                        MaterialTheme.typography.headlineSmall
                    },
                    modifier = Modifier.padding(start = 6.dp),
                    onClick = { onOpponentProfile(opponentUid) },
                )
            }
            Spacer(modifier = Modifier.height(if (compactLayout) 4.dp else 8.dp))
            uiState.eloPreview?.let { preview ->
                MatchLiveEloPreviewRow(
                    preview = preview,
                    style = MaterialTheme.typography.labelMedium,
                    colorDeltasBySign = layoutMatch.status == MatchStatus.COMPLETED,
                )
                Spacer(modifier = Modifier.height(if (compactLayout) 2.dp else 4.dp))
            }
            Text(
                text = stringResource(
                    R.string.round_series,
                    layoutMatch.currentRound,
                    formatMatchModeCode(layoutMatch.matchMode),
                ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(if (compactLayout) 8.dp else 12.dp))

            GameMatchTimerSection(
                viewModel = viewModel,
                match = layoutMatch,
                inMatchEndTransition = inMatchEndTransition,
                endTransition = endTransition,
                uiState = uiState,
                compactLayout = compactLayout,
                tightLayout = tightLayout,
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                MatchRoundMovesPanel(
                    opponentLabel = opponentScoreLabel,
                    opponentMove = panelOpponentPresentation,
                    myMove = panelMyPresentationFinal,
                    myWins = displayMyWins,
                    myScoreScoringActive = animatedScores.myScoringActive,
                    myWinMoves = displayMyWinMoves,
                    opponentWins = displayOpponentWins,
                    opponentScoreScoringActive = animatedScores.opponentScoringActive,
                    opponentWinMoves = displayOpponentWinMoves,
                    winsToFinish = layoutMatch.matchMode.winsToFinish,
                    outcome = panelHeaderOutcome,
                    roundNumber = when {
                        inMatchEndTransition -> endTransition!!.roundKey
                        recapRound != null -> recapRound.roundNumber
                        else -> openRound?.roundNumber ?: layoutMatch.currentRound
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
    showOpenRoundMoves: Boolean = false,
    blockLiveResolvedReveal: Boolean = false,
): Pair<PanelMovePresentation, PanelMovePresentation> {
    val lastResolved = match.lastResolvedRound()
    val revealResolvedRound = shouldRevealResolvedRoundMoves(match, userId, openRound) &&
        !blockLiveResolvedReveal

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

@Composable
private fun GameMatchTimerSection(
    viewModel: GameViewModel,
    match: Match,
    inMatchEndTransition: Boolean,
    endTransition: MatchEndTransitionUi?,
    uiState: GameUiState,
    compactLayout: Boolean,
    tightLayout: Boolean,
) {
    val timerState by viewModel.timerUiState.collectAsState()
    val hasClockSnapshot =
        timerState.myClockSeconds != null && timerState.opponentClockSeconds != null
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
                timerState.countdownSeconds != null
    }
    if (!showTimers) {
        Spacer(modifier = Modifier.height(if (tightLayout) 4.dp else 8.dp))
        return
    }
    val myClockSeconds = if (inMatchEndTransition) {
        endTransition!!.myClockSeconds
    } else {
        timerState.myClockSeconds!!
    }
    val opponentClockSeconds = if (inMatchEndTransition) {
        endTransition!!.opponentClockSeconds
    } else {
        timerState.opponentClockSeconds!!
    }
    val roundSecondsRemaining = if (inMatchEndTransition) {
        endTransition!!.countdownSeconds
    } else {
        timerState.countdownSeconds
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
    timerState: GameTimerUiState,
): MatchEndTransitionUi {
    val maxClockSeconds = (GameRules.MAX_CLOCK_MS / 1_000).toInt()
    val myClockSeconds = timerState.myClockSeconds
        ?: clockSecondsFromMatch(displayMatch, userId, myPlayer = true, maxClockSeconds)
    val opponentClockSeconds = timerState.opponentClockSeconds
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
        countdownSeconds = timerState.countdownSeconds,
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

