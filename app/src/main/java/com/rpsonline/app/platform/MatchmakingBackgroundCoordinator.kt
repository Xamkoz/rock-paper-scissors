package com.rpsonline.app.platform

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus
import com.rpsonline.app.data.preferences.MatchmakingPreferences
import com.rpsonline.app.data.repository.MatchSessionMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Keeps [MatchmakingForegroundService] aligned with queue/match session state and user prefs. */
object MatchmakingBackgroundCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null

    fun ensureObserving(context: Context) {
        if (observeJob?.isActive == true) return
        MatchSessionMonitor.ensureStarted()
        val appContext = context.applicationContext
        observeJob = scope.launch {
            combine(
                MatchSessionMonitor.activeMatch,
                MatchSessionMonitor.hasQueueEntry,
                MatchSessionMonitor.queueJoinedAtMs,
                MatchSessionMonitor.matchmakingInProgress,
            ) { match, hasQueueEntry, queueJoinedAtMs, matchmakingInProgress ->
                sessionNeedsBackgroundService(
                    match = match,
                    hasQueueEntry = hasQueueEntry,
                    queueJoinedAtMs = queueJoinedAtMs,
                    matchmakingInProgress = matchmakingInProgress,
                )
            }
                .distinctUntilChanged()
                .collect { sync(appContext) }
        }
    }

    fun sessionNeedsBackgroundService(): Boolean =
        sessionNeedsBackgroundService(
            match = MatchSessionMonitor.activeMatch.value,
            hasQueueEntry = MatchSessionMonitor.hasQueueEntry.value,
            queueJoinedAtMs = MatchSessionMonitor.queueJoinedAtMs.value,
            matchmakingInProgress = MatchSessionMonitor.matchmakingInProgress.value,
        )

    private fun sessionNeedsBackgroundService(
        match: Match?,
        hasQueueEntry: Boolean,
        queueJoinedAtMs: Long?,
        matchmakingInProgress: Boolean,
    ): Boolean {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return false
        if (match != null && match.isParticipant(uid)) {
            when (match.status) {
                MatchStatus.LOBBY, MatchStatus.ACTIVE -> return true
                else -> Unit
            }
        }
        return hasQueueEntry || matchmakingInProgress || queueJoinedAtMs != null
    }

    fun shouldRunService(context: Context): Boolean {
        if (FirebaseAuth.getInstance().currentUser?.uid == null) return false
        if (!MatchmakingPreferences(context).isBackgroundUsageEnabled()) return false
        return sessionNeedsBackgroundService()
    }

    fun sync(context: Context) {
        MatchmakingForegroundService.sync(
            context.applicationContext,
            shouldRunService(context),
        )
    }
}
