package com.riffle.core.data

import com.riffle.core.domain.PositionSnapshot
import com.riffle.core.domain.ProgressReconciler
import com.riffle.core.domain.ProgressRemote
import com.riffle.core.domain.RemoteProgress
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceUrl
import com.riffle.core.domain.SyncPositionStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The durable multi-source dirty sweep (ADR 0030 slice 5): enumerate dirty rows across every source,
 * skip servers with no valid token, and reconcile each dirty target once under its per-target lock.
 * Orchestration is exercised over fakes — no Android, Room, or network.
 */
class ProgressSweepTest {

    private class FakeStore<P>(
        val rows: MutableMap<Pair<String, String>, Triple<P?, Long, Long>> = mutableMapOf(),
    ) : SyncPositionStore<P> {
        override suspend fun snapshot(sourceId: String, itemId: String): PositionSnapshot<P> {
            val (p, lu, ls) = rows[sourceId to itemId] ?: Triple(null, 0L, 0L)
            return PositionSnapshot(p, lu, ls)
        }
        override suspend fun acceptServerPosition(sourceId: String, itemId: String, position: P, serverStamp: Long, ifLocalUpdatedAt: Long): Boolean {
            val cur = rows[sourceId to itemId]
            if ((cur?.second ?: 0L) != ifLocalUpdatedAt) return false
            rows[sourceId to itemId] = Triple(position, serverStamp, serverStamp); return true
        }
        override suspend fun confirmPushed(sourceId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long): Boolean {
            val cur = rows[sourceId to itemId] ?: return false
            if (cur.second != ifLocalUpdatedAt) return false
            rows[sourceId to itemId] = cur.copy(second = serverStamp, third = serverStamp); return true
        }
        override suspend fun confirmInSync(sourceId: String, itemId: String, ifLocalUpdatedAt: Long): Boolean {
            val cur = rows[sourceId to itemId] ?: return false
            if (cur.second != ifLocalUpdatedAt) return false
            rows[sourceId to itemId] = cur.copy(third = cur.second); return true
        }
        override suspend fun mirror(sourceId: String, itemId: String, position: P, localUpdatedAt: Long, lastSyncedAt: Long) {
            rows[sourceId to itemId] = Triple(position, localUpdatedAt, lastSyncedAt)
        }
        fun dirty(sourceId: String, itemId: String): Boolean =
            rows[sourceId to itemId]!!.let { it.second > it.third }
        private fun <P> Triple<P?, Long, Long>.copy(second: Long = this.second, third: Long = this.third) =
            Triple(first, second, third)
    }

    private class FakeRemote<P>(private val read: RemoteProgress<P>?, private val stamp: Long?) : ProgressRemote<P> {
        var patched: P? = null
        override suspend fun get() = read
        override suspend fun patch(position: P): Long? { patched = position; return stamp }
    }

    private class RecordingFactory(
        private val ebookRemotes: Map<Pair<String, String>, FakeRemote<String>> = emptyMap(),
        private val audioRemotes: Map<Pair<String, String>, FakeRemote<Double>> = emptyMap(),
    ) : ProgressRemoteFactory {
        val ebookBuilt = mutableListOf<Pair<String, String>>()
        val audioBuilt = mutableListOf<Pair<String, String>>()
        override fun ebook(source: Source, token: String, itemId: String): ProgressRemote<String> {
            ebookBuilt += source.id to itemId
            return ebookRemotes[source.id to itemId] ?: FakeRemote(RemoteProgress("noop", 0L), 0L)
        }
        override fun audio(source: Source, token: String, itemId: String): ProgressRemote<Double> {
            audioBuilt += source.id to itemId
            return audioRemotes[source.id to itemId] ?: FakeRemote(RemoteProgress(0.0, 0L), 0L)
        }
    }

    private fun source(id: String) =
        Source(id, SourceUrl.parse("http://$id")!!, isActive = false, insecureConnectionAllowed = false, username = "u")

    private fun ledger(
        servers: List<String>,
        ebook: Map<String, List<String>> = emptyMap(),
        audio: Map<String, List<String>> = emptyMap(),
    ) = object : DirtyProgressLedger {
        override suspend fun serversWithDirty() = servers
        override suspend fun dirtyEbookItems(sourceId: String) = ebook[sourceId].orEmpty()
        override suspend fun dirtyAudioItems(sourceId: String) = audio[sourceId].orEmpty()
    }

    private fun sweep(
        ledger: DirtyProgressLedger,
        resolver: ServerTokenResolver,
        ebookStore: SyncPositionStore<String>,
        audioStore: SyncPositionStore<Double>,
        factory: ProgressRemoteFactory,
        openTargets: OpenReconcileTargets = OpenReconcileTargets(),
    ) = ProgressSweep(
        ledger, resolver,
        ProgressReconciler(ebookStore), ProgressReconciler(audioStore),
        factory, ReconcileLocks(), openTargets,
        object : DirtyBookmarkLedger {
            override suspend fun serversWithDirty() = emptyList<String>()
            override suspend fun dirtyItems(sourceId: String) = emptyList<String>()
        },
        BookmarkReconcile { _, _, _, _, _ -> },
    )

    @Test
    fun `reconciles dirty ebook rows across multiple servers`() = runTest {
        val store = FakeStore<String>().apply {
            rows["s1" to "i1"] = Triple("local1", 300L, 100L)
            rows["s2" to "i2"] = Triple("local2", 300L, 100L)
        }
        val factory = RecordingFactory(
            ebookRemotes = mapOf(
                ("s1" to "i1") to FakeRemote(RemoteProgress("srv", 200L), stamp = 305L),
                ("s2" to "i2") to FakeRemote(RemoteProgress("srv", 200L), stamp = 305L),
            ),
        )
        val resolver = ServerTokenResolver { id -> source(id) to "tok-$id" }

        sweep(
            ledger(listOf("s1", "s2"), ebook = mapOf("s1" to listOf("i1"), "s2" to listOf("i2"))),
            resolver, store, FakeStore(), factory,
        ).run()

        assertFalse(store.dirty("s1", "i1")) // pushed + cleaned
        assertFalse(store.dirty("s2", "i2"))
    }

    @Test
    fun `skips servers with no token, leaving their rows dirty`() = runTest {
        val store = FakeStore<String>().apply {
            rows["s1" to "i1"] = Triple("local1", 300L, 100L)
            rows["s2" to "i2"] = Triple("local2", 300L, 100L)
        }
        val factory = RecordingFactory(
            ebookRemotes = mapOf(("s1" to "i1") to FakeRemote(RemoteProgress("srv", 200L), stamp = 305L)),
        )
        // s2 has no token → skipped.
        val resolver = ServerTokenResolver { id -> if (id == "s2") null else source(id) to "tok" }

        sweep(
            ledger(listOf("s1", "s2"), ebook = mapOf("s1" to listOf("i1"), "s2" to listOf("i2"))),
            resolver, store, FakeStore(), factory,
        ).run()

        assertFalse(store.dirty("s1", "i1"))
        assertTrue(store.dirty("s2", "i2")) // untouched
        assertFalse("s2 must never be contacted", factory.ebookBuilt.contains("s2" to "i2"))
    }

    @Test
    fun `skips a book a live surface is currently driving, leaving it dirty`() = runTest {
        val store = FakeStore<String>().apply { rows["s1" to "open"] = Triple("local", 300L, 100L) }
        val factory = RecordingFactory(
            ebookRemotes = mapOf(("s1" to "open") to FakeRemote(RemoteProgress("srv", 200L), stamp = 305L)),
        )
        val openTargets = OpenReconcileTargets().apply { markOpen("s1", "open") }

        sweep(
            ledger(listOf("s1"), ebook = mapOf("s1" to listOf("open"))),
            ServerTokenResolver { id -> source(id) to "tok" }, store, FakeStore(), factory, openTargets,
        ).run()

        assertTrue("open book is left to its own live cycle", store.dirty("s1", "open"))
        assertFalse("open book is never contacted by the sweep", factory.ebookBuilt.contains("s1" to "open"))
    }

    @Test
    fun `reconciles both ebook and audio dirty rows for a source`() = runTest {
        val ebookStore = FakeStore<String>().apply { rows["s1" to "i1"] = Triple("local", 300L, 100L) }
        val audioStore = FakeStore<Double>().apply { rows["s1" to "i1"] = Triple(50.0, 300L, 100L) }
        val factory = RecordingFactory(
            ebookRemotes = mapOf(("s1" to "i1") to FakeRemote(RemoteProgress("srv", 200L), 305L)),
            audioRemotes = mapOf(("s1" to "i1") to FakeRemote(RemoteProgress(10.0, 200L), 306L)),
        )

        sweep(
            ledger(listOf("s1"), ebook = mapOf("s1" to listOf("i1")), audio = mapOf("s1" to listOf("i1"))),
            ServerTokenResolver { id -> source(id) to "tok" }, ebookStore, audioStore, factory,
        ).run()

        assertFalse(ebookStore.dirty("s1", "i1"))
        assertFalse(audioStore.dirty("s1", "i1"))
    }

    @Test
    fun `source-wins pulls the newer source position and cleans the row without a reader`() = runTest {
        val store = FakeStore<String>().apply { rows["s1" to "i1"] = Triple("local", 100L, 50L) }
        val factory = RecordingFactory(
            ebookRemotes = mapOf(("s1" to "i1") to FakeRemote(RemoteProgress("source-newer", 500L), stamp = null)),
        )

        sweep(
            ledger(listOf("s1"), ebook = mapOf("s1" to listOf("i1"))),
            ServerTokenResolver { id -> source(id) to "tok" }, store, FakeStore(), factory,
        ).run()

        val row = store.rows["s1" to "i1"]!!
        assertEquals("source-newer", row.first) // persisted locally, no reader needed
        assertFalse(store.dirty("s1", "i1"))
    }
}
