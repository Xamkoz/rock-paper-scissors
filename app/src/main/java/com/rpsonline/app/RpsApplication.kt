package com.rpsonline.app

import android.app.Application
import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus
import com.rpsonline.app.data.repository.MatchSessionMonitor
import com.rpsonline.app.platform.AppForegroundTracker
import com.rpsonline.app.platform.JoinMatchNotificationState
import com.rpsonline.app.platform.MatchFoundNotificationPolicy
import com.rpsonline.app.platform.MatchForegroundLaunchCoordinator
import com.rpsonline.app.platform.MatchNotificationHelper
import com.rpsonline.app.platform.MatchmakingBackgroundCoordinator
import com.rpsonline.app.ui.util.GameAudioContext
import com.rpsonline.app.ui.util.MatchClockHaptics
import com.rpsonline.app.ui.util.MatchClockSoundController
import com.rpsonline.app.ui.util.PreGameLobbySoundPolicy

class RpsApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(GameAudioContext.wrap(AppLocale.wrap(base)))
    }

    override fun onCreate() {
        super.onCreate()
        MatchClockSoundController.initialize(this)
        MatchClockHaptics.initialize(this)
        FirebaseApp.initializeApp(this)
        AppLocale.applyFirebaseAuthLanguage()
        MatchSessionMonitor.ensureStarted()
        MatchNotificationHelper.ensureChannels(this)
        MatchmakingBackgroundCoordinator.ensureObserving(this)
        MatchSessionMonitor.onSessionStateChanged = ::handleSessionStateChanged
        MatchSessionMonitor.onQueueRecoveryFailed = ::handleQueueRecoveryFailed
        MatchSessionMonitor.onActiveMatchPublished = ::handleActiveMatchPublished
    }

    private fun handleSessionStateChanged() {
        MatchmakingBackgroundCoordinator.sync(this)
        val match = MatchSessionMonitor.activeMatch.value
        MatchForegroundLaunchCoordinator.onMatchSessionChanged(this, match)
        syncJoinMatchNotification()
    }

    private fun syncJoinMatchNotification() {
        val match = MatchSessionMonitor.activeMatch.value
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        val shouldRunLobbyAlert = PreGameLobbySoundPolicy.shouldRunMatchFoundLobbyAlert(this)
        if (
            MatchFoundNotificationPolicy.shouldDismissJoinMatchNotification(
                match,
                uid,
                MatchSessionMonitor.visibleMatchScreenId.value,
                activeJoinMatchNotificationId =
                    MatchForegroundLaunchCoordinator.activeJoinMatchNotificationId()
                        ?: JoinMatchNotificationState.activeMatchId(),
            )
        ) {
            MatchNotificationHelper.dismissMatchFound(this, match, uid)
        }
        MatchClockSoundController.syncLobbyAlert(shouldRunLobbyAlert)
    }

    private fun handleQueueRecoveryFailed(@Suppress("UNUSED_PARAMETER") message: String) {
        MatchSessionMonitor.setMatchmakingInProgress(false)
        MatchSessionMonitor.clearQueueState()
        MatchmakingBackgroundCoordinator.sync(this)
    }

    private fun handleActiveMatchPublished(match: Match) {
        MatchForegroundLaunchCoordinator.onMatchSessionChanged(this, match)
    }
}
