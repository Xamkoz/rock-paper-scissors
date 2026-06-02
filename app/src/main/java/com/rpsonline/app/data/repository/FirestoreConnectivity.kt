package com.rpsonline.app.data.repository

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Re-establishes Firestore after the app returns from sleep/background.
 * Safe to call repeatedly; failures are ignored so UI is never blocked.
 */
object FirestoreConnectivity {
    /** Light reconnect after resume — avoids toggling the network on every foreground. */
    suspend fun restoreOnResume() {
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser ?: return
        awaitFirestoreAuth(auth, forceRefresh = false)
        runCatching {
            appFirestore().enableNetwork().await()
        }
    }

    /** Hard reset after connectivity loss (DNS / UNAVAILABLE). */
    suspend fun restoreAfterConnectivityLoss() {
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser ?: return
        withTimeoutOrNull(5_000) {
            user.getIdToken(true).await()
        }
        awaitFirestoreAuth(auth, forceRefresh = true)
        val firestore = appFirestore()
        runCatching {
            firestore.disableNetwork().await()
            firestore.enableNetwork().await()
        }
        runCatching {
            firestore.enableNetwork().await()
        }
    }
}
