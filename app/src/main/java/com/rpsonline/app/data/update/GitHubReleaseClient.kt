package com.rpsonline.app.data.update

import com.rpsonline.app.domain.versionCodeFromTag
import com.rpsonline.app.domain.versionLabelFromTag
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

class GitHubReleaseClient(
    private val owner: String,
    private val repo: String,
) {
    fun fetchReleaseNotesForVersion(versionName: String): String? {
        val tag = ReleaseChangelog.tagForInstalledVersion(versionName)
        var connection: HttpURLConnection? = null
        return try {
            connection = openGetConnection("https://api.github.com/repos/$owner/$repo/releases/tags/$tag")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return null
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val releaseBody = JSONObject(body).optString("body").takeIf { it.isNotBlank() }
                ?: return null
            ReleaseChangelog.simplifyMarkdownForDisplay(releaseBody)
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    fun fetchReleasesPage(page: Int, perPage: Int = RELEASES_PAGE_SIZE): ReleaseChangelogPage {
        var connection: HttpURLConnection? = null
        return try {
            connection = openGetConnection(
                "https://api.github.com/repos/$owner/$repo/releases?page=$page&per_page=$perPage",
            )
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return ReleaseChangelogPage(entries = emptyList(), hasMore = false)
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val entries = parseReleaseList(body)
            ReleaseChangelogPage(
                entries = entries,
                hasMore = hasNextPage(connection),
            )
        } catch (_: Exception) {
            ReleaseChangelogPage(entries = emptyList(), hasMore = false)
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseReleaseList(json: String): List<ReleaseChangelogEntry> {
        val releases = JSONArray(json)
        return buildList {
            for (i in 0 until releases.length()) {
                val release = releases.optJSONObject(i) ?: continue
                if (release.optBoolean("draft")) continue
                val tag = release.optString("tag_name").takeIf { it.isNotBlank() } ?: continue
                val publishedDay = release.optString("published_at")
                    .let(ReleaseChangelog::publishedDayFromIso)
                    ?: LocalDate.EPOCH
                val rawBody = release.optString("body")
                val notes = rawBody.takeIf { it.isNotBlank() }
                    ?.let(ReleaseChangelog::simplifyMarkdownForDisplay)
                    ?: ReleaseChangelog.NO_RELEASE_NOTES
                add(
                    ReleaseChangelogEntry(
                        tag = tag,
                        versionLabel = versionLabelFromTag(tag),
                        notes = notes,
                        publishedDay = publishedDay,
                    ),
                )
            }
        }
    }

    private fun openGetConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "RpsOnline-Android")
        }

    private fun hasNextPage(connection: HttpURLConnection): Boolean {
        val linkHeader = connection.getHeaderField("Link") ?: return false
        return NEXT_PAGE_LINK_REGEX.containsMatchIn(linkHeader)
    }

    fun fetchLatestRelease(installedVersionName: String): AppUpdateInfo? {
        var connection: HttpURLConnection? = null
        return try {
            connection = openGetConnection("https://api.github.com/repos/$owner/$repo/releases?per_page=$RELEASES_PAGE_SIZE")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return null
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseLatestInstallableRelease(body, installedVersionName)
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseLatestInstallableRelease(json: String, installedVersionName: String): AppUpdateInfo? {
        return try {
            val releases = JSONArray(json)
            for (i in 0 until releases.length()) {
                val root = releases.optJSONObject(i) ?: continue
                if (root.optBoolean("draft")) continue

                val tag = root.optString("tag_name")
                if (tag.isBlank()) continue

                val assets = root.optJSONArray("assets") ?: continue
                var apkUrl: String? = null
                for (j in 0 until assets.length()) {
                    val asset = assets.optJSONObject(j) ?: continue
                    val name = asset.optString("name")
                    if (name.endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url")
                        if (!apkUrl.isNullOrBlank()) break
                    }
                }
                val url = apkUrl?.takeIf { it.isNotBlank() } ?: continue

                val releaseBody = root.optString("body").takeIf { it.isNotBlank() }
                val (baseTag, headTag) = releaseBody?.let { ReleaseChangelog.parseCompareRangeFromReleaseBody(it) }
                    ?: (ReleaseChangelog.tagForInstalledVersion(installedVersionName) to tag)
                val compareSection = if (baseTag != headTag) {
                    fetchCompareChangelog(baseTag, headTag)
                } else {
                    null
                }
                val changelog = ReleaseChangelog.buildDisplayText(releaseBody, compareSection)

                return AppUpdateInfo(
                    tag = tag,
                    versionCode = versionCodeFromTag(tag),
                    versionLabel = versionLabelFromTag(tag),
                    apkUrl = url,
                    releaseNotes = changelog,
                )
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchCompareChangelog(baseTag: String, headTag: String): String? {
        if (baseTag.isBlank() || headTag.isBlank() || baseTag == headTag) return null
        var connection: HttpURLConnection? = null
        return try {
            val compareRef = "$baseTag...$headTag"
            connection = openGetConnection(
                "https://api.github.com/repos/$owner/$repo/compare/$compareRef",
            )
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return null
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val commits = JSONObject(body).optJSONArray("commits") ?: return null
            ReleaseChangelog.formatCompareCommits(commits).takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        const val RELEASES_PAGE_SIZE = 100
        private val NEXT_PAGE_LINK_REGEX = Regex("""<[^>]+>;\s*rel="next"""")
    }
}
