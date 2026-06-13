package com.riffle.core.data

import android.content.Context
import com.riffle.core.domain.ApkInstaller
import com.riffle.core.domain.AppUpdateRepository
import com.riffle.core.domain.AvailableUpdate
import com.riffle.core.domain.UpdateCheckResult
import com.riffle.core.domain.UpdateDownloadState
import com.riffle.core.network.GitHubRelease
import com.riffle.core.network.GitHubReleaseApi
import com.riffle.core.network.GitHubReleaseResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import javax.inject.Inject

class AppUpdateRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val releaseApi: GitHubReleaseApi,
    private val installer: ApkInstaller,
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
        val ok = releaseApi.download(update.downloadUrl, apk) { percent ->
            trySend(UpdateDownloadState.Downloading(percent))
        }
        if (ok) {
            installer.install(apk)
            send(UpdateDownloadState.Installing)
        } else {
            apk.delete()
            send(UpdateDownloadState.Failed("Couldn't download the update"))
        }
    }.flowOn(Dispatchers.IO)

    override fun sweepStaleApks() {
        updateDir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        const val REPO = "pkmetski/riffle"
        const val UPDATE_DIR_NAME = "updates"

        /**
         * Pure comparison of [release] against the installed [currentVersionCode]. An unparseable tag
         * is a [UpdateCheckResult.Failed]; an equal-or-lower code (e.g. a dev build at a higher code)
         * is [UpdateCheckResult.UpToDate].
         */
        fun evaluate(currentVersionCode: Int, release: GitHubRelease): UpdateCheckResult {
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
                        downloadUrl = release.apkUrl,
                        sizeBytes = release.apkSizeBytes,
                    )
                )
            }
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
}
