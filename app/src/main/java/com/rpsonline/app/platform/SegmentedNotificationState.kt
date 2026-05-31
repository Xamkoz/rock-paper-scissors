package com.rpsonline.app.platform

/** Latest online count for segmented notification rendering. */
object SegmentedNotificationState {
    @Volatile
    var onlineCount: Int? = null
        private set

    @Volatile
    private var onContentChanged: (() -> Unit)? = null

    fun setOnContentChangedListener(listener: (() -> Unit)?) {
        onContentChanged = listener
    }

    fun setOnlineCount(count: Int?) {
        if (onlineCount == count) return
        onlineCount = count
        onContentChanged?.invoke()
    }
}
