package com.riffle.app.dictionary

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.riffle.core.dictionary.LanguageCatalogEntry
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class DictionaryPackScheduler @Inject constructor() {

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    open fun enqueueDownload(context: Context, entry: LanguageCatalogEntry) {
        val data = workDataOf(
            PackDownloadWorker.KEY_LANGUAGE_TAG to entry.languageTag,
            PackDownloadWorker.KEY_JSONL_URL to entry.jsonlUrl,
            PackDownloadWorker.KEY_DISPLAY_NAME to entry.displayName,
            PackDownloadWorker.KEY_SIZE_BYTES to entry.approximateSizeBytes,
            PackDownloadWorker.KEY_ATTRIBUTION_HTML to entry.attributionHtml,
            PackDownloadWorker.KEY_LICENSE_URL to entry.licenseUrl,
        )
        val request = OneTimeWorkRequestBuilder<PackDownloadWorker>()
            .setInputData(data)
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "dict_download_${entry.languageTag}",
                ExistingWorkPolicy.KEEP,
                request,
            )
    }
}
