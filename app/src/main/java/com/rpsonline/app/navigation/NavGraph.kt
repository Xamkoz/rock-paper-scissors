package com.rpsonline.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rpsonline.app.data.repository.AuthRepository
import com.rpsonline.app.data.repository.PresenceRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.rpsonline.app.ui.appearance.AppearanceScreen
import com.rpsonline.app.ui.auth.SignInScreen
import com.rpsonline.app.ui.game.GameScreen
import com.rpsonline.app.ui.home.HomeScreen
import com.rpsonline.app.ui.leaderboard.LeaderboardScreen
import com.rpsonline.app.ui.matchmaking.MatchmakingScreen
import com.rpsonline.app.ui.result.ResultScreen

object Routes {
    const val SIGN_IN = "sign_in"
    const val HOME = "home"
    const val MATCHMAKING = "matchmaking"
    const val GAME = "game/{matchId}"
    const val RESULT = "result/{matchId}"
    const val LEADERBOARD = "leaderboard"
    const val APPEARANCE = "appearance"

    fun game(matchId: String) = "game/$matchId"
    fun result(matchId: String) = "result/$matchId"
}

@Composable
fun RpsNavGraph() {
    val navController = rememberNavController()
    val authRepository = remember { AuthRepository() }
    val presenceRepository = remember { PresenceRepository() }
    var isSignedIn by remember { mutableStateOf(authRepository.currentUser != null) }
    var signedInUserId by remember { mutableStateOf(authRepository.currentUserId) }
    LaunchedEffect(Unit) {
        authRepository.authStateFlow().collect { user ->
            isSignedIn = user != null
            signedInUserId = user?.uid
            if (user == null) {
                navController.navigate(Routes.SIGN_IN) {
                    popUpTo(navController.graph.id) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
    }

    LaunchedEffect(signedInUserId) {
        val uid = signedInUserId ?: return@LaunchedEffect
        try {
            presenceRepository.touchPresence(uid)
            while (isActive) {
                delay(PresenceRepository.HEARTBEAT_INTERVAL_MS)
                presenceRepository.touchPresence(uid)
            }
        } finally {
            runCatching { presenceRepository.clearPresence(uid) }
        }
    }

    val startDestination = if (authRepository.currentUser != null) Routes.HOME else Routes.SIGN_IN

    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(Routes.SIGN_IN) {
            SignInScreen(
                onSignedIn = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.SIGN_IN) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.HOME) {
            if (isSignedIn) {
                HomeScreen(
                    onFindMatch = { navController.navigate(Routes.MATCHMAKING) },
                    onLeaderboard = { navController.navigate(Routes.LEADERBOARD) },
                    onAppearance = { navController.navigate(Routes.APPEARANCE) },
                )
            }
        }

        composable(Routes.MATCHMAKING) {
            MatchmakingScreen(
                onMatchFound = { matchId ->
                    navController.navigate(Routes.game(matchId)) {
                        popUpTo(Routes.HOME)
                    }
                },
                onCancel = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.GAME,
            arguments = listOf(navArgument("matchId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val matchId = backStackEntry.arguments?.getString("matchId") ?: return@composable
            GameScreen(
                matchId = matchId,
                onMatchComplete = { completedMatchId ->
                    navController.navigate(Routes.result(completedMatchId)) {
                        popUpTo(Routes.game(matchId)) { inclusive = true }
                    }
                },
                onMatchAbandoned = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.game(matchId)) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = Routes.RESULT,
            arguments = listOf(navArgument("matchId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val matchId = backStackEntry.arguments?.getString("matchId") ?: return@composable
            ResultScreen(
                matchId = matchId,
                onPlayAgain = {
                    navController.navigate(Routes.MATCHMAKING) {
                        popUpTo(Routes.HOME)
                    }
                },
                onHome = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.LEADERBOARD) {
            LeaderboardScreen(
                onBackToHome = { navController.popBackStack() },
            )
        }

        composable(Routes.APPEARANCE) {
            AppearanceScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }
}
