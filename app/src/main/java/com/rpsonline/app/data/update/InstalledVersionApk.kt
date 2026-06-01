package com.rpsonline.app.data.update

import com.rpsonline.app.BuildConfig

object InstalledVersionApk {
    fun tagFor(versionName: String): String =
        ReleaseChangelog.tagForInstalledVersion(versionName.trim())

    fun downloadUrl(versionName: String): String? {
        val tag = tagFor(versionName)
        if (tag.isBlank()) return null
        return "https://github.com/${BuildConfig.GITHUB_REPO_OWNER}/${BuildConfig.GITHUB_REPO_NAME}/releases/download/$tag/rps-online-$tag.apk"
    }
}
