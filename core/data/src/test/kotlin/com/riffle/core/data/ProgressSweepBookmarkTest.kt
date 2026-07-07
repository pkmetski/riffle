package com.riffle.core.data

import com.riffle.core.domain.ProgressReconciler
import com.riffle.core.domain.ProgressRemote
import com.riffle.core.domain.RemoteProgress
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceUrl
import com.riffle.core.domain.SyncPositionStore
import com.riffle.core.domain.PositionSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bookmark pass of the durable dirty sweep (ADR 0030, Task 12): bookmarks reconcile on the SAME
 * cadence as positions. Per source (unioned with position-dirty servers), every itemId with at least
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
        override fun ebook(source: Source, token: String, itemId: String): ProgressRemote<String> =
            object : ProgressRemote<String> { override suspend fun get() = RemoteProgress("noop", 0L); override suspend fun patch(position: String) = 0L }
        override fun audio(source: Source, token: String, itemId: String): ProgressRemote<Double> =
            object : ProgressRemote<Double> { override suspend fun get() = RemoteProgress(0.0, 0L); override suspend fun patch(position: Double) = 0L }
    }

    /** Records every reconcile call's full arg tuple. */
    private class RecordingBookmarkReconcile : BookmarkReconcile {
        data class Call(val sourceId: String, val itemId: String, val baseUrl: String, val token: String, val insecure: Boolean)
        val calls = mutableListOf<Call>()
        override suspend fun reconcile(sourceId: String, itemId: String, baseUrl: String, token: String, insecureAllowed: Boolean) {
            calls += Call(sourceId, itemId, baseUrl, token, insecureAllowed)
        }
    }

    private fun source(id: String) =
        Source(id, SourceUrl.parse("http://$id")!!, isActive = false, insecureConnectionAllowed = false, username = "u")

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
        resolver: ServerTokenResolver,
        openTargets: OpenReconcileTargets = OpenReconcileTargets(),
    ) = ProgressSweep(
        positionLedger, resolver,
        ProgressReconciler(NoopStore<String>()), ProgressReconciler(NoopStore<Double>()),
        NoopFactory(), ReconcileLocks(), openTargets,
        bookmarkLedger, bookmarkReconcile,
    )

    @Test
    fun `reconciles a dirty bookmark item with the resolved base url, token and insecure flag`() = runTest {
        val rec = RecordingBookmarkReconcile()
        val resolver = ServerTokenResolver { id ->
            Source(id, SourceUrl.parse("https://$id")!!, isActive = false, insecureConnectionAllowed = true, username = "u") to "tok-$id"
        }

        sweep(
            positionLedger(listOf("s1")),
            bookmarkLedger(listOf("s1"), items = mapOf("s1" to listOf("i1"))),
            rec, resolver,
        ).run()

        assertEquals(1, rec.calls.size)
        assertEquals(
            RecordingBookmarkReconcile.Call("s1", "i1", "https://s1", "tok-s1", true),
            rec.calls.single(),
        )
    }

    @Test
    fun `a source with only dirty bookmarks and no dirty positions is still processed`() = runTest {
        val rec = RecordingBookmarkReconcile()
        val resolver = ServerTokenResolver { id -> source(id) to "tok" }

        sweep(
            positionLedger(emptyList()), // no position-dirty servers at all
            bookmarkLedger(listOf("s9"), items = mapOf("s9" to listOf("i9"))),
            rec, resolver,
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
            rec, ServerTokenResolver { id -> source(id) to "tok" }, openTargets,
        ).run()

        assertEquals(listOf("closed"), rec.calls.map { it.itemId })
        assertTrue(rec.calls.none { it.itemId == "open" })
    }

    @Test
    fun `skips a bookmark-dirty source with no token, leaving it for a later sweep`() = runTest {
        val rec = RecordingBookmarkReconcile()
        val resolver = ServerTokenResolver { id -> if (id == "s2") null else source(id) to "tok" }

        sweep(
            positionLedger(emptyList()),
            bookmarkLedger(listOf("s1", "s2"), items = mapOf("s1" to listOf("i1"), "s2" to listOf("i2"))),
            rec, resolver,
        ).run()

        assertEquals(listOf("s1" to "i1"), rec.calls.map { it.sourceId to it.itemId })
    }
}
