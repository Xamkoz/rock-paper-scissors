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
import com.rpsonline.app.platform.PresenceEngagementTracker
import com.rpsonline.app.ui.RpsApp
import com.rpsonline.app.ui.util.enableImmersiveFullscreen

class MainActivity : ComponentActivity() {
    private var screenReceiver: BroadcastReceiver? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PresenceEngagementTracker.syncScreenInteractive(this)
        PresenceEngagementTracker.recordInteraction()
        enableEdgeToEdge()
        enableImmersiveFullscreen()
        setContent {
            RpsApp()
        }
    }

    override fun onStart() {
        super.onStart()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> PresenceEngagementTracker.setScreenInteractive(false)
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

    override fun onStop() {
        screenReceiver?.let { unregisterReceiver(it) }
        screenReceiver = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        PresenceEngagementTracker.syncScreenInteractive(this)
        PresenceEngagementTracker.recordInteraction()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        PresenceEngagementTracker.recordInteraction()
    }
}
