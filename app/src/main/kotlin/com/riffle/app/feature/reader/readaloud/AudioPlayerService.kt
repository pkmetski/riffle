package com.riffle.app.feature.reader.readaloud

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Foreground [MediaSessionService] that plays Readaloud audio. Media3 supplies the media
 * notification, lock-screen transport, and Bluetooth-headset media-button handling out of the box
 * via the [MediaSession]; [ExoPlayer] manages audio focus (incoming calls / nav voice pause us)
 * and the "becoming noisy" pause-on-unplug behaviour.
 *
 * Two audio sources flow through this one service. **Readaloud** streams out of the synced EPUB
 * bundle on disk via [ZipAudioDataSource] (`zipaudio://`, ADR 0023). An **[Audiobook]** streams its
 * tracks directly over HTTP(S) from ABS (`http(s)://`, with the auth token carried as a `?token=`
 * query param so a plain HTTP data source suffices — ADR 0029). The [resolvingDataSourceFactory]
 * dispatches by URI scheme so both work without a second player or service.
 */
@OptIn(UnstableApi::class)
class AudioPlayerService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolvingDataSourceFactory()))
            .build()
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(MediaItemUriRestoringCallback)
            .build()
    }

    /**
     * Media3 strips a [MediaItem]'s playback URI (`localConfiguration`) when a `MediaController`
     * sends items across the session Binder — only `mediaId` + metadata survive. Without restoring
     * the URI here the player receives unplayable items and stays silent. [ReadaloudController] sets
     * each `mediaId` to the audio resource's zip-entry path, so we rebuild the `zipaudio://` URI
     * from it (see [ZipAudioDataSource]).
     */
    private object MediaItemUriRestoringCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val restored = mediaItems.map { item ->
                if (item.localConfiguration != null || item.mediaId.isEmpty()) {
                    item
                } else {
                    // An Audiobook track's mediaId is its full URL — HTTP(S) when streaming, or a
                    // file:// URL when downloaded for offline (ADR 0029); a Readaloud clip's is the
                    // zip-entry path.
                    val uri = if (item.mediaId.startsWith("http") || item.mediaId.startsWith("file")) {
                        Uri.parse(item.mediaId)
                    } else {
                        ZipAudioDataSource.uriFor(item.mediaId)
                    }
                    item.buildUpon().setUri(uri).build()
                }
            }.toMutableList()
            return Futures.immediateFuture(restored)
        }
    }

    /**
     * Dispatches by URI scheme: `http`/`https` → a default HTTP data source (ABS audiobook tracks,
     * ADR 0029); anything else → the [ZipAudioDataSource] for Readaloud bundle audio (ADR 0023).
     * The concrete delegate is chosen lazily in [SchemeResolvingDataSource.open] from the DataSpec's
     * scheme, so neither source is built until a URI is known.
     */
    private fun resolvingDataSourceFactory(): DataSource.Factory =
        DataSource.Factory { SchemeResolvingDataSource(DefaultHttpDataSource.Factory(), SharedBundle.dataSourceFactory()) }

    private class SchemeResolvingDataSource(
        private val http: DataSource.Factory,
        private val zip: DataSource.Factory,
    ) : DataSource {
        private val transferListeners = mutableListOf<androidx.media3.datasource.TransferListener>()
        private var delegate: DataSource? = null

        override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
            transferListeners.add(transferListener)
        }

        override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
            val source = when (dataSpec.uri.scheme) {
                "http", "https" -> http.createDataSource() // streamed ABS audiobook tracks
                "file" -> androidx.media3.datasource.FileDataSource() // downloaded audiobook tracks
                else -> zip.createDataSource() // Readaloud bundle audio
            }
            transferListeners.forEach { source.addTransferListener(it) }
            delegate = source
            return source.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate?.read(buffer, offset, length) ?: throw IllegalStateException("read before open")

        override fun getUri(): Uri? = delegate?.uri

        override fun close() {
            delegate?.close()
            delegate = null
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // If the user swipes the app away while paused, stop the service; if playing, keep going.
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
