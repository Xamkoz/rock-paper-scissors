package com.rpsonline.app.data.repository

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.rpsonline.app.data.model.Match
import com.rpsonline.app.data.model.MatchStatus
import com.rpsonline.app.data.model.Move
import com.rpsonline.app.data.model.RoundResult
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class MatchRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    private val uid: String
        get() = auth.currentUser?.uid ?: error("Not signed in")

    /**
     * Join matchmaking via Firestore (not Callable). Writes queue/{uid}; Cloud Function pairs players.
     *
     * Client-only constraints (no Firebase deploy):
     * - Delete before create so onDocumentCreated always fires (updates do not trigger it).
     * - Use client [Timestamp.now] for joinedAt so the server's orderBy query includes the doc immediately.
     */
    suspend fun joinQueue(): String? {
        val userId = uid
        val userSnap = firestore.collection("users").document(userId).get().await()
        if (!userSnap.exists()) {
            throw IllegalStateException("User profile missing. Sign out and sign in again.")
        }

        val activeMatchId = userSnap.getString("activeMatchId")
        if (!activeMatchId.isNullOrBlank()) {
            val match = getMatch(activeMatchId)
            if (match?.status == MatchStatus.ACTIVE) {
                return activeMatchId
            }
        }

        writeQueueEntry(userId, userSnap)
        return null
    }

    /** Re-queues while searching so onDocumentCreated fires again without a Firebase deploy. */
    suspend fun refreshQueueEntry() {
        val userId = uid
        val userSnap = firestore.collection("users").document(userId).get().await()
        if (!userSnap.exists()) return
        writeQueueEntry(userId, userSnap)
    }

    private suspend fun writeQueueEntry(
        userId: String,
        userSnap: com.google.firebase.firestore.DocumentSnapshot,
    ) {
        val elo = userSnap.getLong("elo")?.toInt() ?: 1000
        val displayName = userSnap.getString("displayName") ?: "Player"
        val queueRef = firestore.collection("queue").document(userId)

        // onDocumentCreated only fires for new docs; delete first so re-queues trigger matchmaking.
        runCatching { queueRef.delete().await() }

        queueRef.set(
            mapOf(
                // Client timestamp: deployed Cloud Function uses orderBy("joinedAt"), which skips
                // documents while serverTimestamp() is still pending.
                "joinedAt" to Timestamp.now(),
                "elo" to elo,
                "displayName" to displayName,
            ),
        ).await()
    }

    suspend fun leaveQueue() {
        firestore.collection("queue").document(uid).delete().await()
    }

    suspend fun requestRoundTimeout(matchId: String, roundNumber: Int) {
        // Auto-id doc: rules only allow create (not update). Reusing uid doc breaks tie replays.
        firestore.collection("matches")
            .document(matchId)
            .collection("rounds")
            .document(roundNumber.toString())
            .collection("timeoutRequests")
            .add(
                mapOf(
                    "userId" to uid,
                    "requestedAt" to FieldValue.serverTimestamp(),
                ),
            )
            .await()
    }

    suspend fun submitMove(matchId: String, move: Move, roundNumber: Int) {
        firestore.collection("matches")
            .document(matchId)
            .collection("rounds")
            .document(roundNumber.toString())
            .collection("choices")
            .document(uid)
            .set(
                mapOf(
                    "choice" to move.name,
                    "submittedAt" to FieldValue.serverTimestamp(),
                ),
            )
            .await()
    }

    fun observeMatch(matchId: String): Flow<Match?> = callbackFlow {
        val listener = firestore.collection("matches")
            .document(matchId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(null)
                    return@addSnapshotListener
                }
                trySend(snapshot?.toMatch(matchId))
            }
        awaitClose { listener.remove() }
    }

    fun observeActiveMatch(): Flow<Match?> = callbackFlow {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            trySend(null)
            close()
            return@callbackFlow
        }

        var matchListener: com.google.firebase.firestore.ListenerRegistration? = null

        val userListener = firestore.collection("users").document(userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(null)
                    return@addSnapshotListener
                }

                matchListener?.remove()
                val matchId = snapshot?.getString("activeMatchId")
                if (matchId.isNullOrBlank()) {
                    trySend(null)
                    return@addSnapshotListener
                }

                matchListener = firestore.collection("matches").document(matchId)
                    .addSnapshotListener { matchSnapshot, matchError ->
                        if (matchError != null) {
                            trySend(null)
                            return@addSnapshotListener
                        }
                        trySend(matchSnapshot?.toMatch(matchId))
                    }
            }

        awaitClose {
            userListener.remove()
            matchListener?.remove()
        }
    }

    suspend fun getMatch(matchId: String): Match? {
        val snapshot = firestore.collection("matches").document(matchId).get().await()
        return if (snapshot.exists()) snapshot.toMatch(matchId) else null
    }
}

@Suppress("UNCHECKED_CAST")
private fun DocumentSnapshot.toMatch(id: String): Match {
    val roundsData = get("rounds") as? List<Map<String, Any?>> ?: emptyList()
    val rounds = roundsData.map { map ->
        RoundResult(
            roundNumber = (map["roundNumber"] as? Number)?.toInt() ?: 0,
            player1Choice = map["player1Choice"] as? String,
            player2Choice = map["player2Choice"] as? String,
            winner = map["winner"] as? String,
            resolvedAt = (map["resolvedAt"] as? Timestamp)?.toDate()?.time,
            deadline = (map["deadline"] as? Timestamp)?.toDate()?.time,
        )
    }

    return Match(
        id = id,
        player1 = getString("player1") ?: "",
        player2 = getString("player2") ?: "",
        player1Name = getString("player1Name") ?: "Player 1",
        player2Name = getString("player2Name") ?: "Player 2",
        status = MatchStatus.fromString(getString("status")),
        currentRound = getLong("currentRound")?.toInt() ?: 1,
        player1Wins = getLong("player1Wins")?.toInt() ?: 0,
        player2Wins = getLong("player2Wins")?.toInt() ?: 0,
        rounds = rounds,
        winnerId = getString("winnerId"),
        player1EloDelta = getLong("player1EloDelta")?.toInt(),
        player2EloDelta = getLong("player2EloDelta")?.toInt(),
        createdAt = getTimestamp("createdAt")?.toDate()?.time ?: 0L,
        lastActivityAt = getTimestamp("lastActivityAt")?.toDate()?.time ?: 0L,
    )
}
