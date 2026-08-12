package com.riffle.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.riffle.core.domain.ContentCacheCleaner
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class ContentCacheCleanupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface CleanerEntryPoint {
        fun contentCacheCleaner(): ContentCacheCleaner
    }

    override suspend fun doWork(): Result =
        try {
            EntryPointAccessors.fromApplication(applicationContext, CleanerEntryPoint::class.java)
                .contentCacheCleaner()
                .cleanExpired()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
}
