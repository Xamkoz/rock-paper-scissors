package com.rpsonline.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rpsonline.app.viewmodel.HomeViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.firebase.firestore.FirebaseFirestoreException
import com.rpsonline.app.data.model.MatchStatus
import com.rpsonline.app.data.repository.AuthRepository
import com.rpsonline.app.data.repository.MatchRepository
import com.rpsonline.app.data.repository.MatchSessionMonitor
import com.rpsonline.app.data.repository.shouldDeferHomeForGameLaunch
import com.rpsonline.app.data.repository.shouldDropPendingGameNavigation
import com.rpsonline.app.data.repository.shouldOpenPendingGameScreen
import com.rpsonline.app.ui.auth.SignInScreen
import com.rpsonline.app.ui.changelog.ChangelogScreen
import com.rpsonline.app.ui.components.RpsLoadingColumn
import com.rpsonline.app.ui.game.GameScreen
import com.rpsonline.app.ui.home.HomeScreen
import com.rpsonline.app.ui.leaderboard.LeaderboardScreen
import com.rpsonline.app.ui.opponents.OpponentsScreen
import com.rpsonline.app.ui.profile.ProfileScreen
import com.rpsonline.app.ui.result.ResultScreen
import kotlinx.coroutines.delay

object Routes {
    const val SIGN_IN = "sign_in"
    const val HOME = "home?autoMatchmake={autoMatchmake}"

    fun home(autoStartMatchmaking: Boolean = false): String =
        "home?autoMatchmake=$autoStartMatchmaking"
    const val GAME = "game/{matchId}"
    const val RESULT = "result/{matchId}"
    const val LEADERBOARD = "leaderboard"
    const val OPPONENTS = "opponents"
    const val CHANGELOG = "changelog"
    const val PROFILE = "profile/{userId}"

    fun game(matchId: String) = "game/$matchId"
    fun result(matchId: String) = "result/$matchId"
    fun profile(userId: String) = "profile/$userId"
}

private fun NavHostController.navigateToHome() {
    navigate(Routes.home()) {
        popUpTo(Routes.HOME) { inclusive = true }
        launchSingleTop = true
    }
}

private fun NavHostController.navigateToHomeAfterMatch(matchId: String) {
    MatchSessionMonitor.onMatchFinished(matchId)
    navigateToHome()
}

@Composable
private fun MatchFoundNavigationEffect(navController: NavHostController) {
    val pendingMatchId by MatchSessionMonitor.pendingGameNavigationMatchId
        .collectAsStateWithLifecycle()
    val activeMatch by MatchSessionMonitor.activeMatch.collectAsStateWithLifecycle()
    val backStackEntries by navController.currentBackStack.collectAsStateWithLifecycle()
    val currentRoute = backStackEntries.lastOrNull()?.destination?.route

    LaunchedEffect(pendingMatchId, currentRoute, activeMatch?.id, activeMatch?.status) {
        val matchId = pendingMatchId ?: return@LaunchedEffect
        if (MatchSessionMonitor.isAutoGameNavigationSuppressed(matchId)) {
            MatchSessionMonitor.consumeGameNavigation()
            return@LaunchedEffect
        }
        val sessionMatch = activeMatch
        if (shouldDropPendingGameNavigation(matchId, sessionMatch)) {
            MatchSessionMonitor.consumeGameNavigation()
            return@LaunchedEffect
        }
        if (!shouldOpenPendingGameScreen(matchId, sessionMatch)) {
            return@LaunchedEffect
        }
        if (currentRoute == Routes.game(matchId)) {
            MatchSessionMonitor.consumeGameNavigation()
            MatchSessionMonitor.setMatchmakingInProgress(false)
            return@LaunchedEffect
        }
        if (currentRoute?.startsWith("game/") == true) {
            return@LaunchedEffect
        }
        // Play Again may still be closing the result screen while matchmaking assigns a game.
        if (currentRoute?.startsWith("result/") == true) {
            return@LaunchedEffect
        }
        navController.navigate(Routes.game(matchId)) {
            popUpTo(Routes.HOME) { inclusive = false }
            launchSingleTop = true
        }
        MatchSessionMonitor.consumeGameNavigation()
        MatchSessionMonitor.setMatchmakingInProgress(false)
    }
}

@Composable
fun RpsNavGraph() {
    val navController = rememberNavController()
    val authRepository = remember { AuthRepository() }
    var isSignedIn by remember { mutableStateOf(authRepository.currentUser != null) }

    LaunchedEffect(navController) {
        authRepository.authStateFlow().collect { user ->
            isSignedIn = user != null
            if (user == null) {
                val onSignIn = navController.currentBackStackEntry?.destination?.route == Routes.SIGN_IN
                if (!onSignIn) {
                    navController.navigate(Routes.SIGN_IN) {
                        popUpTo(navController.graph.id) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
        }
    }

    val startDestination = remember {
        when {
            authRepository.currentUser == null -> Routes.SIGN_IN
            else -> {
                val launchMatchId = MatchSessionMonitor.pendingGameLaunchMatchId()
                if (launchMatchId != null) Routes.game(launchMatchId) else Routes.home()
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(Routes.SIGN_IN) {
            SignInScreen(
                onChangelog = { navController.navigate(Routes.CHANGELOG) },
                onSignedIn = {
                    navController.navigate(Routes.home()) {
                        popUpTo(Routes.SIGN_IN) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = Routes.HOME,
            arguments = listOf(
                navArgument("autoMatchmake") {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { backStackEntry ->
            val autoStartMatchmaking = backStackEntry.arguments?.getBoolean("autoMatchmake") ?: false
            val homeViewModel: HomeViewModel = viewModel(backStackEntry)
            val pendingGameNavigation by MatchSessionMonitor.pendingGameNavigationMatchId
                .collectAsStateWithLifecycle()
            val matchLaunchNudge by MatchSessionMonitor.matchLaunchUiNudge
                .collectAsStateWithLifecycle()
            val activeMatch by MatchSessionMonitor.activeMatch.collectAsStateWithLifecycle()
            val matchmakingInProgress by MatchSessionMonitor.matchmakingInProgress
                .collectAsStateWithLifecycle()
            val deferHomeForGameLaunch = remember(
                pendingGameNavigation,
                matchLaunchNudge,
                activeMatch?.id,
                activeMatch?.status,
                matchmakingInProgress,
            ) {
                shouldDeferHomeForGameLaunch(
                    pendingLaunchMatchId = MatchSessionMonitor.pendingGameLaunchMatchId(),
                    matchmakingInProgress = matchmakingInProgress,
                    sessionMatch = activeMatch,
                )
            }
            LaunchedEffect(matchLaunchNudge, activeMatch?.id, activeMatch?.status) {
                val launchId = MatchSessionMonitor.pendingGameLaunchMatchId() ?: return@LaunchedEffect
                if (shouldDropPendingGameNavigation(launchId, activeMatch)) {
                    MatchSessionMonitor.consumeGameNavigation()
                }
            }
            if (isSignedIn) {
                if (deferHomeForGameLaunch) {
                    RpsLoadingColumn(modifier = Modifier.fillMaxSize())
                } else {
                HomeScreen(
                    autoStartMatchmaking = autoStartMatchmaking,
                    viewModel = homeViewModel,
                    onReconnectToGame = { matchId ->
                        MatchSessionMonitor.clearAutoGameNavigationSuppression(matchId)
                        navController.navigate(Routes.game(matchId)) {
                            launchSingleTop = true
                        }
                    },
                    onLeaderboard = { navController.navigate(Routes.LEADERBOARD) },
                    onMyOpponents = { navController.navigate(Routes.OPPONENTS) },
                    onProfile = {
                        authRepository.currentUserId?.let { uid ->
                            navController.navigate(Routes.profile(uid))
                        }
                    },
                    onPlayerProfile = { profileUserId ->
                        navController.navigate(Routes.profile(profileUserId))
                    },
                    onChangelog = { navController.navigate(Routes.CHANGELOG) },
                )
                }
            }
        }

        composable(
            route = Routes.GAME,
            arguments = listOf(navArgument("matchId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val matchId = backStackEntry.arguments?.getString("matchId") ?: return@composable
            BackHandler {
                MatchSessionMonitor.suppressAutoGameNavigation(matchId)
                if (!navController.popBackStack()) {
                    navController.navigate(Routes.home()) {
                        popUpTo(navController.graph.id) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
            GameScreen(
                matchId = matchId,
                onMatchComplete = { completedMatchId ->
                    MatchSessionMonitor.onMatchFinished(completedMatchId)
                    navController.navigate(Routes.result(completedMatchId)) {
                        popUpTo(Routes.HOME) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onOpponentProfile = { profileUserId ->
                    navController.navigate(Routes.profile(profileUserId))
                },
            )
        }

        composable(
            route = Routes.RESULT,
            arguments = listOf(navArgument("matchId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val matchId = backStackEntry.arguments?.getString("matchId") ?: return@composable
            BackHandler {
                navController.navigateToHomeAfterMatch(matchId)
            }
            ResultScreen(
                matchId = matchId,
                onPlayAgain = {
                    MatchSessionMonitor.clearQueueState(endMatchmaking = true)
                    navController.navigate(Routes.home(autoStartMatchmaking = true)) {
                        popUpTo(Routes.HOME) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onHome = { navController.navigateToHomeAfterMatch(matchId) },
                onOpponentProfile = { userId ->
                    navController.navigate(Routes.profile(userId))
                },
            )
        }

        composable(Routes.LEADERBOARD) {
            LeaderboardScreen(
                onHome = { navController.navigateToHome() },
                onPlayerProfile = { userId ->
                    navController.navigate(Routes.profile(userId))
                },
            )
        }

        composable(Routes.OPPONENTS) {
            OpponentsScreen(
                onHome = { navController.navigateToHome() },
                onPlayerProfile = { userId ->
                    navController.navigate(Routes.profile(userId))
                },
            )
        }

        composable(Routes.CHANGELOG) {
            ChangelogScreen(
                onHome = {
                    if (isSignedIn) {
                        navController.navigateToHome()
                    } else {
                        navController.popBackStack()
                    }
                },
            )
        }

        composable(
            route = Routes.PROFILE,
            arguments = listOf(navArgument("userId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val userId = backStackEntry.arguments?.getString("userId") ?: return@composable
            ProfileScreen(
                userId = userId,
                onHome = {
                    if (!navController.popBackStack()) {
                        navController.navigateToHome()
                    }
                },
                onPlayerProfile = { profileUserId ->
                    navController.navigate(Routes.profile(profileUserId))
                },
            )
        }
    }

    MatchFoundNavigationEffect(navController)
    NonGameMatchResolutionEffect(navController)
}

@Composable
private fun NonGameMatchResolutionEffect(navController: NavHostController) {
    val authRepository = remember { AuthRepository() }
    val matchRepository = remember { MatchRepository() }
    val activeMatch by MatchSessionMonitor.activeMatch.collectAsStateWithLifecycle()
    val backStackEntries by navController.currentBackStack.collectAsStateWithLifecycle()

    val currentRoute = backStackEntries.lastOrNull()?.destination?.route
    val onGameRoute = currentRoute?.startsWith("game/") == true || currentRoute == Routes.GAME

    LaunchedEffect(activeMatch, onGameRoute) {
        if (onGameRoute) return@LaunchedEffect
        val userId = authRepository.currentUserId ?: return@LaunchedEffect
        val match = activeMatch ?: return@LaunchedEffect
        if (match.status != MatchStatus.ACTIVE || !match.isParticipant(userId)) return@LaunchedEffect

        while (true) {
            val latest = activeMatch
            if (latest == null || latest.status != MatchStatus.ACTIVE) break

            val openRound = latest.openRound()
            val deadline = openRound?.deadline
            val roundNumber = openRound?.roundNumber
            if (deadline == null || roundNumber == null || System.currentTimeMillis() < deadline) {
                delay(1_000)
                continue
            }

            val myChoiceSubmitted = openRound.hasSubmittedFor(userId, latest.player1)
            if (myChoiceSubmitted) {
                delay(1_000)
                continue
            }

            runCatching {
                matchRepository.requestRoundTimeout(latest.id, roundNumber)
            }.onFailure { error ->
                if (!isIgnorableResolutionFailure(error)) {
                    // Keep retrying in background; this path is intentionally silent.
                }
            }
            delay(3_000)
        }
    }
}

private fun isIgnorableResolutionFailure(error: Throwable): Boolean {
    if (error is FirebaseFirestoreException &&
        error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
    ) {
        return true
    }
    val message = error.message.orEmpty()
    if (message.contains("PERMISSION_DENIED", ignoreCase = true)) return true
    if (message.contains("cancelled", ignoreCase = true)) return true
    return false
}
