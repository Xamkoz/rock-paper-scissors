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
            var serviceRunning = false
            combine(
                MatchSessionMonitor.activeMatch,
                MatchSessionMonitor.hasQueueEntry,
                MatchSessionMonitor.queueJoinedAtMs,
                MatchSessionMonitor.matchmakingInProgress,
            ) { match, hasQueueEntry, queueJoinedAtMs, matchmakingInProgress ->
                SessionNotificationSnapshot(
                    shouldRun = sessionNeedsBackgroundService(
                        match = match,
                        hasQueueEntry = hasQueueEntry,
                        queueJoinedAtMs = queueJoinedAtMs,
                        matchmakingInProgress = matchmakingInProgress,
                    ) && MatchmakingPreferences(appContext).isBackgroundUsageEnabled() &&
                        FirebaseAuth.getInstance().currentUser?.uid != null,
                    matchId = match?.id,
                    matchStatus = match?.status,
                    queueJoinedAtMs = queueJoinedAtMs,
                    hasQueueEntry = hasQueueEntry,
                    matchmakingInProgress = matchmakingInProgress,
                )
            }
                .distinctUntilChanged()
                .collect { snapshot ->
                    if (snapshot.shouldRun != serviceRunning) {
                        serviceRunning = snapshot.shouldRun
                        MatchmakingForegroundService.sync(appContext, snapshot.shouldRun)
                    } else if (snapshot.shouldRun) {
                        MatchmakingForegroundService.refreshNotificationIfRunning()
                    }
                }
        }
    }

    private data class SessionNotificationSnapshot(
        val shouldRun: Boolean,
        val matchId: String?,
        val matchStatus: MatchStatus?,
        val queueJoinedAtMs: Long?,
        val hasQueueEntry: Boolean,
        val matchmakingInProgress: Boolean,
    )

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
        return computeSessionNeedsBackgroundService(
            uid = uid,
            match = match,
            hasQueueEntry = hasQueueEntry,
            queueJoinedAtMs = queueJoinedAtMs,
        )
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

/**
 * Background status notification while in queue, match-found lobby, or active match; off when idle.
 */
internal fun computeSessionNeedsBackgroundService(
    uid: String,
    match: Match?,
    hasQueueEntry: Boolean,
    queueJoinedAtMs: Long?,
): Boolean {
    if (match != null && match.isParticipant(uid)) {
        when (match.status) {
            MatchStatus.LOBBY, MatchStatus.ACTIVE -> return true
            else -> Unit
        }
    }
    return hasQueueEntry || queueJoinedAtMs != null
}
