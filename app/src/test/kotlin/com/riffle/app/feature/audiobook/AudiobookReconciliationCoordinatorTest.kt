package com.riffle.app.feature.audiobook

import com.riffle.app.feature.reader.AudioLedCycleResult
import com.riffle.app.feature.reader.AudiobookFollow
import com.riffle.app.feature.reader.ReaderSyncCoordinator
import com.riffle.app.feature.reader.ReaderSyncFactory
import com.riffle.core.data.OpenReconcileTargets
import com.riffle.core.domain.PositionSnapshot
import com.riffle.core.domain.ReadaloudResumePosition
import com.riffle.core.domain.ReadaloudResumeStore
import com.riffle.core.domain.SyncPositionStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiobookReconciliationCoordinatorTest {

    private class FakeReadingSyncStore : SyncPositionStore<String> {
        val mirrors = mutableListOf<MirrorCall<String>>()
        override suspend fun snapshot(sourceId: String, itemId: String) =
            PositionSnapshot<String>(null, 0L, 0L)
        override suspend fun acceptServerPosition(sourceId: String, itemId: String, position: String, serverStamp: Long, ifLocalUpdatedAt: Long) = false
        override suspend fun confirmPushed(sourceId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long) = false
        override suspend fun confirmInSync(sourceId: String, itemId: String, ifLocalUpdatedAt: Long) = false
        override suspend fun mirror(sourceId: String, itemId: String, position: String, localUpdatedAt: Long, lastSyncedAt: Long) {
            mirrors += MirrorCall(sourceId, itemId, position, localUpdatedAt, lastSyncedAt)
        }
    }

    private class FakeAudioSyncStore(
        private val snap: PositionSnapshot<Double> = PositionSnapshot(null, 12_345L, 67_890L),
    ) : SyncPositionStore<Double> {
        override suspend fun snapshot(sourceId: String, itemId: String) = snap
        override suspend fun acceptServerPosition(sourceId: String, itemId: String, position: Double, serverStamp: Long, ifLocalUpdatedAt: Long) = false
        override suspend fun confirmPushed(sourceId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long) = false
        override suspend fun confirmInSync(sourceId: String, itemId: String, ifLocalUpdatedAt: Long) = false
        override suspend fun mirror(sourceId: String, itemId: String, position: Double, localUpdatedAt: Long, lastSyncedAt: Long) {}
    }

    private class FakeReadaloudStore : ReadaloudResumeStore {
        val saves = mutableListOf<Triple<String, String, ReadaloudResumePosition>>()
        override suspend fun save(sourceId: String, itemId: String, position: ReadaloudResumePosition) {
            saves += Triple(sourceId, itemId, position)
        }
        override suspend fun load(sourceId: String, itemId: String): ReadaloudResumePosition? = null
        override suspend fun clear(sourceId: String, itemId: String) {}
    }

    private data class MirrorCall<P>(
        val sourceId: String,
        val itemId: String,
        val position: P,
        val localUpdatedAt: Long,
        val lastSyncedAt: Long,
    )

    private fun coordinator(
        factory: ReaderSyncFactory = mockk(relaxed = true),
        targets: OpenReconcileTargets = OpenReconcileTargets(),
        audio: FakeAudioSyncStore = FakeAudioSyncStore(),
        reading: FakeReadingSyncStore = FakeReadingSyncStore(),
        readaloud: FakeReadaloudStore = FakeReadaloudStore(),
    ) = AudiobookReconciliationCoordinator(factory, targets, audio, reading, readaloud) to Triple(audio, reading, readaloud)

    @Test
    fun `attach with empty sourceId short-circuits`() = runTest {
        val (coord, _) = coordinator()
        val result = coord.attach(sourceId = "", itemId = "book", atSec = 10.0, atUpdatedAt = 5L)
        assertFalse(result.readerSyncAttached)
        assertNull(result.jumpToAudioSec)
        assertEquals(5L, result.canonicalLastUpdate)
    }

    @Test
    fun `attach with factory returning null → fallback follow marked open, not attached`() = runTest {
        val factory = mockk<ReaderSyncFactory>()
        val follow = mockk<AudiobookFollow>()
        every { follow.ebookItemId } returns "ebook-x"
        coEvery { factory.createIfApplicable("book") } returns null
        coEvery { factory.createAudiobookFollowIfApplicable("book") } returns follow
        val targets = OpenReconcileTargets()
        val (coord, _) = coordinator(factory = factory, targets = targets)

        val result = coord.attach(sourceId = "srv", itemId = "book", atSec = 10.0, atUpdatedAt = 42L)

        assertFalse(result.readerSyncAttached)
        assertNull(result.jumpToAudioSec)
        assertEquals(42L, result.canonicalLastUpdate)
        assertTrue("fallback ebook item marked open", targets.isOpen("srv", "ebook-x"))
        assertEquals("ebook-x", coord.ebookItemIdForMarkClosed)
    }

    @Test
    fun `attach with factory returning coordinator → runs cycle, marks ebook open, adopts result`() = runTest {
        val factory = mockk<ReaderSyncFactory>()
        val rs = mockk<ReaderSyncCoordinator>(relaxed = true)
        every { rs.ebookItemId } returns "ebook-y"
        coEvery { rs.runAudioLedCycle(15.0, 100L) } returns
            AudioLedCycleResult(jumpToAudioSec = 200.0, canonicalLastUpdate = 500L)
        coEvery { factory.createIfApplicable("book") } returns rs
        val targets = OpenReconcileTargets()
        val (coord, _) = coordinator(factory = factory, targets = targets)

        val result = coord.attach(sourceId = "srv", itemId = "book", atSec = 15.0, atUpdatedAt = 100L)

        assertTrue(result.readerSyncAttached)
        assertEquals(200.0 as Double?, result.jumpToAudioSec)
        assertEquals(500L, result.canonicalLastUpdate)
        assertTrue(targets.isOpen("srv", "ebook-y"))
        assertEquals(rs, coord.readerSync)
        assertEquals("ebook-y", coord.ebookItemIdForMarkClosed)
    }

    @Test
    fun `attach is idempotent once attached`() = runTest {
        val factory = mockk<ReaderSyncFactory>()
        val rs = mockk<ReaderSyncCoordinator>(relaxed = true)
        every { rs.ebookItemId } returns "ebook-y"
        coEvery { rs.runAudioLedCycle(any(), any()) } returns
            AudioLedCycleResult(jumpToAudioSec = null, canonicalLastUpdate = 1L)
        coEvery { factory.createIfApplicable(any()) } returns rs
        val (coord, _) = coordinator(factory = factory)

        coord.attach("srv", "book", 15.0, 100L)
        val second = coord.attach("srv", "book", 20.0, 200L)

        assertFalse("second attach returns not-newly-attached", second.readerSyncAttached)
        assertEquals(200L, second.canonicalLastUpdate)
    }

    @Test
    fun `mirrorListeningToReading with no follow → no write`() = runTest {
        val (coord, deps) = coordinator()
        coord.mirrorListeningToReading("srv", "book", 50.0)
        assertTrue(deps.second.mirrors.isEmpty())
    }

    @Test
    fun `mirrorListeningToReading with fallback follow writes locator with audio row stamps`() = runTest {
        val factory = mockk<ReaderSyncFactory>()
        val follow = mockk<AudiobookFollow>()
        every { follow.ebookItemId } returns "ebook-z"
        coEvery { follow.ebookLocatorForAudioSeconds(50.0) } returns "cfi:/at/50"
        coEvery { factory.createIfApplicable(any()) } returns null
        coEvery { factory.createAudiobookFollowIfApplicable(any()) } returns follow
        val audio = FakeAudioSyncStore(PositionSnapshot(null, 999L, 888L))
        val (coord, deps) = coordinator(factory = factory, audio = audio)
        coord.attach("srv", "book", 0.0, 0L)

        coord.mirrorListeningToReading("srv", "book", 50.0)

        assertEquals(1, deps.second.mirrors.size)
        val m = deps.second.mirrors[0]
        assertEquals("srv", m.sourceId)
        assertEquals("ebook-z", m.itemId)
        assertEquals("cfi:/at/50", m.position)
        assertEquals(999L, m.localUpdatedAt)
        assertEquals(888L, m.lastSyncedAt)
    }

    @Test
    fun `writeListeningToReadaloud persists anchor under ebook item id`() = runTest {
        val factory = mockk<ReaderSyncFactory>()
        val follow = mockk<AudiobookFollow>()
        val anchor = ReadaloudResumePosition(href = "ch1.xhtml", progression = 0.5, fragmentRef = "ch1#s7")
        every { follow.ebookItemId } returns "ebook-w"
        coEvery { follow.readaloudAnchorForAudioSeconds(75.0) } returns anchor
        coEvery { factory.createIfApplicable(any()) } returns null
        coEvery { factory.createAudiobookFollowIfApplicable(any()) } returns follow
        val (coord, deps) = coordinator(factory = factory)
        coord.attach("srv", "book", 0.0, 0L)

        coord.writeListeningToReadaloud("srv", "book", 75.0)

        assertEquals(1, deps.third.saves.size)
        assertEquals("srv", deps.third.saves[0].first)
        assertEquals("ebook-w", deps.third.saves[0].second)
        assertEquals(anchor, deps.third.saves[0].third)
    }

    @Test
    fun `writeListeningToReadaloud with no anchor is a no-op`() = runTest {
        val factory = mockk<ReaderSyncFactory>()
        val follow = mockk<AudiobookFollow>()
        every { follow.ebookItemId } returns "ebook-w"
        coEvery { follow.readaloudAnchorForAudioSeconds(any()) } returns null
        coEvery { factory.createIfApplicable(any()) } returns null
        coEvery { factory.createAudiobookFollowIfApplicable(any()) } returns follow
        val (coord, deps) = coordinator(factory = factory)
        coord.attach("srv", "book", 0.0, 0L)

        coord.writeListeningToReadaloud("srv", "book", 50.0)

        assertTrue(deps.third.saves.isEmpty())
    }

    @Test
    fun `ebookItemIdForMarkClosed prefers readerSync over fallback follow`() = runTest {
        val factory = mockk<ReaderSyncFactory>()
        val rs = mockk<ReaderSyncCoordinator>(relaxed = true)
        every { rs.ebookItemId } returns "ebook-from-rs"
        coEvery { rs.runAudioLedCycle(any(), any()) } returns
            AudioLedCycleResult(jumpToAudioSec = null, canonicalLastUpdate = 0L)
        coEvery { factory.createIfApplicable(any()) } returns rs
        val (coord, _) = coordinator(factory = factory)
        coord.attach("srv", "book", 0.0, 0L)

        assertEquals("ebook-from-rs", coord.ebookItemIdForMarkClosed)
    }
}
