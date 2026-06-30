package com.riffle.app.feature.audio

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.riffle.app.feature.reader.readaloud.SharedBundle
import com.riffle.app.feature.reader.readaloud.ZipAudioDataSource
import javax.inject.Inject

/**
 * Rebuilds a [MediaItem] whose playback URI Media3 stripped when the controller sent it across the
 * session Binder (only `mediaId` + metadata survive that hop). Each restorer recognises one item
 * shape and returns the rebuilt item, or `null` to defer to the next restorer in the
 * [MediaItemRestorerRegistry] chain.
 */
@OptIn(UnstableApi::class)
interface MediaItemRestorer {
    fun restore(item: MediaItem): MediaItem?
}

/**
 * Readaloud-while-streaming items (ADR 0028): the mediaId is the canonical `audioSrc` and the
 * concrete ABS URL + per-segment clip window are pulled from the active streaming context.
 */
@OptIn(UnstableApi::class)
class StreamingReadaloudItemRestorer @Inject constructor() : MediaItemRestorer {
    override fun restore(item: MediaItem): MediaItem? {
        val streamItem = SharedBundle.streaming?.itemsByMediaId?.get(item.mediaId) ?: return null
        return item.buildUpon()
            .setUri(streamItem.url)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(streamItem.clipStartMs)
                    .setEndPositionMs(streamItem.clipEndMs)
                    .build(),
            )
            .build()
    }
}

/**
 * Audiobook tracks (ADR 0029): each track's mediaId is its full URL — `http(s)` while streaming,
 * `file://` once downloaded. Parse-and-restore is enough; no clipping is needed.
 */
@OptIn(UnstableApi::class)
class AudiobookHttpItemRestorer @Inject constructor() : MediaItemRestorer {
    override fun restore(item: MediaItem): MediaItem? {
        val id = item.mediaId
        if (!(id.startsWith("http") || id.startsWith("file"))) return null
        return item.buildUpon().setUri(Uri.parse(id)).build()
    }
}

/**
 * Readaloud bundle items (ADR 0023): the mediaId is the zip-entry path of the audio resource.
 * Rebuilt to the `zipaudio:///<path>` URI [BundleAudioSourceFactory] consumes.
 */
@OptIn(UnstableApi::class)
class BundleZipItemRestorer @Inject constructor() : MediaItemRestorer {
    override fun restore(item: MediaItem): MediaItem? {
        // Trailing fallback: a zip-path mediaId is anything that isn't recognised by the upstream
        // restorers in the registry's ordered chain.
        if (item.mediaId.isEmpty()) return null
        return item.buildUpon().setUri(ZipAudioDataSource.uriFor(item.mediaId)).build()
    }
}

/**
 * Walks an ordered [restorers] chain until one returns a non-null rebuilt item. Items whose
 * [MediaItem.localConfiguration] is already populated, or whose mediaId is empty, pass through
 * untouched — Media3 only strips the URI on the controller→service Binder hop, so items built in
 * the service itself reach this point with their URI intact.
 */
@OptIn(UnstableApi::class)
class MediaItemRestorerRegistry @Inject constructor(
    private val restorers: List<@JvmSuppressWildcards MediaItemRestorer>,
) {
    fun restoreAll(items: List<MediaItem>): MutableList<MediaItem> =
        items.map { item ->
            if (item.localConfiguration != null || item.mediaId.isEmpty()) return@map item
            restorers.firstNotNullOfOrNull { it.restore(item) } ?: item
        }.toMutableList()
}
