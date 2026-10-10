package com.riffle.feature.player

import com.riffle.core.common.Clock
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.ReadaloudResumePosition
import com.riffle.feature.reader.AudioLedCycleResult
import com.riffle.feature.reader.ProgressFlushScope
import com.riffle.feature.reader.ReaderSyncCoordinatorInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FollowLoopOrchestratorTest {

    private class FakeClock(var ms: Long = 0L) : Clock {
        override fun nowMs(): Long = ms
        override fun nowNs(): Long = ms * 1_000_000L
    }

    private class TestApplicationScope(private val scope: CoroutineScope) : ApplicationScope {
        override val coroutineScope: CoroutineScope = scope
        override fun launchSurvivable(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)
        override suspend fun <T> withSurvivable(block: suspend CoroutineScope.() -> T): T = scope.async(block = block).await()
    }

    private class FakeReaderSyncCoordinator(
        override val ebookItemId: String? = null,
        private val defaultResult: AudioLedCycleResult = AudioLedCycleResult(jumpToAudioSec = null, canonicalLastUpdate = 0L),
        private val cycleResults: Map<Pair<Double, Long>, AudioLedCycleResult> = emptyMap(),
    ) : ReaderSyncCoordinatorInterface {
        val cycles = mutableListOf<Pair<Double, Long>>()

        override suspend fun runAudioLedCycle(currentAudioSec: Double, localUpdatedAt: Long): AudioLedCycleResult {
            cycles += currentAudioSec to localUpdatedAt
            return cycleResults[currentAudioSec to localUpdatedAt] ?: defaultResult
        }

        override fun canonicalForAudioSeconds(seconds: Double): String? = null
        override fun readaloudAnchorForAudioSeconds(seconds: Double): ReadaloudResumePosition? = null
    }

    private class FakeContext(
        override var reconciledResumeSec: Double = 0.0,
        override var localUpdatedAt: Long = 0L,
        override var readerSync: ReaderSyncCoordinatorInterface? = null,
    ) : FollowContext {
        var currentSec: Double = 0.0
        var playing: Boolean = false
        var hasServer: Boolean = true
        var progressFractionOf: Float = 0.42f
        var seekedTo: Double? = null
        val singlePeerWrites = mutableListOf<Double>()
        val closeFlushes = mutableListOf<Pair<Double, Float>>()
        val hotAdvances = mutableListOf<Double>()
        var attachOutcome: Boolean = false
        var attachedAt: Double? = null

        override fun currentAudioSec(): Double = currentSec
        override fun isPlaying(): Boolean = playing
        override fun seekTo(positionSec: Double) { seekedTo = positionSec }
        override suspend fun tryAttachReaderSync(currentAudioSec: Double): Boolean {
            attachedAt = currentAudioSec
            return attachOutcome
        }
        override fun hasServer(): Boolean = hasServer
        override fun progressFraction(positionSec: Double): Float = progressFractionOf
        override suspend fun onHotPathAdvance(positionSec: Double) { hotAdvances += positionSec }
        override suspend fun writeSinglePeerFallback(positionSec: Double) {
            singlePeerWrites += positionSec
        }
        override suspend fun writeCloseFlush(positionSec: Double, fraction: Float) {
            closeFlushes += (positionSec to fraction)
        }
    }

    private fun CoroutineScope.setup(): Triple<FollowLoopOrchestrator, FakeContext, FakeClock> {
        val clock = FakeClock(ms = 1_000L)
        val appScope = TestApplicationScope(this)
        val orchestrator = FollowLoopOrchestrator(
            clock = clock,
            progressFlushScope = ProgressFlushScope(appScope),
        )
        return Triple(orchestrator, FakeContext(), clock)
    }

    @Test
    fun `matched tick above floor + playing advances floor and adopts canonical stamp`() = runTest {
        val (orch, ctx, clock) = setup()
        val rs = FakeReaderSyncCoordinator(
            defaultResult = AudioLedCycleResult(jumpToAudioSec = null, canonicalLastUpdate = 5_000L),
        )
        ctx.readerSync = rs
        ctx.currentSec = 100.0
        ctx.playing = true
        ctx.reconciledResumeSec = 50.0
        clock.ms = 2_000L

        orch.start(this, ctx)
        advanceTimeBy(FollowLoopOrchestrator.FOLLOW_INTERVAL_MS + 100)
        orch.cancel()

        assertEquals(100.0, ctx.reconciledResumeSec)
        assertEquals(5_000L, ctx.localUpdatedAt, "canonical stamp adopted")
        assertEquals(listOf(100.0), ctx.hotAdvances)
        assertEquals(1, rs.cycles.size)
        assertEquals(100.0 to 2_000L, rs.cycles[0])
    }

    @Test
    fun `matched tick below floor inbound-only cycle floor untouched`() = runTest {
        val (orch, ctx, clock) = setup()
        val rs = FakeReaderSyncCoordinator(
            defaultResult = AudioLedCycleResult(jumpToAudioSec = null, canonicalLastUpdate = 42L),
        )
        ctx.readerSync = rs
        ctx.currentSec = 10.0
        ctx.reconciledResumeSec = 100.0
        ctx.playing = true
        clock.ms = 9_000L

        orch.start(this, ctx)
        advanceTimeBy(FollowLoopOrchestrator.FOLLOW_INTERVAL_MS + 100)
        orch.cancel()

        assertEquals(100.0, ctx.reconciledResumeSec, "floor untouched below-floor")
        assertEquals(42L, ctx.localUpdatedAt, "canonical stamp adopted via max()")
        assertTrue(ctx.hotAdvances.isEmpty(), "no hot advance below-floor")
        assertEquals(1, rs.cycles.size)
        assertEquals(10.0 to 0L, rs.cycles[0])
    }

    @Test
    fun `inbound jump seeks the player and moves floor to the jump`() = runTest {
        val (orch, ctx, _) = setup()
        val rs = FakeReaderSyncCoordinator(
            defaultResult = AudioLedCycleResult(jumpToAudioSec = 250.0, canonicalLastUpdate = 999L),
        )
        ctx.readerSync = rs
        ctx.currentSec = 10.0
        ctx.reconciledResumeSec = 100.0
        ctx.playing = false

        orch.start(this, ctx)
        advanceTimeBy(FollowLoopOrchestrator.FOLLOW_INTERVAL_MS + 100)
        orch.cancel()

        assertEquals(250.0 as Double?, ctx.seekedTo)
        assertEquals(250.0, ctx.reconciledResumeSec)
    }

    @Test
    fun `self-heal attaches then skips the tick`() = runTest {
        val (orch, ctx, _) = setup()
        ctx.readerSync = null
        ctx.currentSec = 42.0
        ctx.attachOutcome = true

        orch.start(this, ctx)
        advanceTimeBy(FollowLoopOrchestrator.FOLLOW_INTERVAL_MS + 100)
        orch.cancel()

        assertEquals(42.0 as Double?, ctx.attachedAt)
        assertTrue(ctx.hotAdvances.isEmpty(), "tick body skipped after successful attach")
        assertTrue(ctx.singlePeerWrites.isEmpty())
    }

    @Test
    fun `single-peer tick above floor + playing writes and advances`() = runTest {
        val (orch, ctx, _) = setup()
        ctx.readerSync = null
        ctx.currentSec = 300.0
        ctx.reconciledResumeSec = 200.0
        ctx.playing = true

        orch.start(this, ctx)
        advanceTimeBy(FollowLoopOrchestrator.FOLLOW_INTERVAL_MS + 100)
        orch.cancel()

        assertEquals(listOf(300.0), ctx.singlePeerWrites)
        assertEquals(listOf(300.0), ctx.hotAdvances)
        assertEquals(300.0, ctx.reconciledResumeSec)
    }

    @Test
    fun `single-peer tick below floor no write`() = runTest {
        val (orch, ctx, _) = setup()
        ctx.readerSync = null
        ctx.currentSec = 10.0
        ctx.reconciledResumeSec = 200.0
        ctx.playing = true

        orch.start(this, ctx)
        advanceTimeBy(FollowLoopOrchestrator.FOLLOW_INTERVAL_MS + 100)
        orch.cancel()

        assertTrue(ctx.singlePeerWrites.isEmpty())
        assertTrue(ctx.hotAdvances.isEmpty())
        assertEquals(200.0, ctx.reconciledResumeSec)
    }

    @Test
    fun `single-peer tick not playing no write`() = runTest {
        val (orch, ctx, _) = setup()
        ctx.readerSync = null
        ctx.currentSec = 300.0
        ctx.reconciledResumeSec = 200.0
        ctx.playing = false

        orch.start(this, ctx)
        advanceTimeBy(FollowLoopOrchestrator.FOLLOW_INTERVAL_MS + 100)
        orch.cancel()

        assertTrue(ctx.singlePeerWrites.isEmpty())
    }

    @Test
    fun `stopWithFinalFlush on matched runs clock-stamped cycle + closeFlush`() = runTest {
        val (orch, ctx, clock) = setup()
        val rs = FakeReaderSyncCoordinator(
            defaultResult = AudioLedCycleResult(jumpToAudioSec = null, canonicalLastUpdate = 7_000L),
        )
        ctx.readerSync = rs
        ctx.currentSec = 150.0
        ctx.reconciledResumeSec = 100.0
        clock.ms = 3_500L
        ctx.progressFractionOf = 0.55f

        orch.start(this, ctx)
        orch.cancel()

        orch.stopWithFinalFlush()
        advanceUntilIdle()

        assertEquals(7_000L, ctx.localUpdatedAt)
        assertEquals(listOf(150.0 to 0.55f), ctx.closeFlushes)
        assertEquals(1, rs.cycles.size)
        assertEquals(150.0 to 3_500L, rs.cycles[0])
    }

    @Test
    fun `stopWithFinalFlush on unmatched runs single-peer + closeFlush`() = runTest {
        val (orch, ctx, _) = setup()
        ctx.readerSync = null
        ctx.currentSec = 90.0
        ctx.reconciledResumeSec = 90.0
        ctx.progressFractionOf = 0.30f

        orch.start(this, ctx)
        orch.cancel()

        orch.stopWithFinalFlush()
        advanceUntilIdle()

        assertEquals(listOf(90.0), ctx.singlePeerWrites)
        assertEquals(listOf(90.0 to 0.30f), ctx.closeFlushes)
    }

    @Test
    fun `stopWithFinalFlush below-floor no writes`() = runTest {
        val (orch, ctx, _) = setup()
        ctx.readerSync = null
        ctx.currentSec = 10.0
        ctx.reconciledResumeSec = 100.0

        orch.start(this, ctx)
        orch.cancel()

        orch.stopWithFinalFlush()
        advanceUntilIdle()

        assertTrue(ctx.singlePeerWrites.isEmpty())
        assertTrue(ctx.closeFlushes.isEmpty())
    }

    @Test
    fun `stopWithFinalFlush no server no-op`() = runTest {
        val (orch, ctx, _) = setup()
        ctx.hasServer = false
        ctx.currentSec = 500.0
        ctx.reconciledResumeSec = 100.0

        orch.start(this, ctx)
        orch.cancel()

        orch.stopWithFinalFlush()
        advanceUntilIdle()

        assertTrue(ctx.singlePeerWrites.isEmpty())
        assertTrue(ctx.closeFlushes.isEmpty())
    }

    @Test
    fun `start is idempotent for same context`() = runTest {
        val (orch, ctx, _) = setup()
        ctx.readerSync = null
        ctx.currentSec = 300.0
        ctx.reconciledResumeSec = 100.0
        ctx.playing = true

        orch.start(this, ctx)
        orch.start(this, ctx)
        advanceTimeBy(FollowLoopOrchestrator.FOLLOW_INTERVAL_MS + 100)
        orch.cancel()

        assertEquals(1, ctx.singlePeerWrites.size, "second start() must not spawn a second tick")
    }

    @Test
    fun `cancel stops the tick without writing`() = runTest {
        val (orch, ctx, _) = setup()
        ctx.readerSync = null
        ctx.currentSec = 300.0
        ctx.reconciledResumeSec = 100.0
        ctx.playing = true

        orch.start(this, ctx)
        orch.cancel()
        advanceTimeBy(FollowLoopOrchestrator.FOLLOW_INTERVAL_MS + 100)

        assertTrue(ctx.singlePeerWrites.isEmpty())
        assertTrue(ctx.closeFlushes.isEmpty(), "cancel does not flush")
    }
}
