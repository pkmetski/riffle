package com.riffle.feature.player

import com.riffle.core.common.Clock
import com.riffle.feature.reader.ProgressFlushScope
import com.riffle.feature.reader.ReaderSyncCoordinatorInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the audiobook player's periodic "push what we've listened to ABS" tick and the terminal
 * flush that runs on pause/close/teardown.
 *
 * Extracted from [AudiobookPlayerViewModel] (issue #345, slice 1). Concentrates the cycle in one
 * named place so the "final PATCH was dropped at teardown" bug class has a single home — hot-path
 * ticks run on the caller's [CoroutineScope] (VM-scoped, cancelled with the screen), close-path
 * writes run on [ProgressFlushScope] / [ApplicationScope] and survive teardown.
 */
class FollowLoopOrchestrator constructor(
    private val clock: Clock,
    private val progressFlushScope: ProgressFlushScope,
) {
    private var job: Job? = null
    private var lastContext: FollowContext? = null

    /**
     * Start the 10s tick on [scope]. Idempotent — a second call while the loop is active is a
     * no-op. Cancels any previous loop attached to a different context; call [cancel] explicitly
     * if you want deterministic teardown.
     */
    fun start(scope: CoroutineScope, context: FollowContext) {
        if (job?.isActive == true && lastContext === context) return
        job?.cancel()
        lastContext = context
        job = scope.launch { loop(context) }
    }

    /** Cancel the tick without running a final flush (dismiss / handoff-out paths). */
    fun cancel() {
        job?.cancel()
        job = null
    }

    /**
     * Push the just-reached position without cancelling the tick — the pause branch of
     * `togglePlayPause` uses this.
     */
    fun flushNow() {
        val ctx = lastContext ?: return
        runFlush(ctx)
    }

    /**
     * Run the terminal write on the survivable scope and stop the tick. Safe to call from
     * `onCleared()` after `viewModelScope` is already cancelled.
     */
    fun stopWithFinalFlush() {
        val ctx = lastContext
        cancel()
        if (ctx != null) runFlush(ctx)
    }

    private fun runFlush(ctx: FollowContext) {
        if (!ctx.hasServer()) return
        val pos = ctx.currentAudioSec()
        if (pos < ctx.reconciledResumeSec - SETTLE_EPS_SEC) return
        val fraction = ctx.progressFraction(pos)
        progressFlushScope.flush {
            val rs = ctx.readerSync
            if (rs != null) {
                ctx.localUpdatedAt = clock.nowMs()
                val r = rs.runAudioLedCycle(pos, ctx.localUpdatedAt)
                ctx.localUpdatedAt = maxOf(ctx.localUpdatedAt, r.canonicalLastUpdate)
            } else {
                ctx.writeSinglePeerFallback(pos)
            }
            ctx.writeCloseFlush(pos, fraction)
        }
    }

    private suspend fun loop(ctx: FollowContext) {
        while (true) {
            delay(FOLLOW_INTERVAL_MS)
            if (ctx.readerSync == null && ctx.tryAttachReaderSync(ctx.currentAudioSec())) continue

            val rs = ctx.readerSync
            val pos = ctx.currentAudioSec()
            val playing = ctx.isPlaying()
            if (rs != null) {
                if (playing && pos >= ctx.reconciledResumeSec - SETTLE_EPS_SEC) {
                    ctx.localUpdatedAt = clock.nowMs()
                    ctx.reconciledResumeSec = maxOf(ctx.reconciledResumeSec, pos)
                    val r = rs.runAudioLedCycle(pos, ctx.localUpdatedAt)
                    ctx.localUpdatedAt = maxOf(ctx.localUpdatedAt, r.canonicalLastUpdate)
                    ctx.onHotPathAdvance(pos)
                } else {
                    val r = rs.runAudioLedCycle(pos, localUpdatedAt = 0L)
                    r.jumpToAudioSec?.let { seek ->
                        ctx.seekTo(seek)
                        ctx.reconciledResumeSec = seek
                    }
                    ctx.localUpdatedAt = maxOf(ctx.localUpdatedAt, r.canonicalLastUpdate)
                }
            } else if (playing && pos >= ctx.reconciledResumeSec - SETTLE_EPS_SEC) {
                ctx.reconciledResumeSec = maxOf(ctx.reconciledResumeSec, pos)
                ctx.writeSinglePeerFallback(pos)
                ctx.onHotPathAdvance(pos)
            }
        }
    }

    companion object {
        const val FOLLOW_INTERVAL_MS: Long = 10_000L
        const val SETTLE_EPS_SEC: Double = 3.0
    }
}

/**
 * Per-book state and side-effects the orchestrator drives against. Implemented by the ViewModel
 * so today's field-level state (reconciledResumeSec, localUpdatedAt) stays where its non-loop
 * consumers (seek, bookmark, handoff) already read/write it.
 */
interface FollowContext {
    // Player observation.
    fun currentAudioSec(): Double
    fun isPlaying(): Boolean
    fun seekTo(positionSec: Double)

    // Sync surface.
    val readerSync: ReaderSyncCoordinatorInterface?
    suspend fun tryAttachReaderSync(currentAudioSec: Double): Boolean

    // Persistence hooks.
    fun hasServer(): Boolean
    fun progressFraction(positionSec: Double): Float
    suspend fun onHotPathAdvance(positionSec: Double)
    suspend fun writeSinglePeerFallback(positionSec: Double)
    suspend fun writeCloseFlush(positionSec: Double, fraction: Float)

    // Shared mutable floor + adopted-timestamp state.
    var reconciledResumeSec: Double
    var localUpdatedAt: Long
}
