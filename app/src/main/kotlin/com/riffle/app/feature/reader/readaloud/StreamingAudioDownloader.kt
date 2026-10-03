package com.riffle.app.feature.reader.readaloud

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import com.riffle.core.data.StreamingMediaItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Makes a streamed Readaloud available offline (ADR 0040) by eagerly filling the audio cache with
 * every ABS track ahead of playback, via ExoPlayer's [CacheWriter]. This is the "Download readaloud"
 * action for a streaming-eligible book — the ebook and sidecar are already small/cached, so the audio
 * is the only heavy part. Progress is reported as a 0..1 fraction across the distinct tracks.
 */
@OptIn(UnstableApi::class)
object StreamingAudioDownloader {

    private const val MAX_RETRY_ATTEMPTS = 5
    private const val RETRY_BASE_DELAY_MS = 2_000L
    private const val RETRY_MAX_DELAY_MS = 30_000L

    suspend fun download(
        context: Context,
        items: List<StreamingMediaItem>,
        bearerToken: String,
        ioDispatcher: CoroutineDispatcher,
        retryBaseDelayMs: Long = RETRY_BASE_DELAY_MS,
        onProgress: (Float) -> Unit = {},
    ) = withContext(ioDispatcher) {
        val cache = StreamingAudioCache.get(context)
        val upstream = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Authorization" to "Bearer $bearerToken"))
            .setAllowCrossProtocolRedirects(true)
        val urls = items.map { it.url }.distinct()
        urls.forEachIndexed { index, url ->
            coroutineContext.ensureActive()
            val dataSource = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstream)
                // Same key factory as playback (token-free URL) — otherwise the player reads under a
                // different key than we write, and a "Download readaloud" silently re-fetches at play time.
                .setCacheKeyFactory(StreamingAudioCache.cacheKeyFactory)
                .createDataSource()
            // Report byte-level progress within each track, blended with the track index, so a single-file
            // audiobook (one URL) shows a smooth bar instead of jumping 0 → 100% only at the very end.
            val listener = CacheWriter.ProgressListener { requestLength, bytesCached, _ ->
                val withinTrack = if (requestLength > 0) bytesCached.toFloat() / requestLength else 0f
                onProgress((index + withinTrack) / urls.size)
            }
            // CacheWriter preserves already-cached bytes on failure, so each retry continues from where
            // it left off. Without automatic retry a transient drop (connection reset, server timeout)
            // requires a manual tap for every interruption — problematic for long multi-track books.
            var attempt = 0
            while (true) {
                coroutineContext.ensureActive()
                try {
                    CacheWriter(dataSource, DataSpec(Uri.parse(url)), null, listener).cache()
                    break
                } catch (e: IOException) {
                    if (attempt >= MAX_RETRY_ATTEMPTS) throw e
                    delay(minOf(retryBaseDelayMs shl attempt, RETRY_MAX_DELAY_MS))
                    attempt++
                }
            }
            onProgress((index + 1f) / urls.size)
        }
    }
}
