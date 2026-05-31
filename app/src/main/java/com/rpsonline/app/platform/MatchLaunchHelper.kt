package com.rpsonline.app.platform

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.rpsonline.app.MainActivity

object MatchLaunchHelper {
    const val EXTRA_MATCH_ID = "com.rpsonline.app.extra.MATCH_ID"
    private const val REQUEST_CODE_MATCH_LAUNCH = 42_001

    fun launchMatch(context: Context, matchId: String) {
        val intent = buildLaunchIntent(context, matchId)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                    )
                context.startActivity(intent, options.toBundle())
            } else {
                context.startActivity(intent)
            }
        } catch (_: Exception) {
            val pendingIntent = PendingIntent.getActivity(
                context,
                REQUEST_CODE_MATCH_LAUNCH,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val options = ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                        )
                    pendingIntent.send(
                        context,
                        0,
                        null,
                        null,
                        null,
                        null,
                        options.toBundle(),
                    )
                } else {
                    pendingIntent.send()
                }
            } catch (_: PendingIntent.CanceledException) {
                context.startActivity(intent)
            }
        }
    }

    fun buildLaunchIntent(context: Context, matchId: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_MATCH_ID, matchId)
        }

    fun readMatchId(intent: Intent?): String? =
        intent?.getStringExtra(EXTRA_MATCH_ID)?.takeIf { it.isNotBlank() }
}
