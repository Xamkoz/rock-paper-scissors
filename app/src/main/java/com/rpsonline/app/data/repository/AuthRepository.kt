package com.rpsonline.app.data.repository

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import com.rpsonline.app.data.model.UserProfile
import com.rpsonline.app.domain.DisplayNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class AuthRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = appFirestore(),
) {
    private companion object {
        private const val TAG = "AuthRepository"
        private const val QUEUE_PROFILE_WAIT_MS = 12_000L
        private const val QUEUE_PROFILE_POLL_MS = 250L
    }

    val currentUser: FirebaseUser?
        get() = auth.currentUser

    val currentUserId: String?
        get() = auth.currentUser?.uid

    fun authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            trySend(firebaseAuth.currentUser)
        }
        auth.addAuthStateListener(listener)
        trySend(auth.currentUser)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    suspend fun signInWithGoogle(idToken: String): UserProfile {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val result = auth.signInWithCredential(credential).await()
        val user = result.user ?: error("Google sign-in failed")
        awaitFirestoreAuth(forceRefresh = true)
        val profile = ensureUserProfile(user.uid, user.displayName, user.photoUrl?.toString())
        runCatching { awaitCallableAuth() }
        return profile
    }

    /** Firebase auth only — profile sync can finish after the UI navigates away. */
    suspend fun beginAnonymousSignIn(): FirebaseUser = withContext(Dispatchers.IO) {
        withTimeoutOrNull(2_000) { discardIncompleteGuestProfile() }
        try {
            val result = withTimeout(30_000) {
                auth.signInAnonymously().awaitTask()
            }
            result.user ?: error("Guest sign-in failed")
        } catch (e: TimeoutCancellationException) {
            // Auth may have completed even though the coroutine timed out waiting.
            auth.currentUser ?: throw e
        }
    }

    suspend fun signInAnonymously(): UserProfile {
        val user = beginAnonymousSignIn()
        return ensureUserProfile(
            uid = user.uid,
            displayName = DisplayNames.guestName(user.uid),
            photoUrl = null,
        )
    }

    fun signOutBestEffort() {
        runCatching { auth.signOut() }
    }

    suspend fun signInWithEmail(email: String, password: String): UserProfile {
        val result = auth.signInWithEmailAndPassword(email.trim(), password).await()
        val user = result.user ?: error("Email sign-in failed")
        val name = user.displayName?.takeIf { it.isNotBlank() }
            ?: email.substringBefore('@').ifBlank { DisplayNames.DEFAULT }
        return ensureUserProfile(user.uid, name, user.photoUrl?.toString())
    }

    suspend fun registerWithEmail(
        email: String,
        password: String,
        displayName: String,
    ): UserProfile {
        val result = auth.createUserWithEmailAndPassword(email.trim(), password).await()
        val user = result.user ?: error("Registration failed")
        val name = displayName.trim().ifBlank { email.substringBefore('@') }
        user.updateProfile(
            UserProfileChangeRequest.Builder().setDisplayName(name).build(),
        ).await()
        return ensureUserProfile(user.uid, name, photoUrl = null)
    }

    /**
     * Returns once [users/{uid}] has the fields required for matchmaking, or false on timeout.
     */
    suspend fun waitUntilQueueReadyProfile(
        uid: String,
        timeoutMs: Long = QUEUE_PROFILE_WAIT_MS,
    ): Boolean {
        val docRef = firestore.collection("users").document(uid)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            awaitFirestoreAuth()
            val snap = docRef.getServerSnapshotOrNull(timeoutMs = 3_000)
                ?: docRef.getCacheSnapshotOrNull(timeoutMs = 500)
            if (snap != null && snap.isQueueReadyProfile()) return true
            delay(QUEUE_PROFILE_POLL_MS)
        }
        return false
    }

    suspend fun ensureUserProfile(
        uid: String,
        displayName: String?,
        photoUrl: String?,
    ): UserProfile = UserProfileSync.withProfileLock {
        queueReadyProfile(uid)?.let { return@withProfileLock it }
        val profile = ensureUserProfileLocked(uid, displayName, photoUrl)
        verifyAndRememberQueueReady(uid, profile)
    }

    /** Profile known good for queue writes (from a recent [ensureUserProfile]). */
    fun queueReadyProfile(uid: String): UserProfile? = UserProfileSync.queueReadyProfile(uid)

    private suspend fun ensureUserProfileLocked(
        uid: String,
        displayName: String?,
        photoUrl: String?,
    ): UserProfile {
        awaitFirestoreAuth()
        val docRef = firestore.collection("users").document(uid)
        val snapshot = loadUserSnapshot(docRef)
        if (snapshot != null && snapshot.exists()) {
            if (!snapshot.isCompleteUserProfile()) {
                if (snapshot.hasMinimumQueueProfile()) {
                    val deleted = runCatching {
                        withTimeout(5_000) { docRef.delete().await() }
                    }.isSuccess
                    if (!deleted) {
                        val legacy = normalizeStoredProfile(
                            docRef,
                            uid,
                            snapshot.getString("displayName"),
                            snapshot.toUserProfile(uid),
                        )
                        return verifyAndRememberQueueReady(uid, legacy)
                    }
                } else {
                    val deleted = runCatching {
                        withTimeout(5_000) { docRef.delete().await() }
                    }.isSuccess
                    if (!deleted) {
                        val stillThere = loadUserSnapshot(docRef)?.exists() == true
                        if (stillThere) {
                            error(
                                "Your saved profile could not be updated. Sign out, try again, or contact support.",
                            )
                        }
                    }
                }
            } else {
                val existing = normalizeStoredProfile(
                    docRef,
                    uid,
                    snapshot.getString("displayName"),
                    snapshot.toUserProfile(uid),
                )
                return verifyAndRememberQueueReady(uid, existing)
            }
        }

        val defaultName = when {
            auth.currentUser?.isAnonymous == true -> DisplayNames.guestName(uid)
            else -> displayName ?: DisplayNames.DEFAULT
        }
        val profile = UserProfile(
            uid = uid,
            displayName = defaultName,
            photoUrl = photoUrl,
        )
        val now = Timestamp.now()
        withTimeout(15_000) {
            docRef.set(
                buildMap {
                    put("displayName", profile.displayName)
                    profile.photoUrl?.let { put("photoUrl", it) }
                    put("elo", profile.elo)
                    put("wins", profile.wins)
                    put("losses", profile.losses)
                    put("draws", profile.draws)
                    put("roundsWon", profile.roundsWon)
                    put("roundsLost", profile.roundsLost)
                    put("roundsDraw", profile.roundsDraw)
                    put("moveTimeMs", profile.moveTimeMs)
                    put("moveCount", profile.moveCount)
                    put("throwsRock", profile.throwsRock)
                    put("throwsPaper", profile.throwsPaper)
                    put("throwsScissors", profile.throwsScissors)
                    put("isGuest", auth.currentUser?.isAnonymous == true)
                    put("leaderboardVisible", false)
                    put("createdAt", now)
                    put("lastSeen", now)
                },
            ).await()
        }
        return verifyAndRememberQueueReady(uid, profile)
    }

    /** Loads users/{uid} from the server (required before queue writes — Cloud Functions read server data). */
    suspend fun fetchServerProfile(uid: String): UserProfile? {
        awaitFirestoreAuth()
        val snap = firestore.collection("users").document(uid).getServerSnapshotOrNull(4_000)
        if (snap == null || !snap.isQueueReadyProfile()) return null
        return snap.toUserProfile(uid)
    }

    private suspend fun verifyAndRememberQueueReady(uid: String, profile: UserProfile): UserProfile {
        val docRef = firestore.collection("users").document(uid)
        docRef.getCacheSnapshotOrNull(500)?.takeIf { it.isQueueReadyProfile() }?.let { cached ->
            val synced = cached.toUserProfile(uid)
            UserProfileSync.rememberQueueReady(uid, synced)
            return synced
        }
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            val snap = docRef.getServerSnapshotOrNull(3_000)
                ?: docRef.getCacheSnapshotOrNull(500)
            if (snap != null && snap.isQueueReadyProfile()) {
                val synced = snap.toUserProfile(uid)
                UserProfileSync.rememberQueueReady(uid, synced)
                return synced
            }
            delay(300)
        }
        UserProfileSync.rememberQueueReady(uid, profile)
        return profile
    }

    suspend fun loadCurrentUserProfile(): UserProfile? = runCatching {
        awaitFirestoreAuth()
        val uid = currentUserId ?: return@runCatching null
        val docRef = firestore.collection("users").document(uid)
        val snapshot = loadUserSnapshot(docRef) ?: return@runCatching null
        if (!snapshot.exists() || !snapshot.isCompleteUserProfile()) return@runCatching null
        normalizeStoredProfile(docRef, uid, snapshot.getString("displayName"), snapshot.toUserProfile(uid))
    }.getOrNull()

    fun fallbackProfile(user: FirebaseUser): UserProfile {
        val uid = user.uid
        val name = user.displayName?.takeIf { it.isNotBlank() }
            ?: if (user.isAnonymous) DisplayNames.guestName(uid) else DisplayNames.DEFAULT
        return UserProfile(uid = uid, displayName = name, photoUrl = user.photoUrl?.toString())
    }

    suspend fun isFirebaseAvailable(): Boolean {
        runCatching { appFirestore().enableNetwork().await() }
        return probeFirestoreReachability() || probeFirestoreReachabilityFromCache()
    }

    /** Server-only reachability for connection indicators (ignores local cache). */
    suspend fun isFirebaseServerReachable(): Boolean {
        runCatching { appFirestore().enableNetwork().await() }
        return probeFirestoreServerOutcome().isReachable
    }

    /** Server-only reachability probe with failure classification for connection UI. */
    suspend fun probeFirestoreServerOutcome(): FirestoreProbeOutcome {
        runCatching { appFirestore().enableNetwork().await() }
        return probeFirestoreServerReachability()
    }

    /**
     * Unauthenticated clients cannot read [users], but a rules rejection still proves Firestore
     * is reachable. Avoid [Source.SERVER] here — it blocks for a long time when offline.
     */
    private suspend fun probeFirestoreReachability(): Boolean {
        return try {
            withTimeoutOrNull(10_000) {
                firestore.collection("users").limit(1).get().await()
                true
            } ?: false
        } catch (e: FirebaseFirestoreException) {
            interpretUsersProbeError(e)
        } catch (_: TimeoutCancellationException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun probeFirestoreServerReachability(): FirestoreProbeOutcome {
        return try {
            val completed = withTimeoutOrNull(5_000) {
                firestore.collection("users").limit(1).get(Source.SERVER).await()
                true
            }
            if (completed == true) FirestoreProbeOutcome.Reachable else FirestoreProbeOutcome.UnreachableTimeout
        } catch (e: FirebaseFirestoreException) {
            if (interpretUsersProbeError(e)) FirestoreProbeOutcome.Reachable else classifyFirestoreProbeFailure(e)
        } catch (e: TimeoutCancellationException) {
            FirestoreProbeOutcome.UnreachableTimeout
        } catch (e: Exception) {
            classifyFirestoreProbeFailure(e)
        }
    }

    private suspend fun probeFirestoreReachabilityFromCache(): Boolean {
        return try {
            withTimeoutOrNull(2_000) {
                firestore.collection("users").limit(1).get(Source.CACHE).await()
                true
            } ?: false
        } catch (e: FirebaseFirestoreException) {
            interpretUsersProbeError(e)
        } catch (_: Exception) {
            false
        }
    }

    private fun interpretUsersProbeError(error: FirebaseFirestoreException): Boolean =
        when (error.code) {
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            FirebaseFirestoreException.Code.UNAUTHENTICATED,
            -> true
            FirebaseFirestoreException.Code.UNAVAILABLE,
            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
            -> false
            else -> true
        }

    private suspend fun loadUserSnapshot(
        docRef: com.google.firebase.firestore.DocumentReference,
    ): com.google.firebase.firestore.DocumentSnapshot? {
        val cached = docRef.getCacheSnapshotOrNull()
        if (cached?.exists() == true) return cached
        return docRef.getServerSnapshotOrNull() ?: cached
    }

    /** Drops a broken guest Firestore doc without signing out (sign-out resets navigation). */
    private suspend fun discardIncompleteGuestProfile() {
        val uid = currentUserId ?: return
        if (auth.currentUser?.isAnonymous != true) return
        withTimeoutOrNull(3_000) {
            runCatching {
                awaitFirestoreAuth()
                val docRef = firestore.collection("users").document(uid)
                val cached = docRef.getCacheSnapshotOrNull(timeoutMs = 1_000)
                if (cached?.exists() == true && !cached.isCompleteUserProfile()) {
                    docRef.delete().await()
                    return@runCatching
                }
                val remote = docRef.getServerSnapshotOrNull(timeoutMs = 2_000)
                if (remote?.exists() == true && !remote.isCompleteUserProfile()) {
                    docRef.delete().await()
                }
            }
        }
    }

    private suspend fun normalizeStoredProfile(
        docRef: com.google.firebase.firestore.DocumentReference,
        uid: String,
        storedName: String?,
        profile: UserProfile,
    ): UserProfile {
        val resolved = DisplayNames.resolve(storedName, uid)
        if (auth.currentUser?.isAnonymous == true && DisplayNames.isGeneric(storedName)) {
            docRef.updateBestEffort(mapOf("displayName" to resolved))
            return profile.copy(displayName = resolved)
        }
        return if (resolved != profile.displayName) profile.copy(displayName = resolved) else profile
    }

    suspend fun signOut(context: Context) {
        UserProfileSync.clear()
        auth.signOut()
        withTimeoutOrNull(2_000) {
            try {
                CredentialManager.create(context).clearCredentialState(
                    ClearCredentialStateRequest(),
                )
            } catch (_: Exception) {
                // Google credential cache may already be clear.
            }
        }
    }
}
