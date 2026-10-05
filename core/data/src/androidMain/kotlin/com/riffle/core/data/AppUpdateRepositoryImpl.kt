package com.riffle.core.data

import android.content.Context
import com.riffle.core.domain.ApkInstaller
import com.riffle.core.domain.AppUpdateRepository
import com.riffle.core.domain.ReleaseCandidate
import com.riffle.core.domain.AppUpdateVersions
import com.riffle.core.domain.AvailableUpdate
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.ReleaseInfo
import com.riffle.core.domain.UpdateCheckResult
import com.riffle.core.domain.UpdateDownloadState
import com.riffle.core.network.GitHubRelease
import com.riffle.core.network.GitHubReleaseApi
import com.riffle.core.network.GitHubReleaseResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import java.io.File

class AppUpdateRepositoryImpl constructor(
    private val context: Context,
    private val releaseApi: GitHubReleaseApi,
    private val installer: ApkInstaller,
    private val dispatchers: DispatcherProvider,
) : AppUpdateRepository {

    private val updateDir: File
        get() = File(context.cacheDir, UPDATE_DIR_NAME)

    override suspend fun checkForUpdate(currentVersionCode: Int): UpdateCheckResult =
        when (val result = releaseApi.latestRelease(REPO)) {
            is GitHubReleaseResult.Failed -> UpdateCheckResult.Failed(result.message)
            is GitHubReleaseResult.Success -> evaluate(currentVersionCode, result.release)
        }

    override fun downloadAndInstall(update: AvailableUpdate): Flow<UpdateDownloadState> = channelFlow {
        // Start clean: drop any half-finished APK from a prior attempt before downloading.
        sweepStaleApks()
        val apk = File(updateDir.apply { mkdirs() }, "riffle-${update.versionName}.apk")
        send(UpdateDownloadState.Downloading(0))
        val ok = releaseApi.download(update.downloadUrl, apk, update.sizeBytes) { percent ->
            trySend(UpdateDownloadState.Downloading(percent))
        }
        if (ok) {
            installer.install(apk)
            send(UpdateDownloadState.Installing)
        } else {
            apk.delete()
            send(UpdateDownloadState.Failed("Couldn't download the update"))
        }
    }.flowOn(dispatchers.io)

    override fun sweepStaleApks() {
        updateDir.listFiles()?.forEach { it.delete() }
    }

    override suspend fun listReleasesSince(sinceVersionCode: Int): List<ReleaseInfo> =
        listReleasesSince(releaseApi.listReleases(REPO), sinceVersionCode)

    companion object {
        const val REPO = "pkmetski/riffle"
        const val UPDATE_DIR_NAME = "updates"

        /** Maps core:network's release model onto the shared [ReleaseCandidate] shape. */
        private fun GitHubRelease.asCandidate() = ReleaseCandidate(
            tagName = tagName,
            downloadUrl = apkUrl,
            sizeBytes = apkSizeBytes,
            body = body,
            htmlUrl = htmlUrl,
            publishedAt = publishedAt,
        )

        // Version arithmetic lives in core:domain's AppUpdateVersions so Android and iOS cannot
        // disagree on what counts as a newer release; these stay as the Android-facing entry points.
        fun evaluate(currentVersionCode: Int, release: GitHubRelease): UpdateCheckResult =
            AppUpdateVersions.evaluate(currentVersionCode, release.asCandidate())

        fun listReleasesSince(releases: List<GitHubRelease>, sinceVersionCode: Int): List<ReleaseInfo> =
            AppUpdateVersions.listReleasesSince(releases.map { it.asCandidate() }, sinceVersionCode)

        fun versionCodeOf(versionName: String): Int? = AppUpdateVersions.versionCodeOf(versionName)
    }
}
