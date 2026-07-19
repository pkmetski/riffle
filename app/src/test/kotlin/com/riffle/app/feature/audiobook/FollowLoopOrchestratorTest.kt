package com.riffle.app.feature.audiobook

import com.riffle.app.feature.reader.AudioLedCycleResult
import com.riffle.app.feature.reader.ProgressFlushScope
import com.riffle.app.feature.reader.ReaderSyncCoordinator
import com.riffle.app.testing.TestApplicationScope
import com.riffle.core.common.Clock
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FollowLoopOrchestratorTest {

    private class FakeClock(var ms: Long = 0L) : Clock {
        override fun nowMs(): Long = ms
        override fun nowNs(): Long = ms * 1_000_000L
    }

    private class FakeContext(
        override var reconciledResumeSec: Double = 0.0,
        override var localUpdatedAt: Long = 0L,
        override var readerSync: ReaderSyncCoordinator? = null,
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
    fun `matched tick above floor + playing → advances floor and adopts canonical stamp`() = runTest {
        val (orch, ctx, clock) = setup()
        val rs = mockk<ReaderSyncCoordinator>(relaxed = true)
        coEvery { rs.runAudioLedCycle(any(), any()) } returns
            AudioLedCycleResult(jumpToAudioSec = null, canonicalLastUpdate = 5_000L)
        ctx.readerSync = rs
        ctx.currentSec = 100.0
        ctx.playing = true
        ctx.reconciledResumeSec = 50.0
        clock.ms = 2_000L

        orch.start(this, ctx)
        advanceTimeBy(FollowLoopOrchestrator.FOLLOW_INTERVAL_MS + 100)
        orch.cancel()

        assertEquals(100.0, ctx.reconciledResumeSec, 0.0001)
        assertEquals("canonical stamp adopted", 5_000L, ctx.localUpdatedAt)
        assertEquals(listOf(100.0), ctx.hotAdvances)
        coVerify(exactly = 1) { rs.runAudioLedCycle(100.0, 2_000L) }
    }

    @Test
    fun `matched tick below floor → inbound-only cycle, floor untouched`() = runTest {
        val (orch, ctx, clock) = setup()
        val rs = mockk<ReaderSyncCoordinator>(relaxed = true)
        coEvery { rs.runAudioLedCycle(any(), any()) } returns
            AudioLedCycleResult(jumpToAudioSec = null, canonicalLastUpdate = 42L)
        ctx.readerSync = rs
        ctx.currentSec = 10.0
        ctx.reconciledResumeSec = 100.0
        ctx.playing = true
        clock.ms = 9_000L

        orch.start(this, ctx)
        advanceTimeBy(FollowLoopOrchestrator.FOLLOW_INTERVAL_MS + 100)
        orch.cancel()

        assertEquals("floor untouched below-floor", 100.0, ctx.reconciledResumeSec, 0.0001)
        assertEquals("canonical stamp adopted via max()", 42L, ctx.localUpdatedAt)
        assertTrue("no hot advance below-floor", ctx.hotAdvances.isEmpty())
        coVerify(exactly = 1) { rs.runAudioLedCycle(10.0, 0L) }
    }

    @Test
    fun `inbound jump seeks the player and moves floor to the jump`() = runTest {
        val (orch, ctx, _) = setup()
        val rs = mockk<ReaderSyncCoordinator>(relaxed = true)
        coEvery { rs.runAudioLedCycle(any(), any()) } returns
            AudioLedCycleResult(jumpToAudioSec = 250.0, canonicalLastUpdate = 999L)
        ctx.readerSync = rs
        ctx.currentSec = 10.0
        ctx.reconciledResumeSec = 100.0
        ctx.playing = false // below-floor path handles the inbound jump

        orch.start(this, ctx)
        advanceTimeBy(FollowLoopOrchestrator.FOLLOW_INTERVAL_MS + 100)
        orch.cancel()

        assertEquals(250.0 as Double?, ctx.seekedTo)
        assertEquals(250.0, ctx.reconciledResumeSec, 0.0001)
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
        assertTrue("tick body skipped after successful attach", ctx.hotAdvances.isEmpty())
        assertTrue(ctx.singlePeerWrites.isEmpty())
    }

    @Test
    fun `single-peer tick above floor + playing → writes and advances`() = runTest {
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
        assertEquals(300.0, ctx.reconciledResumeSec, 0.0001)
    }

    @Test
    fun `single-peer tick below floor → no write`() = runTest {
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
        assertEquals(200.0, ctx.reconciledResumeSec, 0.0001)
    }

    @Test
    fun `single-peer tick not playing → no write`() = runTest {
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
        val rs = mockk<ReaderSyncCoordinator>(relaxed = true)
        coEvery { rs.runAudioLedCycle(any(), any()) } returns
            AudioLedCycleResult(jumpToAudioSec = null, canonicalLastUpdate = 7_000L)
        ctx.readerSync = rs
        ctx.currentSec = 150.0
        ctx.reconciledResumeSec = 100.0
        clock.ms = 3_500L
        ctx.progressFractionOf = 0.55f

        // The orchestrator needs a context to have been latched; use start-then-cancel to prime it.
        orch.start(this, ctx)
        orch.cancel()

        orch.stopWithFinalFlush()
        advanceUntilIdle()

        assertEquals(7_000L, ctx.localUpdatedAt)
        assertEquals(listOf(150.0 to 0.55f), ctx.closeFlushes)
        coVerify(exactly = 1) { rs.runAudioLedCycle(150.0, 3_500L) }
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
    fun `stopWithFinalFlush below-floor → no writes`() = runTest {
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
    fun `stopWithFinalFlush no server → no-op`() = runTest {
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

        assertEquals("second start() must not spawn a second tick", 1, ctx.singlePeerWrites.size)
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
        assertTrue("cancel does not flush", ctx.closeFlushes.isEmpty())
    }
}
