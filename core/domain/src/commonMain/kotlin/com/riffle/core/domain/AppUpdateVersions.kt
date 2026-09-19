package com.riffle.core.domain

/**
 * A published release, in the platform-neutral shape [AppUpdateVersions] compares. Android maps
 * its `GitHubRelease` (core:network) onto this; iOS decodes the GitHub API into it directly.
 */
data class ReleaseCandidate(
    val tagName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val body: String = "",
    val htmlUrl: String = "",
    val publishedAt: String = "",
)

/**
 * Version-tag arithmetic and release comparison, shared so both platforms agree on what counts as
 * "newer". A drift here would have one platform offering an update the other considers stale.
 */
object AppUpdateVersions {

    /**
     * Pure comparison of [release] against the installed [currentVersionCode]. An unparseable tag
     * is a [UpdateCheckResult.Failed]; an equal-or-lower code (e.g. a dev build at a higher code)
     * is [UpdateCheckResult.UpToDate].
     */
    fun evaluate(currentVersionCode: Int, release: ReleaseCandidate): UpdateCheckResult {
        val versionName = release.tagName.removePrefix("v")
        val versionCode = versionCodeOf(versionName)
            ?: return UpdateCheckResult.Failed("Unrecognized release tag '${release.tagName}'")
        return if (versionCode <= currentVersionCode) {
            UpdateCheckResult.UpToDate
        } else {
            UpdateCheckResult.UpdateAvailable(
                AvailableUpdate(
                    versionName = versionName,
                    versionCode = versionCode,
                    downloadUrl = release.downloadUrl,
                    sizeBytes = release.sizeBytes,
                ),
            )
        }
    }

    fun listReleasesSince(releases: List<ReleaseCandidate>, sinceVersionCode: Int): List<ReleaseInfo> =
        releases.mapNotNull { release ->
            val versionName = release.tagName.removePrefix("v")
            val versionCode = versionCodeOf(versionName) ?: return@mapNotNull null
            if (versionCode <= sinceVersionCode) return@mapNotNull null
            ReleaseInfo(
                versionName = versionName,
                versionCode = versionCode,
                changelog = release.body,
                downloadUrl = release.downloadUrl,
                sizeBytes = release.sizeBytes,
                releaseUrl = release.htmlUrl,
                publishedAt = release.publishedAt,
            )
        }

    /**
     * Mirrors the release workflow's tag→code formula:
     * vMAJOR.MINOR.PATCH → MAJOR*10000 + MINOR*100 + PATCH. Returns null for any tag that is not
     * three numeric dot-separated parts.
     */
    fun versionCodeOf(versionName: String): Int? {
        val parts = versionName.trim().split(".")
        if (parts.size != 3) return null
        val (maj, min, pat) = parts.map { it.toIntOrNull() ?: return null }
        return maj * 10000 + min * 100 + pat
    }
}
