package com.riffle.core.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException

/**
 * Reads release metadata and APK assets from a public GitHub repository's Releases API. No auth is
 * needed because the Riffle repo is public.
 */
class GitHubReleaseApi(
    private val httpClient: HttpClient,
    /** Overridable so tests can point at a local MockWebServer; defaults to the public GitHub API. */
    private val apiBaseUrl: String = "https://api.github.com",
) {

    /**
     * Fetches the repo's most recent non-prerelease, non-draft release whose assets include an APK.
     * Releases that exist but haven't finished their APK build yet are skipped so an in-flight release
     * doesn't stall the updater.
     */
    suspend fun latestRelease(repo: String): GitHubReleaseResult {
        // no-cache on the request: the only caller is the manual Settings "Check for updates"
        // button whose contract is "check now". GitHub's response advertises `Cache-Control:
        // max-age=60`; without this override a re-tap within 60s would serve the previous cached
        // response and the button would silently no-op. This forces every tap through to the origin.
        return try {
            val response = httpClient.get("$apiBaseUrl/repos/$repo/releases?per_page=10") {
                header(HttpHeaders.Accept, "application/vnd.github+json")
                header(HttpHeaders.CacheControl, "no-cache, no-store")
            }
            if (!response.status.isSuccess()) {
                return GitHubReleaseResult.Failed("HTTP ${response.status.value}")
            }
            val parsed = response.body<List<ReleaseResponse>>()
            for (release in parsed) {
                if (release.draft || release.prerelease) continue
                val apk = release.assets.firstOrNull {
                    it.name.endsWith(".apk", ignoreCase = true)
                } ?: continue
                return GitHubReleaseResult.Success(
                    GitHubRelease(
                        tagName = release.tagName,
                        apkUrl = apk.downloadUrl,
                        apkSizeBytes = apk.size,
                    )
                )
            }
            GitHubReleaseResult.Failed("No release with an APK asset")
        } catch (e: IOException) {
            GitHubReleaseResult.Failed(e.message ?: "Network error")
        }
    }

    /**
     * Streams [url] into [dest], reporting whole-percent progress. Returns true on success. On any
     * failure [dest] is deleted, so a truncated APK is never handed to the installer.
     */
    suspend fun download(url: String, dest: File, onProgress: (percent: Int) -> Unit): Boolean =
        try {
            val response = httpClient.get(url)
            if (!response.status.isSuccess()) {
                dest.delete()
                return false
            }
            val total = response.contentLength() ?: -1L
            val channel = response.bodyAsChannel()
            dest.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                var lastPercent = -1
                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    copied += read
                    if (total > 0) {
                        val percent = ((copied * 100) / total).toInt().coerceIn(0, 100)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                }
            }
            true
        } catch (e: IOException) {
            dest.delete()
            false
        }
}

sealed interface GitHubReleaseResult {
    data class Success(val release: GitHubRelease) : GitHubReleaseResult
    data class Failed(val message: String) : GitHubReleaseResult
}

data class GitHubRelease(
    val tagName: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
)

@Serializable
private data class ReleaseResponse(
    @SerialName("tag_name") val tagName: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<AssetResponse> = emptyList(),
)

@Serializable
private data class AssetResponse(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long = 0,
)
