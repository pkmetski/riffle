package com.riffle.app.feature.reader.readaloud

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.riffle.app.MainActivity
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.riffle.app.R

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
        // Show the Riffle mark in the status bar / system media player instead of Media3's default
        // generic music-note small icon.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notification) },
        )
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            // DefaultMediaSourceFactory (not ProgressiveMediaSource.Factory) so the streaming path's
            // per-segment MediaItem.clippingConfiguration is honoured. Bundle items carry no clipping,
            // so their behaviour is unchanged.
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolvingDataSourceFactory()))
            .build()
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(MediaItemUriRestoringCallback)
            .setSessionActivity(openRiffleIntent())
            .build()
            .also { session ->
                // setMediaButtonPreferences controls the exact button order in the notification and
                // lock-screen player across all Android versions — slot hints on CommandButton are
                // only honoured on API 33+. Listing rewind + play/pause + forward here gives the
                // desired ⟲15 · ▶/⏸ · ⟳30 layout without a custom notification provider.
                session.setMediaButtonPreferences(
                    ImmutableList.of(
                        CommandButton.Builder(CommandButton.ICON_SKIP_BACK_15)
                            .setDisplayName("Rewind 15 seconds")
                            .setSessionCommand(CMD_REWIND)
                            .build(),
                        CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                            .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
                            .setDisplayName("Play / Pause")
                            .build(),
                        CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
                            .setDisplayName("Forward 30 seconds")
                            .setSessionCommand(CMD_FORWARD)
                            .build(),
                    )
                )
            }
    }

    /**
     * Tapping the media notification or lock-screen player reopens Riffle on the active player view.
     * The intent carries no per-item data — a constant action — because the player is paged through a
     * single Compose [MainActivity]: it consults [com.riffle.app.playback.NowPlayingStore] at tap time
     * to route to the audiobook player or the reader's readaloud session. [Intent.FLAG_ACTIVITY_SINGLE_TOP]
     * plus the activity's `singleTop` launch mode reuses the running instance via `onNewIntent`.
     */
    private fun openRiffleIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_NOW_PLAYING)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )
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
            val streaming = SharedBundle.streaming
            val restored = mediaItems.map { item ->
                if (item.localConfiguration != null || item.mediaId.isEmpty()) {
                    return@map item
                }
                val streamItem = streaming?.itemsByMediaId?.get(item.mediaId)
                if (streamItem != null) {
                    // Streaming: point at the ABS track, clipped to this segment's window.
                    item.buildUpon()
                        .setUri(streamItem.url)
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(streamItem.clipStartMs)
                                .setEndPositionMs(streamItem.clipEndMs)
                                .build(),
                        )
                        .build()
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

        // Advertise the two custom seek commands so controllers can dispatch them.
        // Button ordering is handled by setMediaButtonPreferences() in onCreate() —
        // setCustomLayout() here is intentionally omitted.
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(CMD_REWIND)
                .add(CMD_FORWARD)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        // ── new: dispatch custom button taps to the player ──────────────────────
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val player = session.player
            return when (customCommand.customAction) {
                CMD_REWIND.customAction -> {
                    val target = (player.currentPosition - 15_000L).coerceAtLeast(0L)
                    player.seekTo(target)
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CMD_FORWARD.customAction -> {
                    val target = player.currentPosition + 30_000L
                    val duration = player.duration
                    player.seekTo(if (duration != C.TIME_UNSET) target.coerceAtMost(duration) else target)
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                else -> super.onCustomCommand(session, controller, customCommand, args)
            }
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

    companion object {
        val CMD_REWIND  = SessionCommand("com.riffle.REWIND_15",  Bundle.EMPTY)
        val CMD_FORWARD = SessionCommand("com.riffle.FORWARD_30", Bundle.EMPTY)
    }
}
