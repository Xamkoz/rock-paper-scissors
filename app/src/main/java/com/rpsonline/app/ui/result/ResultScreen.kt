package com.rpsonline.app.ui.result

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rpsonline.app.R
import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus
import com.rpsonline.app.data.model.viewerResolution
import com.rpsonline.app.data.model.UserProfile
import com.rpsonline.app.data.repository.AuthRepository
import com.rpsonline.app.data.repository.MatchRepository
import com.rpsonline.app.data.repository.UserRepository
import com.rpsonline.app.domain.matchResultOutcomeDetail
import com.rpsonline.app.domain.LiveEloPreview
import com.rpsonline.app.domain.opponentEloAtMatch
import com.rpsonline.app.domain.resultEloPreview
import com.rpsonline.app.domain.DisplayNames
import com.rpsonline.app.ui.components.HomeOutlinedButton
import com.rpsonline.app.ui.components.MatchRecapCard
import com.rpsonline.app.ui.components.MatchResolutionOutcomeHeader
import com.rpsonline.app.ui.components.ProfileSummaryCard
import com.rpsonline.app.ui.components.ProvideOnlinePresence
import com.rpsonline.app.ui.components.ownProfileDisplayName
import com.rpsonline.app.ui.components.RpsCard
import com.rpsonline.app.ui.components.RpsLoadingColumn
import com.rpsonline.app.ui.components.formatMatchScore
import com.rpsonline.app.ui.components.profileStatValueColor
import com.rpsonline.app.ui.components.rpsScreenPadding
import com.rpsonline.app.ui.game.MatchLiveEloPreviewRow

@Composable
fun ResultScreen(
    matchId: String,
    onPlayAgain: () -> Unit,
    onHome: () -> Unit,
    onOpponentProfile: (String) -> Unit,
) {
    val authRepository = remember { AuthRepository() }
    val matchRepository = remember { MatchRepository() }
    val userRepository = remember { UserRepository() }
    var match by remember { mutableStateOf<Match?>(null) }
    var myProfile by remember { mutableStateOf<UserProfile?>(null) }
    var myCurrentElo by remember { mutableStateOf<Int?>(null) }
    var opponentProfile by remember { mutableStateOf<UserProfile?>(null) }
    var opponentCurrentElo by remember { mutableStateOf<Int?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(matchId) {
        val userId = authRepository.currentUserId
        match = matchRepository.getMatch(matchId)
        match?.takeIf {
            it.status == MatchStatus.COMPLETED || it.status == MatchStatus.ABANDONED
        }?.let { finished ->
            matchRepository.invalidateConcludedMatchCacheForParticipants(
                finished.player1,
                finished.player2,
            )
        }
        myCurrentElo = userId?.let { userRepository.getUserProfile(it)?.elo }
        myProfile = userId?.let { userRepository.getUserProfile(it) }
        isLoading = false
        val opponentId = userId?.let { uid -> match?.opponentId(uid) } ?: return@LaunchedEffect
        opponentCurrentElo = userRepository.getUserProfile(opponentId)?.elo

        userId?.let { uid ->
            launch {
                userRepository.observeUserProfile(uid).collectLatest { profile ->
                    myProfile = profile
                    myCurrentElo = profile?.elo ?: myCurrentElo
                }
            }
        }

        launch {
            userRepository.observeUserProfile(opponentId).collectLatest { profile ->
                opponentProfile = profile
                opponentCurrentElo = profile?.elo ?: opponentCurrentElo
            }
        }

        // Cloud Functions may finish incrementing throw stats and Elo after the result screen opens.
        repeat(8) {
            delay(2_000)
            userId?.let { uid ->
                userRepository.getUserProfile(uid)?.let { profile ->
                    myProfile = profile
                    myCurrentElo = profile.elo
                }
            }
            userRepository.getUserProfile(opponentId)?.let { profile ->
                opponentProfile = profile
                opponentCurrentElo = profile.elo
            }
        }
    }

    val scrollState = rememberScrollState()
    var playAgainTriggered by remember(matchId) { mutableStateOf(false) }

    Column(
        modifier = Modifier.rpsScreenPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isLoading || match == null) {
            RpsLoadingColumn(modifier = Modifier.fillMaxSize())
            return
        }

        val userId = authRepository.currentUserId
        val currentMatch = match!!
        val myWins = userId?.let { currentMatch.myWins(it) } ?: 0
        val opponentWins = userId?.let { currentMatch.opponentWins(it) } ?: 0
        val resolution = userId?.let { currentMatch.viewerResolution(it) }
        val eloPreview = userId?.let { currentMatch.resultEloPreview(it) }
        val opponentName = userId?.let { currentMatch.opponentName(it) } ?: stringResource(R.string.opponent)
        val myDisplayName = userId?.let { uid ->
            myProfile?.displayName?.takeIf { it.isNotBlank() }
                ?: currentMatch.myName(uid).takeIf { it.isNotBlank() }
        } ?: DisplayNames.DEFAULT
        val opponentId = userId?.let { currentMatch.opponentId(it) }
        val opponentPreMatchElo = userId?.let { uid ->
            myCurrentElo?.let { currentMatch.opponentEloAtMatch(uid, it) }
        }
        val opponentDisplayElo = opponentCurrentElo ?: opponentProfile?.elo ?: opponentPreMatchElo
        val recaps = userId?.let { currentMatch.resolvedRoundRecaps(it) } ?: emptyList()
        val outcomeDetail = matchResultOutcomeDetail(
            match = currentMatch,
            resolution = resolution,
        )
        val presenceUids = buildSet {
            userId?.let { add(it) }
            opponentId?.let { add(it) }
        }

        ProvideOnlinePresence(uids = presenceUids) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MatchResolutionOutcomeHeader(
                resolution = resolution,
                outcomeDetail = outcomeDetail,
            )

        Spacer(modifier = Modifier.height(12.dp))

        FinalScoreCard(
            myWins = myWins,
            opponentWins = opponentWins,
            eloPreview = eloPreview,
        )

        if (userId != null) {
            Spacer(modifier = Modifier.height(12.dp))
            ProfileSummaryCard(
                displayName = ownProfileDisplayName(myDisplayName),
                profile = myProfile,
                playerUid = userId,
                eloOverride = myCurrentElo,
                emphasized = true,
                onClick = { onOpponentProfile(userId) },
            )
        }

        if (opponentId != null) {
            Spacer(modifier = Modifier.height(12.dp))
            ProfileSummaryCard(
                displayName = opponentProfile?.displayName ?: opponentName,
                profile = opponentProfile,
                playerUid = opponentId,
                eloOverride = opponentDisplayElo,
                onClick = { onOpponentProfile(opponentId) },
            )
        }

        if (recaps.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            MatchRecapCard(recaps = recaps)
        }
        }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                if (playAgainTriggered) return@Button
                playAgainTriggered = true
                onPlayAgain()
            },
            enabled = !playAgainTriggered,
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
        ) {
            Text(
                text = stringResource(R.string.play_again),
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        HomeOutlinedButton(onClick = onHome)
    }
}

@Composable
private fun FinalScoreCard(
    myWins: Int,
    opponentWins: Int,
    eloPreview: LiveEloPreview?,
) {
    val scoreStyle = MaterialTheme.typography.titleLarge
    val eloStyle = MaterialTheme.typography.titleMedium
    RpsCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "${stringResource(R.string.final_score_label)} ",
                    style = scoreStyle,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    text = formatMatchScore(myWins, opponentWins),
                    style = scoreStyle,
                    fontWeight = FontWeight.Bold,
                    color = profileStatValueColor(),
                    maxLines = 1,
                )
            }
            eloPreview?.let { preview ->
                MatchLiveEloPreviewRow(
                    preview = preview,
                    style = eloStyle,
                    colorDeltasBySign = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
