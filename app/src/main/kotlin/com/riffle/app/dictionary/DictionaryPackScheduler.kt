package com.riffle.app.dictionary

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.riffle.core.dictionary.PackInfo
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class DictionaryPackScheduler @Inject constructor() {

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    open fun enqueueDownload(context: Context, packInfo: PackInfo) {
        val data = workDataOf(
            PackDownloadWorker.KEY_LANGUAGE_TAG to packInfo.languageTag,
            PackDownloadWorker.KEY_DOWNLOAD_URL to packInfo.downloadUrl,
            PackDownloadWorker.KEY_SHA256 to packInfo.sha256,
            PackDownloadWorker.KEY_PACK_VERSION to packInfo.packVersion,
            PackDownloadWorker.KEY_SIZE_BYTES to packInfo.sizeBytes,
            PackDownloadWorker.KEY_ATTRIBUTION_HTML to packInfo.attributionHtml,
            PackDownloadWorker.KEY_LICENSE_URL to packInfo.licenseUrl,
        )
        val request = OneTimeWorkRequestBuilder<PackDownloadWorker>()
            .setInputData(data)
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "dict_download_${packInfo.languageTag}",
                ExistingWorkPolicy.KEEP,
                request,
            )
    }

    fun ensurePeriodicRefresh(context: Context) {
        val request = PeriodicWorkRequestBuilder<PackRefreshWorker>(7, TimeUnit.DAYS)
            .setConstraints(networkConstraint)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                "dict_refresh",
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
    }
}
