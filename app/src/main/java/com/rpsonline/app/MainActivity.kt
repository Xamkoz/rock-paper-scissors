package com.rpsonline.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.rpsonline.app.data.model.MatchStatus
import com.rpsonline.app.data.repository.MatchSessionMonitor
import com.rpsonline.app.platform.AppForegroundTracker
import com.rpsonline.app.platform.JoinMatchNotificationState
import com.rpsonline.app.platform.MatchForegroundLaunchCoordinator
import com.rpsonline.app.platform.MatchLaunchHelper
import com.rpsonline.app.platform.MatchmakingBackgroundCoordinator
import com.rpsonline.app.platform.MatchmakingForegroundService
import com.rpsonline.app.platform.PresenceEngagementTracker
import com.rpsonline.app.ui.RpsApp
import com.rpsonline.app.ui.util.GameAudioContext
import com.rpsonline.app.ui.util.enableImmersiveFullscreen

class MainActivity : ComponentActivity() {
    private var screenReceiver: BroadcastReceiver? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(GameAudioContext.wrap(AppLocale.wrap(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PresenceEngagementTracker.syncScreenInteractive(this)
        PresenceEngagementTracker.recordInteraction()
        handleMatchLaunchIntent(intent)
        enableEdgeToEdge()
        enableImmersiveFullscreen()
        setContent {
            RpsApp()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleMatchLaunchIntent(intent)
    }

    private fun handleMatchLaunchIntent(intent: Intent?) {
        val matchId = MatchLaunchHelper.readMatchId(intent) ?: return
        MatchSessionMonitor.ensureStarted()
        MatchSessionMonitor.noteMatchLaunchIntent(matchId)
        MatchForegroundLaunchCoordinator.noteLaunchAttempted(
            matchId = matchId,
            status = MatchSessionMonitor.activeMatch.value?.status,
        )
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val match = MatchSessionMonitor.activeMatch.value
        if (
            match?.id == matchId &&
            match.isParticipant(uid) &&
            (match.status == MatchStatus.ACTIVE || match.status == MatchStatus.LOBBY)
        ) {
            MatchSessionMonitor.requestGameNavigation(matchId)
        } else {
            MatchSessionMonitor.enqueueGameNavigationWhenReady(matchId)
        }
        MatchSessionMonitor.nudgeMatchLaunchUi()
    }

    override fun onStop() {
        AppForegroundTracker.setInForeground(false)
        val match = MatchSessionMonitor.activeMatch.value
        if (match?.status == MatchStatus.LOBBY) {
            MatchForegroundLaunchCoordinator.onMatchSessionChanged(this, match)
        }
        screenReceiver?.let { unregisterReceiver(it) }
        screenReceiver = null
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        PresenceEngagementTracker.setScreenInteractive(false)
                        MatchmakingBackgroundCoordinator.sync(context)
                    }
                    Intent.ACTION_SCREEN_ON -> PresenceEngagementTracker.syncScreenInteractive(context)
                }
            }
        }
        screenReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        AppForegroundTracker.setInForeground(true)
        MatchSessionMonitor.nudgeMatchLaunchUi()
        val lobbyHold = MatchSessionMonitor.activeMatch.value?.status == MatchStatus.LOBBY ||
            JoinMatchNotificationState.lobbyMatch()?.status == MatchStatus.LOBBY
        if (!lobbyHold) {
            MatchmakingForegroundService.clearLaunchAlert()
        }
        MatchmakingForegroundService.retryPendingStart(this)
        MatchmakingBackgroundCoordinator.sync(this)
        PresenceEngagementTracker.syncScreenInteractive(this)
        PresenceEngagementTracker.recordInteraction()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        PresenceEngagementTracker.recordInteraction()
    }
}
