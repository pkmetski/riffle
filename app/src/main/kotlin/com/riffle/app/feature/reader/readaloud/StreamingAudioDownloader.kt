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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Makes a streamed Readaloud available offline (ADR 0028) by eagerly filling the audio cache with
 * every ABS track ahead of playback, via ExoPlayer's [CacheWriter]. This is the "Download readaloud"
 * action for a streaming-eligible book — the ebook and sidecar are already small/cached, so the audio
 * is the only heavy part. Progress is reported as a 0..1 fraction across the distinct tracks.
 */
@OptIn(UnstableApi::class)
object StreamingAudioDownloader {

    suspend fun download(
        context: Context,
        items: List<StreamingMediaItem>,
        bearerToken: String,
        onProgress: (Float) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
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
                .createDataSource()
            CacheWriter(dataSource, DataSpec(Uri.parse(url)), null, null).cache()
            onProgress((index + 1f) / urls.size)
        }
    }
}
