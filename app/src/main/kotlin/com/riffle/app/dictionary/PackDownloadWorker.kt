package com.riffle.app.dictionary

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.riffle.core.data.dictionary.PackDownloader
import com.riffle.core.dictionary.LanguageCatalogEntry
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
            val jsonlUrl = inputData.getString(KEY_JSONL_URL) ?: return Result.failure()
            val displayName = inputData.getString(KEY_DISPLAY_NAME) ?: return Result.failure()
            val sizeBytes = inputData.getLong(KEY_SIZE_BYTES, -1L)
                .takeIf { it >= 0 } ?: return Result.failure()
            val attributionHtml = inputData.getString(KEY_ATTRIBUTION_HTML).orEmpty()
            val licenseUrl = inputData.getString(KEY_LICENSE_URL).orEmpty()

            val entry = LanguageCatalogEntry(
                languageTag = languageTag,
                displayName = displayName,
                jsonlUrl = jsonlUrl,
                approximateSizeBytes = sizeBytes,
                attributionHtml = attributionHtml,
                licenseUrl = licenseUrl,
            )
            val downloader = EntryPointAccessors
                .fromApplication(applicationContext, DownloadEntryPoint::class.java)
                .packDownloader()

            downloadResultFor(downloader.download(entry))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }

    companion object {
        const val KEY_LANGUAGE_TAG = "languageTag"
        const val KEY_JSONL_URL = "jsonlUrl"
        const val KEY_DISPLAY_NAME = "displayName"
        const val KEY_SIZE_BYTES = "sizeBytes"
        const val KEY_ATTRIBUTION_HTML = "attributionHtml"
        const val KEY_LICENSE_URL = "licenseUrl"
    }
}
