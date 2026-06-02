package com.riffle.app.feature.reader.readaloud

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
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
 * The player is fed by a [ZipAudioDataSource] so audio streams straight out of the synced EPUB
 * bundle on disk (ADR 0023). The bundle file is supplied per book by [ReadaloudController] before
 * playback begins.
 */
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
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(SharedBundle.dataSourceFactory()))
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
                    item.buildUpon().setUri(ZipAudioDataSource.uriFor(item.mediaId)).build()
                }
            }.toMutableList()
            return Futures.immediateFuture(restored)
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
