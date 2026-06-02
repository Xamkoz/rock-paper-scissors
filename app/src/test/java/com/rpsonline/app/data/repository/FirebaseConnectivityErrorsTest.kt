package com.rpsonline.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

class FirebaseConnectivityErrorsTest {

    @Test
    fun classifiesDnsFailureAsDefinitive() {
        val error = RuntimeException(
            "Unable to resolve host firestore.googleapis.com",
            UnknownHostException("Unable to resolve host \"firestore.googleapis.com\""),
        )
        assertEquals(FirestoreProbeOutcome.UnreachableDefinitive, classifyFirestoreProbeFailure(error))
        assertTrue(isConnectivityFailure(error))
        assertTrue(hasConnectivityCause(error))
    }

    @Test
    fun classifiesUnknownHostMessageAsDefinitive() {
        val error = RuntimeException("No address associated with hostname")
        assertEquals(FirestoreProbeOutcome.UnreachableDefinitive, classifyFirestoreProbeFailure(error))
    }
}
