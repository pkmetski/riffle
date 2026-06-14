package com.riffle.app.feature.reader.readaloud

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
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
     * Keys cached spans by the token-free URL. The audio URL carries the auth token as `?token=` (ABS
     * authenticates the file endpoint by query param, not header), so a token refresh would otherwise
     * orphan everything already cached for the book. Shared by the playback factory AND the offline
     * [StreamingAudioDownloader] so the bytes the downloader writes are found by the player — keying the
     * two differently would make a "Download readaloud" silently re-fetch at play time (cache miss).
     */
    val cacheKeyFactory = CacheKeyFactory { dataSpec ->
        dataSpec.uri.buildUpon().clearQuery().build().toString()
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
            .setCacheKeyFactory(cacheKeyFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}
