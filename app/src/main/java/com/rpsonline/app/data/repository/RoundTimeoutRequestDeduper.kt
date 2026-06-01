package com.rpsonline.app.data.repository

/** One timeout-request write per match round per device — retries only poll the server. */
internal object RoundTimeoutRequestDeduper {
    private val sentKeys = mutableSetOf<String>()

    fun wasSent(matchId: String, roundNumber: Int): Boolean =
        sentKeys.contains(key(matchId, roundNumber))

    fun markSent(matchId: String, roundNumber: Int) {
        sentKeys.add(key(matchId, roundNumber))
    }

    fun clearSent(matchId: String, roundNumber: Int) {
        sentKeys.remove(key(matchId, roundNumber))
    }

    private fun key(matchId: String, roundNumber: Int): String = "$matchId:$roundNumber"
}
