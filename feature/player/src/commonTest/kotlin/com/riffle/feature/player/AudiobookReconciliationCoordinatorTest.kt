package com.riffle.feature.player

import com.riffle.core.domain.PositionSnapshot
import com.riffle.core.domain.ReadaloudResumePosition
import com.riffle.core.domain.ReadaloudResumeStore
import com.riffle.core.domain.SyncPositionStore
import com.riffle.core.sync.OpenReconcileTargets
import com.riffle.feature.reader.AudioLedCycleResult
import com.riffle.feature.reader.AudiobookFollowInterface
import com.riffle.feature.reader.ReaderSyncCoordinatorInterface
import com.riffle.feature.reader.ReaderSyncFactoryInterface
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudiobookReconciliationCoordinatorTest {

    private class FakeReadingSyncStore : SyncPositionStore<String> {
        val mirrors = mutableListOf<MirrorCall<String>>()
        override suspend fun snapshot(sourceId: String, itemId: String) =
            PositionSnapshot<String>(null, 0L, 0L)
        override suspend fun acceptServerPosition(sourceId: String, itemId: String, position: String, serverStamp: Long, ifLocalUpdatedAt: Long, deleted: Boolean) = false
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
        override suspend fun acceptServerPosition(sourceId: String, itemId: String, position: Double, serverStamp: Long, ifLocalUpdatedAt: Long, deleted: Boolean) = false
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

    private class FakeReaderSyncFactory(
        private val syncCoordinator: ReaderSyncCoordinatorInterface? = null,
        private val follow: AudiobookFollowInterface? = null,
    ) : ReaderSyncFactoryInterface {
        override suspend fun createIfApplicable(itemId: String): ReaderSyncCoordinatorInterface? = syncCoordinator
        override suspend fun createAudiobookFollowIfApplicable(itemId: String): AudiobookFollowInterface? = follow
    }

    private class FakeAudiobookFollow(
        override val ebookItemId: String? = null,
        private val locatorBySeconds: Map<Double, String?> = emptyMap(),
        private val anchorBySeconds: Map<Double, ReadaloudResumePosition?> = emptyMap(),
    ) : AudiobookFollowInterface {
        override fun ebookLocatorForAudioSeconds(seconds: Double): String? = locatorBySeconds[seconds]
        override fun readaloudAnchorForAudioSeconds(seconds: Double): ReadaloudResumePosition? = anchorBySeconds[seconds]
    }

    private class FakeReaderSyncCoordinator(
        override val ebookItemId: String? = null,
        private val cycleResults: Map<Pair<Double, Long>, AudioLedCycleResult> = emptyMap(),
    ) : ReaderSyncCoordinatorInterface {
        override suspend fun runAudioLedCycle(currentAudioSec: Double, localUpdatedAt: Long): AudioLedCycleResult =
            cycleResults[currentAudioSec to localUpdatedAt]
                ?: AudioLedCycleResult(jumpToAudioSec = null, canonicalLastUpdate = localUpdatedAt)
        override fun canonicalForAudioSeconds(seconds: Double): String? = null
        override fun readaloudAnchorForAudioSeconds(seconds: Double): ReadaloudResumePosition? = null
    }

    private data class MirrorCall<P>(
        val sourceId: String,
        val itemId: String,
        val position: P,
        val localUpdatedAt: Long,
        val lastSyncedAt: Long,
    )

    private fun coordinator(
        factory: ReaderSyncFactoryInterface = FakeReaderSyncFactory(),
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
    fun `attach with factory returning null fallback follow marked open not attached`() = runTest {
        val follow = FakeAudiobookFollow(ebookItemId = "ebook-x")
        val factory = FakeReaderSyncFactory(syncCoordinator = null, follow = follow)
        val targets = OpenReconcileTargets()
        val (coord, _) = coordinator(factory = factory, targets = targets)

        val result = coord.attach(sourceId = "srv", itemId = "book", atSec = 10.0, atUpdatedAt = 42L)

        assertFalse(result.readerSyncAttached)
        assertNull(result.jumpToAudioSec)
        assertEquals(42L, result.canonicalLastUpdate)
        assertTrue(targets.isOpen("srv", "ebook-x"), "fallback ebook item marked open")
        assertEquals("ebook-x", coord.ebookItemIdForMarkClosed)
    }

    @Test
    fun `attach with factory returning coordinator runs cycle marks ebook open adopts result`() = runTest {
        val rs = FakeReaderSyncCoordinator(
            ebookItemId = "ebook-y",
            cycleResults = mapOf((15.0 to 100L) to AudioLedCycleResult(jumpToAudioSec = 200.0, canonicalLastUpdate = 500L)),
        )
        val factory = FakeReaderSyncFactory(syncCoordinator = rs)
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
        val rs = FakeReaderSyncCoordinator(
            ebookItemId = "ebook-y",
            cycleResults = mapOf(
                (15.0 to 100L) to AudioLedCycleResult(jumpToAudioSec = null, canonicalLastUpdate = 1L),
                (20.0 to 200L) to AudioLedCycleResult(jumpToAudioSec = null, canonicalLastUpdate = 1L),
            ),
        )
        val factory = FakeReaderSyncFactory(syncCoordinator = rs)
        val (coord, _) = coordinator(factory = factory)

        coord.attach("srv", "book", 15.0, 100L)
        val second = coord.attach("srv", "book", 20.0, 200L)

        assertFalse(second.readerSyncAttached, "second attach returns not-newly-attached")
        assertEquals(200L, second.canonicalLastUpdate)
    }

    @Test
    fun `mirrorListeningToReading with no follow no write`() = runTest {
        val (coord, deps) = coordinator()
        coord.mirrorListeningToReading("srv", "book", 50.0)
        assertTrue(deps.second.mirrors.isEmpty())
    }

    @Test
    fun `mirrorListeningToReading with fallback follow writes locator with audio row stamps`() = runTest {
        val follow = FakeAudiobookFollow(
            ebookItemId = "ebook-z",
            locatorBySeconds = mapOf(50.0 to "cfi:/at/50"),
        )
        val factory = FakeReaderSyncFactory(syncCoordinator = null, follow = follow)
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
        val anchor = ReadaloudResumePosition(href = "ch1.xhtml", progression = 0.5, fragmentRef = "ch1#s7")
        val follow = FakeAudiobookFollow(
            ebookItemId = "ebook-w",
            anchorBySeconds = mapOf(75.0 to anchor),
        )
        val factory = FakeReaderSyncFactory(syncCoordinator = null, follow = follow)
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
        val follow = FakeAudiobookFollow(
            ebookItemId = "ebook-w",
            anchorBySeconds = mapOf(50.0 to null),
        )
        val factory = FakeReaderSyncFactory(syncCoordinator = null, follow = follow)
        val (coord, deps) = coordinator(factory = factory)
        coord.attach("srv", "book", 0.0, 0L)

        coord.writeListeningToReadaloud("srv", "book", 50.0)

        assertTrue(deps.third.saves.isEmpty())
    }

    @Test
    fun `ebookItemIdForMarkClosed prefers readerSync over fallback follow`() = runTest {
        val rs = FakeReaderSyncCoordinator(
            ebookItemId = "ebook-from-rs",
            cycleResults = mapOf((0.0 to 0L) to AudioLedCycleResult(jumpToAudioSec = null, canonicalLastUpdate = 0L)),
        )
        val factory = FakeReaderSyncFactory(syncCoordinator = rs)
        val (coord, _) = coordinator(factory = factory)
        coord.attach("srv", "book", 0.0, 0L)

        assertEquals("ebook-from-rs", coord.ebookItemIdForMarkClosed)
    }
}
