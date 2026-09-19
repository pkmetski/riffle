package com.riffle.core.data

import com.riffle.core.domain.AppUpdateRepository
import com.riffle.core.domain.AppUpdateVersions
import com.riffle.core.domain.AvailableUpdate
import com.riffle.core.domain.ReleaseCandidate
import com.riffle.core.domain.ReleaseInfo
import com.riffle.core.domain.UpdateCheckResult
import com.riffle.core.domain.UpdateDownloadState
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val GITHUB_REPO = "pkmetski/riffle"
private const val GITHUB_API_BASE = "https://api.github.com"

/**
 * iOS [AppUpdateRepository].
 *
 * [checkForUpdate] and [listReleasesSince] are real: they query the same GitHub releases the
 * Android updater does and compare tags through the shared [AppUpdateVersions], so both platforms
 * agree on what counts as newer. The Settings screen can therefore tell an iOS user that a newer
 * Riffle exists — which the previous stub, hardcoded to UpToDate, never could.
 *
 * [downloadAndInstall] returns an empty flow and [sweepStaleApks] does nothing. That is the
 * interface's documented behaviour for platforms without self-update, not a stub: iOS forbids an
 * app installing another build of itself, so there is no APK to fetch or sweep. Distribution there
 * is TestFlight/App Store.
 */
class IosAppUpdateRepositoryImpl(
    private val httpClient: HttpClient,
    private val apiBaseUrl: String = GITHUB_API_BASE,
    private val repo: String = GITHUB_REPO,
) : AppUpdateRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun checkForUpdate(currentVersionCode: Int): UpdateCheckResult {
        val releases = fetchReleases()
            ?: return UpdateCheckResult.Failed("Could not reach GitHub to check for updates")
        // Newest usable release first, matching Android's latestRelease(): skip drafts and
        // prereleases, and pick the highest version tag we can parse.
        val latest = releases.maxByOrNull { AppUpdateVersions.versionCodeOf(it.tagName.removePrefix("v")) ?: -1 }
            ?: return UpdateCheckResult.Failed("No published releases found")
        return AppUpdateVersions.evaluate(currentVersionCode, latest)
    }

    override suspend fun listReleasesSince(sinceVersionCode: Int): List<ReleaseInfo> =
        AppUpdateVersions.listReleasesSince(fetchReleases().orEmpty(), sinceVersionCode)

    /** iOS cannot install another build of itself; see the class doc. */
    override fun downloadAndInstall(update: AvailableUpdate): Flow<UpdateDownloadState> = emptyFlow()

    /** Nothing is ever downloaded on iOS, so there is nothing to sweep. */
    override fun sweepStaleApks() = Unit

    private suspend fun fetchReleases(): List<ReleaseCandidate>? = runCatching {
        val response = httpClient.get("$apiBaseUrl/repos/$repo/releases") {
            header("Accept", "application/vnd.github+json")
            header("Cache-Control", "no-cache")
        }
        if (!response.status.isSuccess()) return null
        json.decodeFromString<List<GitHubReleaseResponse>>(response.bodyAsText())
            .filterNot { it.draft || it.prerelease }
            .map { it.toCandidate() }
    }.getOrNull()

    @Serializable
    private data class GitHubReleaseResponse(
        @SerialName("tag_name") val tagName: String,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val body: String = "",
        @SerialName("html_url") val htmlUrl: String = "",
        @SerialName("published_at") val publishedAt: String? = null,
    ) {
        // There is no downloadable artifact for iOS, so the url/size an Android release carries for
        // its APK are simply absent here; only the version, changelog and release page matter.
        fun toCandidate() = ReleaseCandidate(
            tagName = tagName,
            downloadUrl = htmlUrl,
            sizeBytes = 0L,
            body = body,
            htmlUrl = htmlUrl,
            publishedAt = publishedAt.orEmpty(),
        )
    }
}
