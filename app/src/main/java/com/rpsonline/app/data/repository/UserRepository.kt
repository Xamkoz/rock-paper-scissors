package com.rpsonline.app.data.repository



import com.google.firebase.firestore.DocumentSnapshot

import com.google.firebase.firestore.FirebaseFirestore

import com.google.firebase.firestore.Query

import com.google.firebase.firestore.Source

import com.rpsonline.app.data.model.LeaderboardEntry

import com.rpsonline.app.data.model.UserProfile

import com.rpsonline.app.domain.DisplayNames

import kotlinx.coroutines.channels.awaitClose

import kotlinx.coroutines.flow.Flow

import kotlinx.coroutines.flow.callbackFlow

import kotlinx.coroutines.tasks.await



class UserRepository(

    private val firestore: FirebaseFirestore = appFirestore(),

) {

    internal data class LeaderboardPage(

        val entries: List<LeaderboardEntry>,

        val nextCursor: DocumentSnapshot?,

        val hasMoreFromFirestore: Boolean,

    ) {

        val hasMore: Boolean

            get() = hasMoreFromFirestore

    }



    suspend fun getUserProfile(uid: String): UserProfile? {

        val ref = firestore.collection("users").document(uid)

        val snapshot = runCatching { ref.get(Source.SERVER).await() }.getOrNull()

            ?: runCatching { ref.get(Source.CACHE).await() }.getOrNull()

            ?: return null

        if (!snapshot.exists()) return null

        return snapshot.toUserProfile(uid)

    }



    /** Live updates when Cloud Functions increment throw counts, ELO, etc. */

    fun observeUserProfile(uid: String): Flow<UserProfile?> = callbackFlow {

        val listener = firestore.collection("users").document(uid)

            .addSnapshotListener { snapshot, error ->

                if (error != null || snapshot == null || !snapshot.exists()) {

                    trySend(null)

                    return@addSnapshotListener

                }

                trySend(snapshot.toUserProfile(uid))

            }

        awaitClose { listener.remove() }

    }



    suspend fun getLeaderboard(limit: Long = 50): List<LeaderboardEntry> =

        getLeaderboardPage(pageSize = limit).entries



    /**

     * Fetches a page of ranked non-guest players ordered by `elo` descending.

     *

     * Requires `leaderboardVisible == true` on user docs (set server-side after a match).

     */

    internal suspend fun getLeaderboardPageFromCache(pageSize: Long): LeaderboardPage? {
        require(pageSize > 0) { "pageSize must be > 0" }
        val snapshot = runCatching {
            firestore.collection("users")
                .whereEqualTo("leaderboardVisible", true)
                .orderBy("elo", Query.Direction.DESCENDING)
                .limit(pageSize)
                .get(Source.CACHE)
                .await()
        }.getOrNull() ?: return null
        if (snapshot.documents.isEmpty()) return null
        val docs = snapshot.documents
        return LeaderboardPage(
            entries = docs.map { it.toLeaderboardEntry() },
            nextCursor = docs.lastOrNull(),
            hasMoreFromFirestore = docs.size.toLong() == pageSize,
        )
    }

    internal suspend fun getLeaderboardPage(

        pageSize: Long,

        startAfter: DocumentSnapshot? = null,

    ): LeaderboardPage {

        require(pageSize > 0) { "pageSize must be > 0" }



        var query = firestore.collection("users")

            .whereEqualTo("leaderboardVisible", true)

            .orderBy("elo", Query.Direction.DESCENDING)

        if (startAfter != null) query = query.startAfter(startAfter)



        val snapshot = query.limit(pageSize).get().await()

        val docs = snapshot.documents

        val entries = docs.map { doc -> doc.toLeaderboardEntry() }



        return LeaderboardPage(

            entries = entries,

            nextCursor = docs.lastOrNull(),

            hasMoreFromFirestore = docs.size.toLong() == pageSize,

        )

    }



    private fun DocumentSnapshot.toLeaderboardEntry(): LeaderboardEntry =

        LeaderboardEntry(

            uid = id,

            displayName = DisplayNames.resolve(getString("displayName"), id),

            elo = getIntField("elo") ?: 1000,

            wins = getIntField("wins") ?: 0,

            losses = getIntField("losses") ?: 0,

            draws = getIntField("draws") ?: 0,

            roundsWon = getIntField("roundsWon") ?: 0,

            roundsLost = getIntField("roundsLost") ?: 0,

            roundsDraw = getIntField("roundsDraw") ?: 0,

            moveTimeMs = getLong("moveTimeMs") ?: 0L,

            moveCount = getIntField("moveCount") ?: 0,

            throwsRock = getIntField("throwsRock") ?: 0,

            throwsPaper = getIntField("throwsPaper") ?: 0,

            throwsScissors = getIntField("throwsScissors") ?: 0,

        )

}


