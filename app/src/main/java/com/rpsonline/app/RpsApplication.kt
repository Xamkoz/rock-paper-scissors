package com.rpsonline.app

import android.app.Application
import android.content.Context
import com.google.firebase.FirebaseApp
import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.repository.MatchSessionMonitor
import com.rpsonline.app.platform.MatchForegroundLaunchCoordinator
import com.rpsonline.app.platform.MatchNotificationHelper
import com.rpsonline.app.platform.MatchmakingBackgroundCoordinator
import com.rpsonline.app.ui.util.GameAudioContext
import com.rpsonline.app.ui.util.MatchClockSoundController

class RpsApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(GameAudioContext.wrap(AppLocale.wrap(base)))
    }

    override fun onCreate() {
        super.onCreate()
        MatchClockSoundController.initialize(this)
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
