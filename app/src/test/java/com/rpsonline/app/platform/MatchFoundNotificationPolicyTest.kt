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
    fun retainsNotificationWhenLobbyAlertPhase() {
        JoinMatchNotificationState.beginLobbyAlertPhase(lobbyMatch)
        try {
            assertFalse(
                MatchFoundNotificationPolicy.shouldDismissJoinMatchNotification(
                    match = null,
                    uid = "u1",
                    visibleMatchScreenId = null,
                    activeJoinMatchNotificationId = null,
                ),
            )
        } finally {
            JoinMatchNotificationState.clear()
        }
    }

    @Test
    fun retainsNotificationWhenStickyLobbyPresent() {
        val sticky = lobbyMatch.copy(status = MatchStatus.LOBBY)
        JoinMatchNotificationState.bindLobby(sticky)
        try {
            assertFalse(
                MatchFoundNotificationPolicy.shouldDismissJoinMatchNotification(
                    match = null,
                    uid = "u1",
                    visibleMatchScreenId = null,
                    activeJoinMatchNotificationId = null,
                ),
            )
            assertTrue(
                MatchFoundNotificationPolicy.shouldDismissJoinMatchNotification(
                    match = null,
                    uid = "u1",
                    visibleMatchScreenId = "m1",
                    activeJoinMatchNotificationId = null,
                ),
            )
        } finally {
            JoinMatchNotificationState.clear()
        }
    }

    @Test
    fun retainsNotificationWhenSessionMatchAbsent() {
        assertFalse(
            MatchFoundNotificationPolicy.shouldDismissJoinMatchNotification(
                match = null,
                uid = "u1",
                visibleMatchScreenId = null,
                activeJoinMatchNotificationId = "m1",
            ),
        )
        assertTrue(
            MatchFoundNotificationPolicy.shouldDismissJoinMatchNotification(
                match = null,
                uid = "u1",
                visibleMatchScreenId = null,
                activeJoinMatchNotificationId = null,
            ),
        )
    }

    @Test
    fun maintainsLobbyNotificationInBackground() {
        assertTrue(
            MatchFoundNotificationPolicy.shouldMaintainJoinMatchNotification(
                appInForeground = false,
                match = lobbyMatch,
                uid = "u1",
                visibleMatchScreenId = null,
                matchFoundNotificationsEnabled = true,
                backgroundUsageEnabled = true,
                hasPostNotificationsPermission = true,
            ),
        )
        assertFalse(
            MatchFoundNotificationPolicy.shouldMaintainJoinMatchNotification(
                appInForeground = false,
                match = lobbyMatch,
                uid = "u1",
                visibleMatchScreenId = "m1",
                matchFoundNotificationsEnabled = true,
                backgroundUsageEnabled = true,
                hasPostNotificationsPermission = true,
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
    fun keepsInMatchNotificationUntilGameScreenOpened() {
        val active = lobbyMatch.copy(status = MatchStatus.ACTIVE)
        assertFalse(
            MatchFoundNotificationPolicy.shouldDismissJoinMatchNotification(
                match = active,
                uid = "u1",
                visibleMatchScreenId = null,
            ),
        )
        assertTrue(
            MatchFoundNotificationPolicy.shouldMaintainInMatchNotification(
                match = active,
                uid = "u1",
                visibleMatchScreenId = null,
            ),
        )
        assertFalse(
            MatchFoundNotificationPolicy.shouldMaintainInMatchNotification(
                match = active,
                uid = "u1",
                visibleMatchScreenId = "m1",
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
    fun skipsWhenAppInForegroundAndNotificationsDisabled() {
        assertFalse(
            MatchFoundNotificationPolicy.shouldShowJoinMatchNotification(
                appInForeground = true,
                matchStatus = MatchStatus.LOBBY,
                matchFoundNotificationsEnabled = false,
                backgroundUsageEnabled = true,
                hasPostNotificationsPermission = true,
                matchId = "m1",
            ),
        )
    }

    @Test
    fun showsWhenAppInForegroundAndNotificationsEnabled() {
        assertTrue(
            MatchFoundNotificationPolicy.shouldShowJoinMatchNotification(
                appInForeground = true,
                matchStatus = MatchStatus.LOBBY,
                matchFoundNotificationsEnabled = true,
                backgroundUsageEnabled = false,
                hasPostNotificationsPermission = true,
                matchId = "m1",
            ),
        )
        assertTrue(
            MatchFoundNotificationPolicy.shouldMaintainJoinMatchNotification(
                appInForeground = true,
                match = lobbyMatch,
                uid = "u1",
                visibleMatchScreenId = null,
                matchFoundNotificationsEnabled = true,
                backgroundUsageEnabled = false,
                hasPostNotificationsPermission = true,
            ),
        )
    }

    @Test
    fun suppressesMatchFoundAlertsWhileInActiveMatchWithBackgroundUsage() {
        val active = lobbyMatch.copy(status = MatchStatus.ACTIVE)
        assertTrue(
            MatchFoundNotificationPolicy.shouldSuppressMatchFoundAlerts(
                liveMatch = active,
                uid = "u1",
                backgroundUsageEnabled = true,
            ),
        )
        assertFalse(
            MatchFoundNotificationPolicy.shouldShowJoinMatchNotification(
                appInForeground = false,
                matchStatus = MatchStatus.LOBBY,
                matchFoundNotificationsEnabled = true,
                backgroundUsageEnabled = true,
                hasPostNotificationsPermission = true,
                matchId = "m1",
                liveSessionMatch = active,
                uid = "u1",
            ),
        )
        assertFalse(
            MatchFoundNotificationPolicy.shouldMaintainJoinMatchNotification(
                appInForeground = false,
                match = lobbyMatch,
                uid = "u1",
                visibleMatchScreenId = null,
                matchFoundNotificationsEnabled = true,
                backgroundUsageEnabled = true,
                hasPostNotificationsPermission = true,
                liveSessionMatch = active,
            ),
        )
    }

    @Test
    fun defersShadeToForegroundServiceWhenItOwnsDisplay() {
        assertFalse(
            MatchFoundNotificationPolicy.shouldShowJoinMatchNotification(
                appInForeground = true,
                matchStatus = MatchStatus.LOBBY,
                matchFoundNotificationsEnabled = true,
                backgroundUsageEnabled = true,
                hasPostNotificationsPermission = true,
                matchId = "m1",
                foregroundServiceOwnsDisplay = true,
            ),
        )
        assertFalse(
            MatchFoundNotificationPolicy.shouldMaintainJoinMatchNotification(
                appInForeground = true,
                match = lobbyMatch,
                uid = "u1",
                visibleMatchScreenId = null,
                matchFoundNotificationsEnabled = true,
                backgroundUsageEnabled = true,
                hasPostNotificationsPermission = true,
                foregroundServiceOwnsDisplay = true,
            ),
        )
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

    @Test
    fun matchFoundGateAllowsOncePerMatchId() {
        MatchFoundNotificationGate.reset()
        try {
            assertTrue(MatchFoundNotificationGate.tryNotify("m1"))
            assertFalse(MatchFoundNotificationGate.tryNotify("m1"))
            assertTrue(MatchFoundNotificationGate.tryNotify("m2"))
        } finally {
            MatchFoundNotificationGate.reset()
        }
    }
}
