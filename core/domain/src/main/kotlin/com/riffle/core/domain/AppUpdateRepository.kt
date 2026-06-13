package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Self-updates the app from its GitHub releases. Riffle is sideloaded (direct APK / F-Droid), so
 * there is no store to push updates: the user checks manually from Settings, and when the installed
 * build is behind the latest release we download its signed APK and hand it to the system installer.
 */
interface AppUpdateRepository {
    /** Compares the installed [currentVersionCode] against the latest GitHub release. */
    suspend fun checkForUpdate(currentVersionCode: Int): UpdateCheckResult

    /**
     * Downloads [update]'s APK into the update cache, emitting download progress, then launches the
     * system installer. The APK is deliberately left on disk for the installer to read — a successful
     * update restarts the app before any post-install cleanup could run, so [sweepStaleApks] reclaims
     * it on the next launch instead.
     */
    fun downloadAndInstall(update: AvailableUpdate): Flow<UpdateDownloadState>

    /** Deletes any APKs left in the update cache by a previous download. Safe to call on startup. */
    fun sweepStaleApks()
}

/** Outcome of an [AppUpdateRepository.checkForUpdate] call. */
sealed interface UpdateCheckResult {
    /** The installed build is the latest — or newer, e.g. a local dev build. */
    data object UpToDate : UpdateCheckResult

    data class UpdateAvailable(val update: AvailableUpdate) : UpdateCheckResult

    data class Failed(val message: String) : UpdateCheckResult
}

/** A newer release available for download. */
data class AvailableUpdate(
    val versionName: String,
    val versionCode: Int,
    val downloadUrl: String,
    val sizeBytes: Long,
)

/** Progress of [AppUpdateRepository.downloadAndInstall]. */
sealed interface UpdateDownloadState {
    data class Downloading(val percent: Int) : UpdateDownloadState

    /** The APK finished downloading and the system installer has been launched. */
    data object Installing : UpdateDownloadState

    data class Failed(val message: String) : UpdateDownloadState
}

/** Launches Android's package installer for a downloaded APK. */
interface ApkInstaller {
    fun install(apk: File)
}
