package com.rpsonline.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class PresenceOnlineCountTest {

    @Test
    fun countOnlineUids_countsFreshPresenceOnly() {
        val nowMs = 1_700_000_000_000L

        assertEquals(
            2,
            PresenceRepository.countOnlineUids(
                lastSeenByUid = mapOf(
                    "fresh" to nowMs - 30_000L,
                    "stale" to nowMs - 120_000L,
                ),
                nowMs = nowMs,
                selfUid = "me",
            ),
        )
    }

    @Test
    fun countOnlineUids_dropsPlayersAfterPresenceWindow() {
        val nowMs = 1_700_000_000_000L

        assertEquals(
            1,
            PresenceRepository.countOnlineUids(
                lastSeenByUid = mapOf(
                    "gone" to nowMs - PresenceRepository.ONLINE_PRESENCE_WINDOW_MS - 1_000L,
                ),
                nowMs = nowMs,
                selfUid = "me",
            ),
        )
    }

    @Test
    fun onlineUidsFromLastSeen_keepsGracePeriodAfterStaleHeartbeat() {
        val nowMs = 1_700_000_000_000L
        val lastOnlineEmittedAt = mutableMapOf("player" to nowMs - 10_000L)

        assertEquals(
            setOf("player"),
            PresenceRepository.onlineUidsFromLastSeen(
                tracked = setOf("player"),
                lastSeenByUid = mapOf(
                    "player" to nowMs - PresenceRepository.ONLINE_PRESENCE_WINDOW_MS - 1_000L,
                ),
                lastOnlineEmittedAt = lastOnlineEmittedAt,
                nowMs = nowMs,
            ),
        )
    }

    @Test
    fun onlineUidsFromLastSeen_dropsPlayerAfterGraceExpires() {
        val nowMs = 1_700_000_000_000L
        val lastOnlineEmittedAt = mutableMapOf(
            "player" to nowMs - PresenceRepository.ONLINE_DISPLAY_GRACE_MS - 1_000L,
        )

        assertEquals(
            emptySet<String>(),
            PresenceRepository.onlineUidsFromLastSeen(
                tracked = setOf("player"),
                lastSeenByUid = mapOf(
                    "player" to nowMs - PresenceRepository.ONLINE_PRESENCE_WINDOW_MS - 1_000L,
                ),
                lastOnlineEmittedAt = lastOnlineEmittedAt,
                nowMs = nowMs,
            ),
        )
    }
}
