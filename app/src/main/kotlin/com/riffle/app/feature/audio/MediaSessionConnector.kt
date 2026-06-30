package com.riffle.app.feature.audio

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.riffle.app.feature.reader.readaloud.AudioPlayerService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * Shared [MediaController] lifecycle the audiobook and readaloud controllers used to hand-roll
 * each (~50 lines of identical buildAsync → suspendCancellableCoroutine → listener-attach). ADR 0032
 * makes any divergence between the two controllers' connection state load-bearing for the
 * pre-warmed handoff — one connector to debug instead of two.
 *
 * Each consumer holds its own instance (the dance is per-handle); the controller binder this
 * connector vends still talks to the single shared [AudioPlayerService] session.
 */
interface MediaSessionConnector {
    /**
     * Returns the active [MediaController], building+binding one if needed. May return `null` if
     * no Android [Context] is available (a test fake configured without one).
     */
    suspend fun ensureConnected(): MediaController?

    /** The current controller without connecting; `null` when no session is bound. */
    val controller: MediaController?

    /** Attach a [Player.Listener] idempotently — repeat calls are no-ops. */
    fun attachListener(listener: Player.Listener)

    /**
     * Pause + detach the listener but keep the binder alive — used during the audiobook↔readaloud
     * swipe handoff so the incoming side reconnects in ~0 ms (ADR 0032). Does NOT stop or clear
     * media items: the incoming side replaces the queue with its own [MediaController.setMediaItems].
     */
    fun releaseForHandoff()

    /** Stop, clear, release the controller and forget it. */
    fun release()
}

class DefaultMediaSessionConnector @Inject constructor(
    @ApplicationContext private val context: Context?,
) : MediaSessionConnector {

    private var _controller: MediaController? = null
    override val controller: MediaController? get() = _controller
    private var attached: Player.Listener? = null

    override suspend fun ensureConnected(): MediaController? {
        val existing = _controller
        if (existing != null) return existing
        val ctx = context ?: return null
        val token = SessionToken(ctx, ComponentName(ctx, AudioPlayerService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        val newC = suspendCancellableCoroutine<MediaController?> { cont ->
            future.addListener(
                { cont.resume(runCatching { future.get() }.getOrNull()) },
                ContextCompat.getMainExecutor(ctx),
            )
        }
        _controller = newC
        return newC
    }

    override fun attachListener(listener: Player.Listener) {
        if (attached === listener) return
        _controller?.addListener(listener)
        attached = listener
    }

    override fun releaseForHandoff() {
        val c = _controller ?: return
        attached?.let { c.removeListener(it) }
        attached = null
        // The caller has already pause()d via its own controller reference; binder stays alive.
    }

    override fun release() {
        val c = _controller ?: return
        attached?.let { c.removeListener(it) }
        c.stop()
        c.clearMediaItems()
        c.release()
        _controller = null
        attached = null
    }
}
