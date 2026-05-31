package com.rpsonline.app

import android.app.Application
import android.content.Context
import com.google.firebase.FirebaseApp
import com.rpsonline.app.data.repository.MatchSessionMonitor
import com.rpsonline.app.platform.MatchNotificationHelper
import com.rpsonline.app.platform.MatchmakingBackgroundCoordinator

class RpsApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        MatchSessionMonitor.ensureStarted()
        MatchNotificationHelper.ensureChannels(this)
        MatchmakingBackgroundCoordinator.ensureObserving(this)
    }
}
