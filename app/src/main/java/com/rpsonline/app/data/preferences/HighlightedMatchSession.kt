package com.rpsonline.app.data.preferences

/** In-process only; cleared on cold start and sign-out. */
object HighlightedMatchSession {
    @Volatile
    var dismissed: Boolean = false
        private set

    fun dismiss() {
        dismissed = true
    }

    fun clear() {
        dismissed = false
    }
}
