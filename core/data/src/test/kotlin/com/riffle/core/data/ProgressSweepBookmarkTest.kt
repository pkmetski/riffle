package com.riffle.core.data

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogFileHandle
import com.riffle.core.catalog.CatalogFileStream
import com.riffle.core.catalog.CatalogHealth
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.CatalogRoot
import com.riffle.core.catalog.SortKey
import com.riffle.core.catalog.FacetSelection
import com.riffle.core.domain.PositionSnapshot
import com.riffle.core.domain.ProgressReconciler
import com.riffle.core.domain.ProgressRemote
import com.riffle.core.domain.RemoteProgress
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceType
import com.riffle.core.domain.SyncPositionStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bookmark pass of the durable dirty sweep (ADR 0030, Task 12): bookmarks reconcile on the SAME
 * cadence as positions. Per source (unioned with position-dirty sources), every itemId with at least
 * one dirty bookmark row reconciles once under its own per-target lock. Exercised over fakes.
 */
class ProgressSweepBookmarkTest {

    private class NoopStore<P> : SyncPositionStore<P> {
        override suspend fun snapshot(sourceId: String, itemId: String) = PositionSnapshot<P>(null, 0L, 0L)
        override suspend fun acceptServerPosition(sourceId: String, itemId: String, position: P, serverStamp: Long, ifLocalUpdatedAt: Long) = false
        override suspend fun confirmPushed(sourceId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long) = false
        override suspend fun confirmInSync(sourceId: String, itemId: String, ifLocalUpdatedAt: Long) = false
        override suspend fun mirror(sourceId: String, itemId: String, position: P, localUpdatedAt: Long, lastSyncedAt: Long) {}
    }

    private class NoopFactory : ProgressRemoteFactory {
        override suspend fun ebook(sourceId: String, itemId: String): ProgressRemote<String> =
            object : ProgressRemote<String> { override suspend fun get() = RemoteProgress("noop", 0L); override suspend fun patch(position: String) = 0L }
        override suspend fun audio(sourceId: String, itemId: String): ProgressRemote<Double> =
            object : ProgressRemote<Double> { override suspend fun get() = RemoteProgress(0.0, 0L); override suspend fun patch(position: Double) = 0L }
    }

    /** Records every reconcile call. */
    private class RecordingBookmarkReconcile : BookmarkReconcile {
        data class Call(val sourceId: String, val itemId: String)
        val calls = mutableListOf<Call>()
        override suspend fun reconcile(sourceId: String, itemId: String) {
            calls += Call(sourceId, itemId)
        }
    }

    private class FakeRegistry(private val available: Set<String>) : CatalogRegistry {
        override suspend fun forActive(): Catalog? = null
        override suspend fun forSource(source: Source): Catalog? = if (source.id in available) DummyCatalog else null
        override suspend fun forSourceId(sourceId: String): Catalog? = if (sourceId in available) DummyCatalog else null
    }

    private object DummyCatalog : Catalog {
        override val sourceType = SourceType.ABS
        override suspend fun listRoots() = emptyList<CatalogRoot>()
        override suspend fun browse(rootId: String, sort: SortKey, page: Int, pageSize: Int, facet: FacetSelection?) = emptyList<CatalogItem>()
        override suspend fun search(rootId: String, query: String, page: Int, pageSize: Int) = emptyList<CatalogItem>()
        override suspend fun getItem(itemId: String): CatalogItem? = null
        override suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle = throw UnsupportedOperationException()
        override suspend fun openFile(itemId: String, format: BookFormat, handleHint: String?): CatalogFileStream = throw UnsupportedOperationException()
        override suspend fun connectivityCheck() = CatalogHealth(isReachable = false)
    }

    private fun positionLedger(servers: List<String>) = object : DirtyProgressLedger {
        override suspend fun serversWithDirty() = servers
        override suspend fun dirtyEbookItems(sourceId: String) = emptyList<String>()
        override suspend fun dirtyAudioItems(sourceId: String) = emptyList<String>()
    }

    private fun bookmarkLedger(
        servers: List<String>,
        items: Map<String, List<String>> = emptyMap(),
    ) = object : DirtyBookmarkLedger {
        override suspend fun serversWithDirty() = servers
        override suspend fun dirtyItems(sourceId: String) = items[sourceId].orEmpty()
    }

    private fun sweep(
        positionLedger: DirtyProgressLedger,
        bookmarkLedger: DirtyBookmarkLedger,
        bookmarkReconcile: BookmarkReconcile,
        registry: CatalogRegistry,
        openTargets: OpenReconcileTargets = OpenReconcileTargets(),
    ) = ProgressSweep(
        positionLedger, registry,
        ProgressReconciler(NoopStore<String>()), ProgressReconciler(NoopStore<Double>()),
        NoopFactory(), ReconcileLocks(), openTargets,
        bookmarkLedger, bookmarkReconcile,
    )

    @Test
    fun `reconciles a dirty bookmark item on a resolvable source`() = runTest {
        val rec = RecordingBookmarkReconcile()

        sweep(
            positionLedger(listOf("s1")),
            bookmarkLedger(listOf("s1"), items = mapOf("s1" to listOf("i1"))),
            rec, FakeRegistry(setOf("s1")),
        ).run()

        assertEquals(1, rec.calls.size)
        assertEquals(RecordingBookmarkReconcile.Call("s1", "i1"), rec.calls.single())
    }

    @Test
    fun `a source with only dirty bookmarks and no dirty positions is still processed`() = runTest {
        val rec = RecordingBookmarkReconcile()

        sweep(
            positionLedger(emptyList()),
            bookmarkLedger(listOf("s9"), items = mapOf("s9" to listOf("i9"))),
            rec, FakeRegistry(setOf("s9")),
        ).run()

        assertEquals(listOf("s9" to "i9"), rec.calls.map { it.sourceId to it.itemId })
    }

    @Test
    fun `skips a bookmark item the live player is currently driving, consistent with the audio pass`() = runTest {
        val rec = RecordingBookmarkReconcile()
        val openTargets = OpenReconcileTargets().apply { markOpen("s1", "open") }

        sweep(
            positionLedger(emptyList()),
            bookmarkLedger(listOf("s1"), items = mapOf("s1" to listOf("open", "closed"))),
            rec, FakeRegistry(setOf("s1")), openTargets,
        ).run()

        assertEquals(listOf("closed"), rec.calls.map { it.itemId })
        assertTrue(rec.calls.none { it.itemId == "open" })
    }

    @Test
    fun `skips a bookmark-dirty source whose Catalog cannot be resolved, leaving it for a later sweep`() = runTest {
        val rec = RecordingBookmarkReconcile()

        sweep(
            positionLedger(emptyList()),
            bookmarkLedger(listOf("s1", "s2"), items = mapOf("s1" to listOf("i1"), "s2" to listOf("i2"))),
            rec, FakeRegistry(setOf("s1")),
        ).run()

        assertEquals(listOf("s1" to "i1"), rec.calls.map { it.sourceId to it.itemId })
    }
}
