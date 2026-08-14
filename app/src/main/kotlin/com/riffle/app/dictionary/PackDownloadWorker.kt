package com.riffle.app.dictionary

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.riffle.core.data.dictionary.PackDownloader
import com.riffle.core.dictionary.PackInfo
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

class PackDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface DownloadEntryPoint {
        fun packDownloader(): PackDownloader
    }

    override suspend fun doWork(): Result =
        try {
            val languageTag = inputData.getString(KEY_LANGUAGE_TAG) ?: return Result.failure()
            val downloadUrl = inputData.getString(KEY_DOWNLOAD_URL) ?: return Result.failure()
            val sha256 = inputData.getString(KEY_SHA256) ?: return Result.failure()
            val packVersion = inputData.getString(KEY_PACK_VERSION) ?: return Result.failure()
            val sizeBytes = inputData.getLong(KEY_SIZE_BYTES, -1L)
                .takeIf { it >= 0 } ?: return Result.failure()
            val attributionHtml = inputData.getString(KEY_ATTRIBUTION_HTML).orEmpty()
            val licenseUrl = inputData.getString(KEY_LICENSE_URL).orEmpty()

            val packInfo = PackInfo(
                languageTag = languageTag,
                packVersion = packVersion,
                downloadUrl = downloadUrl,
                sha256 = sha256,
                sizeBytes = sizeBytes,
                attributionHtml = attributionHtml,
                licenseUrl = licenseUrl,
            )
            val downloader = EntryPointAccessors
                .fromApplication(applicationContext, DownloadEntryPoint::class.java)
                .packDownloader()

            downloadResultFor(downloader.download(packInfo))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }

    companion object {
        const val KEY_LANGUAGE_TAG = "languageTag"
        const val KEY_DOWNLOAD_URL = "downloadUrl"
        const val KEY_SHA256 = "sha256"
        const val KEY_PACK_VERSION = "packVersion"
        const val KEY_SIZE_BYTES = "sizeBytes"
        const val KEY_ATTRIBUTION_HTML = "attributionHtml"
        const val KEY_LICENSE_URL = "licenseUrl"
    }
}
