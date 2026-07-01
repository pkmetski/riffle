package com.riffle.app.feature.audiobook

import com.riffle.app.feature.reader.ProgressFlushScope
import com.riffle.app.feature.reader.ReaderSyncCoordinator
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Owns the audiobook player's periodic "push what we've listened to ABS" tick and the terminal
 * flush that runs on pause/close/teardown.
 *
 * Extracted from [AudiobookPlayerViewModel] (issue #345, slice 1). Concentrates the cycle in one
 * named place so the "final PATCH was dropped at teardown" bug class has a single home — hot-path
 * ticks run on the caller's [CoroutineScope] (VM-scoped, cancelled with the screen), close-path
 * writes run on [ProgressFlushScope] / [ApplicationScope] and survive teardown.
 *
 * Behaviour preserved 1:1 from the previous `startFollowLoop()` + `pushProgressOnStop()`:
 * - 10s tick ([FOLLOW_INTERVAL_MS]).
 * - Self-heal: at the top of each tick, if [FollowContext.readerSync] is still null, ask the
 *   context to attach it; if the attach lands, skip this tick so the reconcile-seek settles.
 * - Matched (readerSync != null) + playing + above the settle floor → stamp `now`, advance the
 *   floor, run one audio-led cycle, adopt the returned canonical timestamp, mark cold-path dirty.
 * - Matched + not playing / below floor → inbound-only cycle (local time 0); seek to any inbound
 *   jump, adopt canonical timestamp.
 * - Unmatched + playing + above floor → single-peer ABS save + cold-path dirty; else skip.
 * - Close: same audio-led (matched) or single-peer (unmatched) cycle, then the cold-path
 *   `closeFlush`. Below-floor guard identical to the tick.
 *
 * The orchestrator itself carries no per-book state; [FollowContext] owns the mutable floor and
 * timestamp so the caller can still read/write them (bookmarks, seek-to, handoff prep all touch
 * those values today).
 */
class FollowLoopOrchestrator @Inject constructor(
    private val applicationScope: ApplicationScope,
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
     * Run the terminal write on the survivable scope and stop the tick. Safe to call from
     * `onCleared()` after `viewModelScope` is already cancelled — the write is enqueued on
     * [ProgressFlushScope].
     *
     * Below-floor guard: a pause/teardown before the resume seek has settled leaves the audio
     * position at a transient book-start value; writing that would regress the record to 0.
     * Identical to the original `pushProgressOnStop()` guard.
     */
    fun stopWithFinalFlush() {
        cancel()
        val ctx = lastContext ?: return
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
        // How far below the reconciled resume a position may sit and still be treated as "settled".
        // Covers a seek/buffer landing within a tick; anything further behind is a transient that
        // must not lead. Small, so a genuine book-start 0 can never drive the ebook.
        const val SETTLE_EPS_SEC: Double = 3.0
    }
}

/**
 * Per-book state and side-effects the orchestrator drives against. Implemented by the ViewModel
 * so today's field-level state (reconciledResumeSec, localUpdatedAt) stays where its non-loop
 * consumers (seek, bookmark, handoff) already read/write it. All methods are cheap accessors or
 * delegate to existing ViewModel helpers.
 */
interface FollowContext {
    // Player observation.
    fun currentAudioSec(): Double
    fun isPlaying(): Boolean
    fun seekTo(positionSec: Double)

    // Sync surface.
    val readerSync: ReaderSyncCoordinator?
    suspend fun tryAttachReaderSync(currentAudioSec: Double): Boolean

    // Persistence hooks.
    fun hasServer(): Boolean
    fun progressFraction(positionSec: Double): Float
    fun onHotPathAdvance(positionSec: Double)
    suspend fun writeSinglePeerFallback(positionSec: Double)
    suspend fun writeCloseFlush(positionSec: Double, fraction: Float)

    // Shared mutable floor + adopted-timestamp state.
    var reconciledResumeSec: Double
    var localUpdatedAt: Long
}
