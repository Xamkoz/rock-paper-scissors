package com.rpsonline.app.platform

import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchFoundNotificationPolicyTest {

    private val lobbyMatch = Match(
        id = "m1",
        player1 = "u1",
        player2 = "u2",
        status = MatchStatus.LOBBY,
        createdAt = 0L,
    )

    @Test
    fun showsInBackgroundLobbyWhenBackgroundUsageEnabled() {
        assertTrue(
            MatchFoundNotificationPolicy.shouldShowJoinMatchNotification(
                appInForeground = false,
                matchStatus = MatchStatus.LOBBY,
                matchFoundNotificationsEnabled = false,
                backgroundUsageEnabled = true,
                hasPostNotificationsPermission = true,
                matchId = "m1",
            ),
        )
    }

    @Test
    fun keepsNotificationInForegroundUntilMatchScreen() {
        assertFalse(
            MatchFoundNotificationPolicy.shouldDismissJoinMatchNotification(
                match = lobbyMatch,
                uid = "u1",
                visibleMatchScreenId = null,
            ),
        )
        assertTrue(
            MatchFoundNotificationPolicy.shouldDismissJoinMatchNotification(
                match = lobbyMatch,
                uid = "u1",
                visibleMatchScreenId = "m1",
            ),
        )
    }

    @Test
    fun keepsActiveMatchNotificationUntilGameScreen() {
        val active = lobbyMatch.copy(status = MatchStatus.ACTIVE)
        assertFalse(
            MatchFoundNotificationPolicy.shouldDismissJoinMatchNotification(
                match = active,
                uid = "u1",
                visibleMatchScreenId = null,
            ),
        )
        assertTrue(
            MatchFoundNotificationPolicy.shouldDismissJoinMatchNotification(
                match = active,
                uid = "u1",
                visibleMatchScreenId = "m1",
            ),
        )
    }

    @Test
    fun runsAlertInForegroundWhenBackgroundUsageEnabled() {
        assertTrue(
            MatchFoundNotificationPolicy.shouldRunMatchFoundAlert(
                appInForeground = true,
                matchStatus = MatchStatus.LOBBY,
                matchFoundNotificationsEnabled = false,
                backgroundUsageEnabled = true,
                hasPostNotificationsPermission = true,
                matchId = "m1",
                visibleMatchScreenId = null,
            ),
        )
    }

    @Test
    fun postsShadeInForegroundWhenBackgroundServiceOwnsDisplay() {
        assertTrue(
            MatchFoundNotificationPolicy.shouldPostMatchFoundShadeNotification(
                foregroundServiceOwnsDisplay = true,
                appInForeground = true,
                matchFoundNotificationsEnabled = false,
                backgroundUsageEnabled = true,
            ),
        )
        assertFalse(
            MatchFoundNotificationPolicy.shouldPostMatchFoundShadeNotification(
                foregroundServiceOwnsDisplay = true,
                appInForeground = false,
                matchFoundNotificationsEnabled = true,
                backgroundUsageEnabled = true,
            ),
        )
    }

    @Test
    fun skipsWhenAppInForegroundAndNotificationsDisabled() {
        assertFalse(
            MatchFoundNotificationPolicy.shouldRunMatchFoundAlert(
                appInForeground = true,
                matchStatus = MatchStatus.LOBBY,
                matchFoundNotificationsEnabled = false,
                backgroundUsageEnabled = false,
                hasPostNotificationsPermission = true,
                matchId = "m1",
                visibleMatchScreenId = null,
            ),
        )
    }

    @Test
    fun showsInBackgroundWhileForegroundServiceRuns() {
        assertTrue(
            MatchFoundNotificationPolicy.shouldShowJoinMatchNotification(
                appInForeground = false,
                matchStatus = MatchStatus.LOBBY,
                matchFoundNotificationsEnabled = true,
                backgroundUsageEnabled = true,
                hasPostNotificationsPermission = true,
                matchId = "m1",
            ),
        )
    }

    @Test
    fun highImportanceMatchFoundShade_whenForegroundWithBackgroundService() {
        AppForegroundTracker.setInForeground(true)
        try {
            assertTrue(
                MatchFoundNotificationPolicy.shouldUseHighImportanceMatchFoundShade(
                    backgroundUsageEnabled = true,
                    foregroundServiceRunning = true,
                ),
            )
            assertFalse(
                MatchFoundNotificationPolicy.shouldUseHighImportanceMatchFoundShade(
                    backgroundUsageEnabled = true,
                    foregroundServiceRunning = false,
                ),
            )
        } finally {
            AppForegroundTracker.setInForeground(false)
        }
    }

    @Test
    fun composePathSkipsRepeatMatchId() {
        assertFalse(
            MatchFoundNotificationPolicy.shouldPostJoinMatchNotification(
                appInForeground = false,
                matchStatus = MatchStatus.LOBBY,
                matchFoundNotificationsEnabled = true,
                backgroundUsageEnabled = true,
                hasPostNotificationsPermission = true,
                lastNotifiedMatchId = "m1",
                matchId = "m1",
            ),
        )
    }
}
