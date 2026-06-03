package com.rpsonline.app.platform

import com.rpsonline.app.R

/** Shared status-bar identity so foreground and match-found alerts do not reorder as different icons. */
internal object RpsStatusBarNotification {
    const val SESSION_GROUP_KEY = "com.rpsonline.app.session"
    val smallIconRes: Int = R.drawable.ic_stat_rps_session
}
