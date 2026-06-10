package com.riffle.app.feature.reader.readaloud

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * App-managed, LRU-capped on-disk cache for streamed Readaloud audio (ADR 0028, amending ADR 0024
 * for the audio tier). Lives in app-owned storage — not the OS cache dir — because [SimpleCache]
 * corrupts if the OS clears spans underneath it. The cap is a fixed internal default, not a setting.
 */
@OptIn(UnstableApi::class)
object StreamingAudioCache {
    private const val MAX_BYTES = 2L * 1024 * 1024 * 1024 // 2 GB evictable budget

    @Volatile
    private var instance: SimpleCache? = null

    fun get(context: Context): SimpleCache = instance ?: synchronized(this) {
        instance ?: SimpleCache(
            File(context.applicationContext.filesDir, "readaloud-audio-cache"),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { instance = it }
    }

    /**
     * Streams ABS audio over HTTP with Range, writing fetched spans through to [get]'s cache so a
     * listen progressively fills the file and replays come from disk. The bearer token authenticates
     * each request.
     */
    fun dataSourceFactory(context: Context, bearerToken: String): DataSource.Factory {
        val http = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Authorization" to "Bearer $bearerToken"))
            .setAllowCrossProtocolRedirects(true)
        return CacheDataSource.Factory()
            .setCache(get(context))
            .setUpstreamDataSourceFactory(http)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}
