package com.riffle.app.dictionary

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.riffle.core.data.dictionary.PackManifestFetcher
import com.riffle.core.dictionary.PackStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

class PackRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface RefreshEntryPoint {
        fun packManifestFetcher(): PackManifestFetcher
        fun packStore(): PackStore
        fun dictionaryPackScheduler(): DictionaryPackScheduler
    }

    override suspend fun doWork(): Result =
        try {
            val ep = EntryPointAccessors.fromApplication(applicationContext, RefreshEntryPoint::class.java)
            val manifest = ep.packManifestFetcher().fetch()
            val installed = ep.packStore().observeInstalledPacks().first()
            val scheduler = ep.dictionaryPackScheduler()

            for (installedPack in installed) {
                val remote = manifest.packs.firstOrNull { it.languageTag == installedPack.languageTag }
                    ?: continue
                if (remote.packVersion != installedPack.packVersion) {
                    scheduler.enqueueDownload(applicationContext, remote)
                }
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
}
