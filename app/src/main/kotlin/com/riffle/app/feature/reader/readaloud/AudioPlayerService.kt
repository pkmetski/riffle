package com.riffle.app.feature.reader.readaloud

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.riffle.app.MainActivity
import com.riffle.app.R
import com.riffle.app.feature.audio.MediaItemRestorerRegistry
import com.riffle.app.feature.audio.MediaSourceRegistry
import com.riffle.app.feature.audiobook.AbsolutePositionPlayer
import com.riffle.feature.player.SkipIconBucket
import com.riffle.feature.player.SkipIntervals
import com.riffle.feature.player.skipBackwardLabel
import com.riffle.feature.player.skipForwardLabel
import org.koin.android.ext.android.inject

/**
 * Foreground [MediaSessionService] that plays Readaloud audio. Media3 supplies the media
 * notification, lock-screen transport, and Bluetooth-headset media-button handling out of the box
 * via the [MediaSession]; [ExoPlayer] manages audio focus (incoming calls / nav voice pause us)
 * and the "becoming noisy" pause-on-unplug behaviour.
 *
 * Per-scheme data-source dispatch (HTTP audiobook tracks, on-disk bundle, streamed readaloud
 * segments) is delegated to [MediaSourceRegistry]; the controller→service Binder hop's URI-strip
 * is undone by [MediaItemRestorerRegistry]. Both are injected — a new audio source is one
 * `MediaSourceFactory` + `MediaItemRestorer` entry, no edits here (issue #333).
 */
@OptIn(UnstableApi::class)
class AudioPlayerService : MediaSessionService() {

    private val mediaSourceRegistry: MediaSourceRegistry by inject()
    private val mediaItemRestorerRegistry: MediaItemRestorerRegistry by inject()

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
        val exoPlayer = ExoPlayer.Builder(this)
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
            .setMediaSourceFactory(DefaultMediaSourceFactory(mediaSourceRegistry.asDataSourceFactory()))
            .build()
        // Wraps ExoPlayer so the OS media controls (notification, lock screen, Bluetooth) see
        // book-absolute position / total duration rather than per-track (per-chapter) values.
        val player = AbsolutePositionPlayer(exoPlayer)
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(MediaItemUriRestoringCallback(mediaItemRestorerRegistry))
            .setSessionActivity(openRiffleIntent())
            .build()
            .also { session ->
                // setMediaButtonPreferences controls the exact button order in the notification and
                // lock-screen player across all Android versions — slot hints on CommandButton are
                // only honoured on API 33+. Listing rewind + play/pause + forward here gives the
                // desired ⟲ · ▶/⏸ · ⟳ layout without a custom notification provider.
                session.setMediaButtonPreferences(mediaButtonPreferences(SkipIntervals.DEFAULT))
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
     * sends items across the session Binder — only `mediaId` + metadata survive. The
     * [MediaItemRestorerRegistry] rebuilds the URI (and per-segment clipping where applicable) from
     * the `mediaId` so the player receives playable items instead of staying silent.
     */
    private class MediaItemUriRestoringCallback(
        private val restorers: MediaItemRestorerRegistry,
    ) : MediaSession.Callback {

        /**
         * The jumps the notification's ⟲ / ⟳ buttons draw and perform. Pushed by the player
         * controllers via [CMD_SET_SKIP_INTERVALS] whenever the Listening preference changes —
         * the buttons used to be built once with a hardcoded 15 s / 30 s, so the Settings steppers
         * only ever reached the in-app player.
         */
        private var skipIntervals: SkipIntervals = SkipIntervals.DEFAULT

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(restorers.restoreAll(mediaItems))

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
                .add(CMD_SET_SKIP_INTERVALS)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val player = session.player
            return when (customCommand.customAction) {
                CMD_REWIND.customAction -> {
                    val target = skipIntervals.backwardTargetSec(player.currentPosition / MS_PER_SEC)
                    player.seekTo((target * MS_PER_SEC).toLong())
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CMD_FORWARD.customAction -> {
                    val duration = player.duration
                    val target = skipIntervals.forwardTargetSec(
                        currentSec = player.currentPosition / MS_PER_SEC,
                        durationSec = if (duration != C.TIME_UNSET) duration / MS_PER_SEC else 0.0,
                    )
                    player.seekTo((target * MS_PER_SEC).toLong())
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CMD_SET_SKIP_INTERVALS.customAction -> {
                    val updated = SkipIntervals.of(
                        forwardSec = args.getInt(ARG_SKIP_FORWARD_SEC, skipIntervals.forwardSec),
                        backwardSec = args.getInt(ARG_SKIP_BACKWARD_SEC, skipIntervals.backwardSec),
                    )
                    if (updated != skipIntervals) {
                        skipIntervals = updated
                        session.setMediaButtonPreferences(mediaButtonPreferences(updated))
                    }
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                else -> super.onCustomCommand(session, controller, customCommand, args)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
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
        // The action strings are part of the session's wire contract with already-installed
        // controllers, so they keep their historical "_15"/"_30" suffixes even though the jump is
        // now whatever the user configured.
        val CMD_REWIND  = SessionCommand("com.riffle.REWIND_15",  Bundle.EMPTY)
        val CMD_FORWARD = SessionCommand("com.riffle.FORWARD_30", Bundle.EMPTY)

        /** Carries [ARG_SKIP_FORWARD_SEC] / [ARG_SKIP_BACKWARD_SEC]; see [skipIntervalsArgs]. */
        val CMD_SET_SKIP_INTERVALS = SessionCommand("com.riffle.SET_SKIP_INTERVALS", Bundle.EMPTY)

        const val ARG_SKIP_FORWARD_SEC = "forward_sec"
        const val ARG_SKIP_BACKWARD_SEC = "backward_sec"

        private const val MS_PER_SEC = 1000.0

        fun skipIntervalsArgs(intervals: SkipIntervals): Bundle = Bundle().apply {
            putInt(ARG_SKIP_FORWARD_SEC, intervals.forwardSec)
            putInt(ARG_SKIP_BACKWARD_SEC, intervals.backwardSec)
        }

        /**
         * The notification / lock-screen transport cluster for [intervals]. Media3 only ships
         * numbered glyphs for 5 / 10 / 15 / 30 s, so anything else falls back to the unnumbered
         * arrow — the bucketing is shared with iOS via [SkipIconBucket].
         */
        @OptIn(UnstableApi::class)
        fun mediaButtonPreferences(intervals: SkipIntervals): ImmutableList<CommandButton> =
            ImmutableList.of(
                CommandButton.Builder(backIcon(intervals.backwardSec))
                    .setDisplayName(skipBackwardLabel(intervals.backwardSec))
                    .setSessionCommand(CMD_REWIND)
                    .build(),
                CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                    .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
                    .setDisplayName("Play / Pause")
                    .build(),
                CommandButton.Builder(forwardIcon(intervals.forwardSec))
                    .setDisplayName(skipForwardLabel(intervals.forwardSec))
                    .setSessionCommand(CMD_FORWARD)
                    .build(),
            )

        @OptIn(UnstableApi::class)
        private fun backIcon(seconds: Int): Int = when (SkipIconBucket.of(seconds)) {
            SkipIconBucket.SEC_5 -> CommandButton.ICON_SKIP_BACK_5
            SkipIconBucket.SEC_10 -> CommandButton.ICON_SKIP_BACK_10
            SkipIconBucket.SEC_15 -> CommandButton.ICON_SKIP_BACK_15
            SkipIconBucket.SEC_30 -> CommandButton.ICON_SKIP_BACK_30
            SkipIconBucket.GENERIC -> CommandButton.ICON_SKIP_BACK
        }

        @OptIn(UnstableApi::class)
        private fun forwardIcon(seconds: Int): Int = when (SkipIconBucket.of(seconds)) {
            SkipIconBucket.SEC_5 -> CommandButton.ICON_SKIP_FORWARD_5
            SkipIconBucket.SEC_10 -> CommandButton.ICON_SKIP_FORWARD_10
            SkipIconBucket.SEC_15 -> CommandButton.ICON_SKIP_FORWARD_15
            SkipIconBucket.SEC_30 -> CommandButton.ICON_SKIP_FORWARD_30
            SkipIconBucket.GENERIC -> CommandButton.ICON_SKIP_FORWARD
        }
    }
}
