package com.riffle.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.riffle.core.domain.ContentCacheCleaner
import org.koin.core.context.GlobalContext

class ContentCacheCleanupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        try {
            GlobalContext.get().get<ContentCacheCleaner>().cleanExpired()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
}
