package com.rpsonline.app.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class QueueSnapshotPolicyTest {
    @Test
    fun listenerError_whileMatchmaking_retainsSession() {
        assertTrue(
            QueueSnapshotPolicy.shouldRetainSessionOnListenerError(
                matchmakingInProgress = true,
                error = IOException("offline"),
            ),
        )
    }

    @Test
    fun listenerError_whileIdle_doesNotRetain() {
        assertFalse(
            QueueSnapshotPolicy.shouldRetainSessionOnListenerError(
                matchmakingInProgress = false,
                error = IOException("offline"),
            ),
        )
    }

    @Test
    fun missingFromCache_whileMatchmaking_retainsSession() {
        assertTrue(
            QueueSnapshotPolicy.shouldRetainSessionOnMissingDoc(
                matchmakingInProgress = true,
                exists = false,
                fromCache = true,
            ),
        )
    }

    @Test
    fun missingFromServer_whileMatchmaking_doesNotRetain() {
        assertFalse(
            QueueSnapshotPolicy.shouldRetainSessionOnMissingDoc(
                matchmakingInProgress = true,
                exists = false,
                fromCache = false,
            ),
        )
    }

    @Test
    fun authoritativeMissing_isServerConfirmedGap() {
        assertTrue(QueueSnapshotPolicy.isAuthoritativeQueueMissing(exists = false, fromCache = false))
        assertFalse(QueueSnapshotPolicy.isAuthoritativeQueueMissing(exists = false, fromCache = true))
        assertFalse(QueueSnapshotPolicy.isAuthoritativeQueueMissing(exists = true, fromCache = false))
    }
}
