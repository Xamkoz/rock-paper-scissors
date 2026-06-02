package com.rpsonline.app.ui.home

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import android.widget.Toast
import com.rpsonline.app.BuildConfig
import com.rpsonline.app.R
import com.rpsonline.app.data.model.MatchStatus
import com.rpsonline.app.data.repository.MatchSessionMonitor
import com.rpsonline.app.data.update.ReleaseChangelog
import com.rpsonline.app.domain.MatchMode
import com.rpsonline.app.ui.components.AppUpdateDialogs
import com.rpsonline.app.ui.components.LocalNetworkConnectionStatus
import com.rpsonline.app.ui.components.MatchHistoryCard
import com.rpsonline.app.ui.components.ProfileSummaryCard
import com.rpsonline.app.ui.components.RpsHeroPrimaryButton
import com.rpsonline.app.ui.components.RpsOutlinedActionButton
import com.rpsonline.app.ui.components.PlayerDisplayNameText
import com.rpsonline.app.ui.components.ProvideOnlinePresence
import com.rpsonline.app.ui.components.isServerConnected
import com.rpsonline.app.ui.components.ownProfileDisplayName
import com.rpsonline.app.ui.components.RpsLoadingColumn
import com.rpsonline.app.ui.components.RpsCard
import com.rpsonline.app.ui.components.rpsScreenPadding
import com.rpsonline.app.ui.util.PreGameReadyFeedbackEffect
import com.rpsonline.app.ui.util.formatQueueTime
import com.rpsonline.app.ui.util.rememberQueueElapsedSeconds
import com.rpsonline.app.ui.util.findActivity
import com.rpsonline.app.data.model.MatchHistoryEntry
import com.rpsonline.app.viewmodel.AppUpdateViewModel
import com.rpsonline.app.viewmodel.HomeViewModel
import com.rpsonline.app.viewmodel.PreGameSyncUiState

@Composable
fun HomeScreen(
    onReconnectToGame: (String) -> Unit,
    onMyOpponents: () -> Unit,
    onLeaderboard: () -> Unit,
    onProfile: () -> Unit,
    onPlayerProfile: (String) -> Unit = {},
    onChangelog: () -> Unit,
    autoStartMatchmaking: Boolean = false,
    viewModel: HomeViewModel = viewModel(),
    updateViewModel: AppUpdateViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val isServerConnected = LocalNetworkConnectionStatus.current.isServerConnected()
    val openingMatchId by viewModel.navigateToGameMatchId.collectAsState()
    val matchmakingInProgress by MatchSessionMonitor.matchmakingInProgress.collectAsStateWithLifecycle()
    val queueJoinedAtMs by MatchSessionMonitor.queueJoinedAtMs.collectAsStateWithLifecycle()
    val queueTimerAnchorMs by MatchSessionMonitor.queueTimerAnchorMs.collectAsStateWithLifecycle()
    val queueAnchorMs = queueTimerAnchorMs ?: queueJoinedAtMs
    val activeMatch by MatchSessionMonitor.activeMatch.collectAsStateWithLifecycle()
    val updateState by updateViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val hapticFeedback = LocalHapticFeedback.current
    val activity = context.findActivity()

    LaunchedEffect(Unit) {
        viewModel.onHomeVisible(context)
        updateViewModel.onScreenVisible(context)
    }

    LifecycleResumeEffect(Unit) {
        viewModel.reconcileQueueOnResume(context)
        onPauseOrDispose { }
    }

    var autoMatchmakingStarted by remember(autoStartMatchmaking) { mutableStateOf(false) }
    LaunchedEffect(autoStartMatchmaking, uiState.profile, isServerConnected) {
        if (
            autoStartMatchmaking &&
            !autoMatchmakingStarted &&
            uiState.profile != null &&
            isServerConnected
        ) {
            autoMatchmakingStarted = true
            viewModel.startMatchmakingWithSavedPreferences(context)
        }
    }

    AppUpdateDialogs(
        updateState = updateState,
        activity = activity,
        viewModel = updateViewModel,
    )

    PreGameReadyFeedbackEffect(preGameSync = uiState.preGameSync)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .rpsScreenPadding(),
    ) {
        if (uiState.isSigningOut) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                RpsLoadingColumn()
            }
            return
        }

        if (uiState.isLoading && uiState.profile == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                RpsLoadingColumn(message = stringResource(R.string.loading_profile))
            }
            return
        }

        if (uiState.profile == null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = uiState.error ?: stringResource(R.string.could_not_load_profile),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(onClick = { viewModel.refresh() }) {
                    Text(stringResource(R.string.retry))
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = { viewModel.signOut(context) }) {
                    Text(stringResource(R.string.sign_out))
                }
            }
            return
        }

        val profile = uiState.profile
        val selectedModes = uiState.selectedMatchModes
        val matchModesLocked = uiState.isJoiningQueue || uiState.isInQueue
        val scrollState = rememberScrollState()
        val showMatchFoundPhase = activeMatch?.status == MatchStatus.ACTIVE &&
            (
                matchmakingInProgress ||
                    (
                        openingMatchId != null &&
                            activeMatch?.id == openingMatchId
                        )
                )

        val showHighlightedMatch = !uiState.isHighlightedMatchDismissed && uiState.highlightedMatch != null

        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
        Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(4.dp))
        HomeWelcomeHeaderRow(
            onSignOutConfirmed = { viewModel.signOut(context) },
        )
        Spacer(modifier = Modifier.height(6.dp))

        ProvideOnlinePresence(uids = listOfNotNull(profile?.uid)) {
            ProfileSummaryCard(
                displayName = ownProfileDisplayName(profile?.displayName),
                profile = profile,
                playerUid = profile?.uid,
                emphasized = true,
                onClick = onProfile,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MatchMode.entries.forEach { mode ->
                    val selected = mode in selectedModes
                    val contentColor = if (matchModesLocked) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .toggleable(
                                value = selected,
                                enabled = !matchModesLocked,
                                role = Role.Checkbox,
                                onValueChange = { viewModel.toggleMatchMode(context, mode) },
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = null,
                            enabled = !matchModesLocked,
                            modifier = Modifier.graphicsLayer {
                                scaleX = 1.2f
                                scaleY = 1.2f
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary,
                                uncheckedColor = MaterialTheme.colorScheme.outline,
                                checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                                disabledUncheckedColor = MaterialTheme.colorScheme.outline,
                                disabledCheckedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
                            ),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = contentColor,
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        uiState.matchmakingError?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (uiState.preGameSync != null) {
            val preGameSync = uiState.preGameSync!!
            val readySecondsRemaining = rememberPreGameReadySecondsRemaining(preGameSync.readyDeadlineAtMs)
            ProvideOnlinePresence(uids = listOf(preGameSync.opponentUid)) {
                PreGameSyncStatusCard(
                    state = preGameSync,
                    readySecondsRemaining = readySecondsRemaining,
                    onPlayerProfile = onPlayerProfile,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        } else if (
            (uiState.isJoiningQueue || uiState.isInQueue || openingMatchId != null || matchmakingInProgress) &&
            uiState.activeMatchId == null
        ) {
            val liveQueueElapsed = rememberQueueElapsedSeconds(
                anchorMs = queueAnchorMs?.takeIf {
                    uiState.isInQueue || uiState.isJoiningQueue || matchmakingInProgress
                },
            )
            HomeQueueStatusCard(
                phase = when {
                    showMatchFoundPhase -> QueueStatusPhase.MatchFound
                    uiState.isJoiningQueue || queueAnchorMs == null -> QueueStatusPhase.Joining
                    else -> QueueStatusPhase.Searching
                },
                queueElapsedSeconds = liveQueueElapsed ?: uiState.queueElapsedSeconds,
            )
            if (uiState.isJoiningQueue || uiState.isInQueue) {
                Spacer(modifier = Modifier.height(8.dp))
                RpsOutlinedActionButton(
                    onClick = { viewModel.leaveQueue() },
                    text = stringResource(R.string.leave_queue),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        } else if (
            uiState.preGameSync == null &&
            uiState.activeMatchId != null &&
            !matchmakingInProgress &&
            openingMatchId == null
        ) {
            HomeReconnectToGameActionCard(
                onClick = { onReconnectToGame(uiState.activeMatchId!!) },
            )
            Spacer(modifier = Modifier.height(8.dp))
        } else if (uiState.preGameSync == null && uiState.activeMatchId == null) {
            HomeFindMatchActionCard(
                onClick = { viewModel.startMatchmaking(context, selectedModes) },
                enabled = isServerConnected,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (showMatchFoundPhase || openingMatchId != null) {
            RpsHeroPrimaryButton(
                onClick = {},
                text = stringResource(R.string.opening_match),
                enabled = false,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        }

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = maxHeight)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.Bottom,
            ) {
                if (showHighlightedMatch) {
                    uiState.highlightedMatch?.let { highlightedMatch ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            HomeHighlightedMatchCard(
                                entry = highlightedMatch,
                                onPlayerProfile = onPlayerProfile,
                                onDismiss = { viewModel.dismissHighlightedMatch() },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    RpsOutlinedActionButton(
                        onClick = onMyOpponents,
                        text = stringResource(R.string.my_opponents),
                    )
                    RpsOutlinedActionButton(
                        onClick = onLeaderboard,
                        text = stringResource(R.string.leaderboard),
                    )
                    HomeAppInfoFooter(
                        versionName = updateState.versionName,
                        updatesEnabled = BuildConfig.GITHUB_UPDATES_ENABLED,
                        availableUpdate = updateState.availableUpdate,
                        isCheckingForUpdate = updateState.isCheckingForUpdate,
                        isDownloadingUpdate = updateState.isDownloadingUpdate,
                        updateMessage = updateState.updateMessage,
                        onCheckForUpdate = { updateViewModel.checkForUpdate(context) },
                        onInstallUpdate = {
                            activity?.let { updateViewModel.downloadAndInstallUpdate(it) }
                                ?: updateViewModel.showUpdatePrompt()
                        },
                        onVersionClick = onChangelog,
                        onVersionLongClick = {
                            val version = updateState.versionName.trim()
                            if (version.isBlank() && updateState.availableUpdate?.tag.isNullOrBlank()) {
                                return@HomeAppInfoFooter
                            }
                            val tag = if (BuildConfig.DEBUG) {
                                updateState.availableUpdate?.tag?.takeIf { it.isNotBlank() }
                                    ?: ReleaseChangelog.tagForInstalledVersion(version)
                            } else {
                                ReleaseChangelog.tagForInstalledVersion(version)
                            }
                            val apkUrl =
                                "https://github.com/${BuildConfig.GITHUB_REPO_OWNER}/${BuildConfig.GITHUB_REPO_NAME}/releases/download/$tag/rps-online-$tag.apk"
                            clipboardManager.setText(AnnotatedString(apkUrl))
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            Toast.makeText(
                                context,
                                context.getString(R.string.apk_link_copied),
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun HomeHighlightedMatchCard(
    entry: MatchHistoryEntry,
    onPlayerProfile: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    MatchHistoryCard(
        entry = entry,
        title = stringResource(R.string.highlighted_match),
        onDismiss = onDismiss,
        contentVerticalPadding = 2.dp,
        contentTopPadding = 12.dp,
        compactHeader = true,
        matchHeaderTopPadding = 8.dp,
        titleStyle = MaterialTheme.typography.titleMedium,
        onMyProfile = onPlayerProfile,
        onOpponentProfile = onPlayerProfile,
    )
}

@Composable
private fun HomeWelcomeHeaderRow(
    onSignOutConfirmed: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Welcome!",
            style = MaterialTheme.typography.headlineMedium,
            color = scheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .widthIn(min = 0.dp)
                .padding(end = 8.dp),
        )
        HomeSignOutMiniWidget(onSignOutConfirmed = onSignOutConfirmed)
    }
}

private enum class QueueStatusPhase {
    Joining,
    Searching,
    MatchFound,
}

@Composable
private fun PreGameSyncStatusCard(
    state: PreGameSyncUiState,
    readySecondsRemaining: Int,
    onPlayerProfile: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    RpsCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = scheme.primaryContainer.copy(alpha = 0.94f),
        borderColor = scheme.primary.copy(alpha = 0.55f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.match_found),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onPrimaryContainer.copy(alpha = 0.85f),
            )
            PreGameSyncPrimaryLine(
                myDisplayName = state.myDisplayName,
                opponentDisplayName = state.opponentDisplayName,
                opponentUid = state.opponentUid,
                myReady = state.myReady,
                opponentReady = state.opponentReady,
                onOpponentProfile = { onPlayerProfile(state.opponentUid) },
            )
            HomeMatchmakingStatusSubtitleSlot(
                text = if (readySecondsRemaining > 0) {
                    stringResource(R.string.pre_game_ready_countdown, readySecondsRemaining)
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun PreGameSyncPrimaryLine(
    myDisplayName: String,
    opponentDisplayName: String,
    opponentUid: String,
    myReady: Boolean,
    opponentReady: Boolean,
    onOpponentProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primaryColor = MaterialTheme.colorScheme.onPrimaryContainer
    val timerTypography = MaterialTheme.typography.headlineMedium
    val timerStyle = timerTypography.copy(fontWeight = FontWeight.Bold)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val timerReference = formatQueueTime(5_999)

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val maxWidthPx = constraints.maxWidth.coerceAtLeast(0)
        val slotHeightPx = textMeasurer.measure(
            text = timerReference,
            style = timerStyle,
            maxLines = 1,
            constraints = Constraints(maxWidth = maxWidthPx),
        ).size.height
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { slotHeightPx.toDp() }),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PreGamePlayerReadyColumn(
                    displayName = myDisplayName,
                    ready = myReady,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.pre_game_vs),
                    modifier = Modifier.padding(horizontal = 6.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = primaryColor.copy(alpha = 0.6f),
                )
                PreGamePlayerReadyColumn(
                    displayName = opponentDisplayName,
                    playerUid = opponentUid,
                    ready = opponentReady,
                    onClick = onOpponentProfile,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PreGamePlayerReadyColumn(
    displayName: String,
    playerUid: String? = null,
    ready: Boolean,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val readyColor = scheme.primary
    val waitingColor = scheme.onPrimaryContainer.copy(alpha = 0.55f)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        PlayerDisplayNameText(
            name = displayName,
            uid = playerUid,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp,
                lineHeight = 12.sp,
            ),
            defaultColor = scheme.onPrimaryContainer,
            textAlign = TextAlign.Center,
            maxLines = 2,
            onClick = onClick,
        )
        Icon(
            imageVector = if (ready) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = stringResource(
                if (ready) R.string.player_ready else R.string.player_not_ready,
            ),
            tint = if (ready) readyColor else waitingColor,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun rememberPreGameReadySecondsRemaining(deadlineAtMs: Long): Int {
    var remaining by remember(deadlineAtMs) {
        mutableIntStateOf(preGameReadySecondsRemaining(deadlineAtMs))
    }
    LaunchedEffect(deadlineAtMs) {
        while (isActive) {
            remaining = preGameReadySecondsRemaining(deadlineAtMs)
            if (remaining <= 0) break
            delay(1_000)
        }
    }
    return remaining
}

private fun preGameReadySecondsRemaining(deadlineAtMs: Long): Int {
    if (deadlineAtMs <= 0L) return 0
    return ((deadlineAtMs - System.currentTimeMillis()) / 1_000L).toInt().coerceAtLeast(0)
}

/**
 * Single matchmaking status card: label / primary line / subtitle.
 * "Communicating to server" and "In queue · 00:11 · Finding opponent" share the same layout.
 */
@Composable
private fun HomeQueueStatusCard(
    phase: QueueStatusPhase,
    queueElapsedSeconds: Long?,
) {
    HomeMatchmakingStatusCard(
        label = stringResource(
            when (phase) {
                QueueStatusPhase.MatchFound -> R.string.match_found
                else -> R.string.in_queue
            },
        ),
        primary = when (phase) {
            QueueStatusPhase.Joining -> stringResource(R.string.communicating_to_server)
            QueueStatusPhase.Searching -> formatQueueTime(checkNotNull(queueElapsedSeconds))
            QueueStatusPhase.MatchFound -> stringResource(R.string.opening_game)
        },
        primaryStyle = when (phase) {
            QueueStatusPhase.Joining -> HomeMatchmakingPrimaryStyle.Body
            QueueStatusPhase.Searching, QueueStatusPhase.MatchFound -> HomeMatchmakingPrimaryStyle.Timer
        },
        subtitle = when (phase) {
            QueueStatusPhase.MatchFound -> null
            else -> stringResource(R.string.finding_opponent)
        },
    )
}
