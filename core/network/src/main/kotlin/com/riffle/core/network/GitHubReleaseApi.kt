package com.riffle.core.network

import com.riffle.core.domain.DispatcherProvider
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Reads release metadata and APK assets from a public GitHub repository's Releases API. No auth is
 * needed because the Riffle repo is public.
 */
class GitHubReleaseApi(
    private val httpClient: OkHttpClient,
    private val dispatchers: DispatcherProvider,
    /** Overridable so tests can point at a local MockWebServer; defaults to the public GitHub API. */
    private val apiBaseUrl: String = "https://api.github.com",
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Fetches the repo's most recent non-prerelease, non-draft release whose assets include an APK.
     * Releases that exist but haven't finished their APK build yet are skipped so an in-flight release
     * doesn't stall the updater.
     */
    suspend fun latestRelease(repo: String): GitHubReleaseResult = withContext(dispatchers.io) {
        // FORCE_NETWORK on the request: the only caller is the manual Settings "Check for updates"
        // button whose contract is "check now". GitHub's response advertises `Cache-Control:
        // max-age=60`, which the shared default OkHttp Cache honors — without this override a
        // re-tap within 60s would serve the previous response from disk and the button would
        // silently no-op. This forces every tap through to the origin.
        val request = Request.Builder()
            .url("$apiBaseUrl/repos/$repo/releases?per_page=10")
            .header("Accept", "application/vnd.github+json")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .get()
            .build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use GitHubReleaseResult.Failed("HTTP ${response.code}")
                }
                val raw = response.body?.string()
                    ?: return@use GitHubReleaseResult.Failed("Empty response")
                val parsed = json.decodeFromString<List<ReleaseResponse>>(raw)
                for (release in parsed) {
                    if (release.draft || release.prerelease) continue
                    val apk = release.assets.firstOrNull {
                        it.name.endsWith(".apk", ignoreCase = true)
                    } ?: continue
                    return@use GitHubReleaseResult.Success(
                        GitHubRelease(
                            tagName = release.tagName,
                            apkUrl = apk.downloadUrl,
                            apkSizeBytes = apk.size,
                        )
                    )
                }
                GitHubReleaseResult.Failed("No release with an APK asset")
            }
        } catch (e: IOException) {
            GitHubReleaseResult.Failed(e.message ?: "Network error")
        }
    }

    /**
     * Streams [url] into [dest], reporting whole-percent progress. Returns true on success. On any
     * failure [dest] is deleted, so a truncated APK is never handed to the installer.
     */
    suspend fun download(url: String, dest: File, onProgress: (percent: Int) -> Unit): Boolean =
        withContext(dispatchers.io) {
            val request = Request.Builder().url(url).get().build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body
                    if (!response.isSuccessful || body == null) {
                        dest.delete()
                        return@use false
                    }
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        dest.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var copied = 0L
                            var lastPercent = -1
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
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
                    }
                    true
                }
            } catch (e: IOException) {
                dest.delete()
                false
            }
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
