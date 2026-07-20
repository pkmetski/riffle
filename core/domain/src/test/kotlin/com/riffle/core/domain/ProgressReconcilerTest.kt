package com.riffle.core.domain

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the durable single-target reconcile primitive (ADR 0030). Exercised entirely over
 * in-memory fakes — no Android, Room, or network — so the GET-before-PATCH / last-update-wins /
 * compare-and-clear logic is pinned in isolation.
 */
class ProgressReconcilerTest {

    private val SERVER = "s1"
    private val ITEM = "i1"

    /** A position snapshot + mutable timestamps, with a conditional (compare-and-clear) write model. */
    private class FakeSyncStore<P>(
        var position: P? = null,
        var localUpdatedAt: Long = 0L,
        var lastSyncedAt: Long = 0L,
    ) : SyncPositionStore<P> {
        override suspend fun snapshot(sourceId: String, itemId: String) =
            PositionSnapshot(position, localUpdatedAt, lastSyncedAt)

        override suspend fun acceptServerPosition(
            sourceId: String, itemId: String, position: P, serverStamp: Long, ifLocalUpdatedAt: Long,
        ): Boolean {
            if (localUpdatedAt != ifLocalUpdatedAt) return false
            this.position = position
            localUpdatedAt = serverStamp
            lastSyncedAt = serverStamp
            return true
        }

        override suspend fun confirmPushed(
            sourceId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long,
        ): Boolean {
            if (localUpdatedAt != ifLocalUpdatedAt) return false
            localUpdatedAt = serverStamp
            lastSyncedAt = serverStamp
            return true
        }

        override suspend fun confirmInSync(
            sourceId: String, itemId: String, ifLocalUpdatedAt: Long,
        ): Boolean {
            if (localUpdatedAt != ifLocalUpdatedAt) return false
            lastSyncedAt = localUpdatedAt
            return true
        }

        override suspend fun mirror(
            sourceId: String, itemId: String, position: P, localUpdatedAt: Long, lastSyncedAt: Long,
        ) {
            this.position = position
            this.localUpdatedAt = localUpdatedAt
            this.lastSyncedAt = lastSyncedAt
        }

        val dirty: Boolean get() = localUpdatedAt > lastSyncedAt
    }

    private class FakeRemote<P>(
        private val getResult: RemoteProgress<P>?,
        private val patchStamp: Long? = null,
        private val onGet: (() -> Unit)? = null,
        private val onPatch: (() -> Unit)? = null,
    ) : ProgressRemote<P> {
        var patchedWith: P? = null
        var patchCalls = 0
        override suspend fun get(): RemoteProgress<P>? {
            onGet?.invoke()
            return getResult
        }
        override suspend fun patch(position: P): Long? {
            patchCalls++
            patchedWith = position
            onPatch?.invoke()
            return patchStamp
        }
    }

    @Test
    fun `server newer wins and is persisted locally with the server stamp`() = runTest {
        val store = FakeSyncStore(position = "local-cfi", localUpdatedAt = 100L, lastSyncedAt = 100L)
        val remote = FakeRemote(RemoteProgress("server-cfi", lastUpdate = 200L))

        val outcome = ProgressReconciler(store).reconcile(SERVER, ITEM, remote)

        assertEquals(ReconcileOutcome.ServerWon("server-cfi", 200L), outcome)
        assertEquals("server-cfi", store.position)
        assertEquals(200L, store.localUpdatedAt)
        assertEquals(200L, store.lastSyncedAt)
        assertFalse(store.dirty)
        assertEquals(0, remote.patchCalls)
    }

    @Test
    fun `local newer is pushed and adopts the server-returned stamp`() = runTest {
        val store = FakeSyncStore(position = "local-cfi", localUpdatedAt = 300L, lastSyncedAt = 100L)
        val remote = FakeRemote(RemoteProgress("server-cfi", lastUpdate = 200L), patchStamp = 305L)

        val outcome = ProgressReconciler(store).reconcile(SERVER, ITEM, remote)

        assertEquals(ReconcileOutcome.LocalPushed(305L), outcome)
        assertEquals("local-cfi", remote.patchedWith)
        assertEquals("local-cfi", store.position)
        assertEquals(305L, store.localUpdatedAt)
        assertEquals(305L, store.lastSyncedAt)
        assertFalse(store.dirty)
    }

    @Test
    fun `equal timestamps clear dirty without any network write`() = runTest {
        // localUpdatedAt == server stamp but the row is still marked dirty (a push that landed but
        // whose clean-mark was lost to process death). Reconcile must clear it idempotently.
        val store = FakeSyncStore(position = "cfi", localUpdatedAt = 200L, lastSyncedAt = 100L)
        val remote = FakeRemote(RemoteProgress("cfi", lastUpdate = 200L))

        val outcome = ProgressReconciler(store).reconcile(SERVER, ITEM, remote)

        assertEquals(ReconcileOutcome.InSync, outcome)
        assertEquals(200L, store.lastSyncedAt)
        assertFalse(store.dirty)
        assertEquals(0, remote.patchCalls)
    }

    @Test
    fun `equal timestamps are local-wins ties - the server never forces a pull`() = runTest {
        val store = FakeSyncStore(position = "local-cfi", localUpdatedAt = 200L, lastSyncedAt = 200L)
        val remote = FakeRemote(RemoteProgress("server-cfi", lastUpdate = 200L))

        val outcome = ProgressReconciler(store).reconcile(SERVER, ITEM, remote)

        assertEquals(ReconcileOutcome.InSync, outcome)
        assertEquals("local-cfi", store.position) // not overwritten by the server
    }

    /**
     * #528 device-clock-skew data-loss regression: a CLEAN row (localUpdatedAt == lastSyncedAt)
     * whose local stamp happens to be numerically ABOVE the server's current lastUpdate must
     * NEVER push local to server. Without this guard, the per-item puller invoked from reader-
     * open would treat a stale local locator as authoritative and PATCH it over the server's
     * fresh position saved by another device, silently downgrading the user's real progress.
     * Matches [com.riffle.core.data.ReadingSessionRepositoryImpl.runSyncCycle]'s guard.
     */
    @Test
    fun `clean row with local stamp ABOVE server stamp does NOT push (clock-skew guard)`() = runTest {
        val store = FakeSyncStore(position = "stale-local-cfi", localUpdatedAt = 500L, lastSyncedAt = 500L)
        val remote = FakeRemote(RemoteProgress("server-cfi", lastUpdate = 300L))

        val outcome = ProgressReconciler(store).reconcile(SERVER, ITEM, remote)

        assertEquals(ReconcileOutcome.InSync, outcome)
        assertEquals("stale-local-cfi", store.position) // local NOT overwritten by server
        assertEquals(0, remote.patchCalls) // AND server NOT overwritten by local (the data-loss bug)
        assertFalse(store.dirty)
    }

    /**
     * Complementary case to the clock-skew guard: a CLEAN row whose stamp equals the server's
     * lastSyncedAt but where the server has since ADVANCED (a real cross-device push) must
     * pull the server value. This is the fix for the reader-open flash — the puller relies on
     * it to refresh the position store before the reader loads the initial locator.
     */
    @Test
    fun `clean row pulls when server has advanced since last sync (cross-device push)`() = runTest {
        val store = FakeSyncStore(position = "old-server-cfi", localUpdatedAt = 300L, lastSyncedAt = 300L)
        val remote = FakeRemote(RemoteProgress("new-server-cfi", lastUpdate = 500L))

        val outcome = ProgressReconciler(store).reconcile(SERVER, ITEM, remote)

        assertEquals(ReconcileOutcome.ServerWon("new-server-cfi", 500L), outcome)
        assertEquals("new-server-cfi", store.position)
        assertEquals(500L, store.localUpdatedAt)
        assertEquals(500L, store.lastSyncedAt)
        assertFalse(store.dirty)
        assertEquals(0, remote.patchCalls) // pull only, no push
    }

    @Test
    fun `GET failure is offline - nothing is written and the row stays dirty`() = runTest {
        val store = FakeSyncStore(position = "local-cfi", localUpdatedAt = 300L, lastSyncedAt = 100L)
        val remote = FakeRemote<String>(getResult = null)

        val outcome = ProgressReconciler(store).reconcile(SERVER, ITEM, remote)

        assertEquals(ReconcileOutcome.Offline, outcome)
        assertEquals("local-cfi", store.position)
        assertEquals(300L, store.localUpdatedAt)
        assertEquals(100L, store.lastSyncedAt)
        assertTrue(store.dirty)
        assertEquals(0, remote.patchCalls)
    }

    @Test
    fun `PATCH failure leaves the row dirty and nothing cleared`() = runTest {
        val store = FakeSyncStore(position = "local-cfi", localUpdatedAt = 300L, lastSyncedAt = 100L)
        val remote = FakeRemote(RemoteProgress("server-cfi", lastUpdate = 200L), patchStamp = null)

        val outcome = ProgressReconciler(store).reconcile(SERVER, ITEM, remote)

        assertEquals(ReconcileOutcome.PushFailed, outcome)
        assertEquals("local-cfi", remote.patchedWith) // it tried
        assertEquals(300L, store.localUpdatedAt)
        assertEquals(100L, store.lastSyncedAt)
        assertTrue(store.dirty)
    }

    @Test
    fun `server wins but a concurrent local edit mid-flight supersedes - server position not persisted`() = runTest {
        val store = FakeSyncStore(position = "local-cfi", localUpdatedAt = 100L, lastSyncedAt = 100L)
        // The GET round-trip overlaps a local page turn that bumps localUpdatedAt to 150.
        val remote = FakeRemote(
            RemoteProgress("server-cfi", lastUpdate = 200L),
            onGet = { store.localUpdatedAt = 150L },
        )

        val outcome = ProgressReconciler(store).reconcile(SERVER, ITEM, remote)

        assertEquals(ReconcileOutcome.Superseded, outcome)
        assertEquals("local-cfi", store.position) // fresh local edit NOT clobbered by the server
        assertEquals(150L, store.localUpdatedAt)
        assertTrue(store.dirty) // re-evaluated next sweep
    }

    @Test
    fun `local wins but a concurrent local edit mid-flight supersedes - dirty preserved`() = runTest {
        val store = FakeSyncStore(position = "local-cfi", localUpdatedAt = 300L, lastSyncedAt = 100L)
        // The PATCH round-trip overlaps a local page turn that bumps localUpdatedAt to 350.
        val remote = FakeRemote(
            RemoteProgress("server-cfi", lastUpdate = 200L),
            patchStamp = 305L,
            onPatch = { store.localUpdatedAt = 350L },
        )

        val outcome = ProgressReconciler(store).reconcile(SERVER, ITEM, remote)

        assertEquals(ReconcileOutcome.Superseded, outcome)
        assertEquals("local-cfi", remote.patchedWith)
        assertEquals(350L, store.localUpdatedAt)
        assertEquals(100L, store.lastSyncedAt) // not cleared
        assertTrue(store.dirty)
    }

    @Test
    fun `no local position with a newer server stamp pulls the server`() = runTest {
        val store = FakeSyncStore<String>(position = null, localUpdatedAt = 0L, lastSyncedAt = 0L)
        val remote = FakeRemote(RemoteProgress("server-cfi", lastUpdate = 200L))

        val outcome = ProgressReconciler(store).reconcile(SERVER, ITEM, remote)

        assertEquals(ReconcileOutcome.ServerWon("server-cfi", 200L), outcome)
        assertEquals("server-cfi", store.position)
    }

    @Test
    fun `works over a Double payload (audio seconds)`() = runTest {
        val store = FakeSyncStore(position = 42.0, localUpdatedAt = 300L, lastSyncedAt = 100L)
        val remote = FakeRemote(RemoteProgress(10.0, lastUpdate = 200L), patchStamp = 301L)

        val outcome = ProgressReconciler(store).reconcile(SERVER, ITEM, remote)

        assertEquals(ReconcileOutcome.LocalPushed(301L), outcome)
        assertEquals(42.0, remote.patchedWith)
        assertFalse(store.dirty)
    }

    /**
     * The library grid and detail view read `library_items.readingProgress` / `finishedAt`, not
     * the position stores. Regression pin: after a ServerWon reconcile the UI sink MUST fire with
     * the propagated fraction / finished stamp so the blue bar and % refresh without waiting for
     * the reader to reopen and derive the fraction from the locator on close.
     */
    @Test
    fun `ServerWon invokes the UI sink with the propagated fraction and finishedAt`() = runTest {
        val store = FakeSyncStore(position = "local-cfi", localUpdatedAt = 100L, lastSyncedAt = 100L)
        val remote = FakeRemote(
            RemoteProgress(
                position = "server-cfi",
                lastUpdate = 200L,
                readingProgress = 0.42f,
                finishedAt = 200L,
            ),
        )
        val sinkCalls = mutableListOf<Quad<String, String, Float, Long?>>()
        val sink = UiProgressSink { s, i, p, f -> sinkCalls += Quad(s, i, p, f) }

        val outcome = ProgressReconciler(store, sink).reconcile(SERVER, ITEM, remote)

        assertEquals(ReconcileOutcome.ServerWon("server-cfi", 200L), outcome)
        assertEquals(listOf(Quad(SERVER, ITEM, 0.42f, 200L)), sinkCalls)
    }

    /**
     * The UI sink is the "server-wins mirror". A superseded ServerWon (local edit raced in mid-
     * flight) means the local edit is authoritative — the sink must NOT overwrite the row's
     * fraction/finishedAt with the older server view.
     */
    @Test
    fun `Superseded ServerWon does NOT invoke the UI sink`() = runTest {
        val store = FakeSyncStore(position = "local-cfi", localUpdatedAt = 100L, lastSyncedAt = 100L)
        val remote = FakeRemote(
            RemoteProgress(position = "server-cfi", lastUpdate = 200L, readingProgress = 0.42f),
            onGet = { store.localUpdatedAt = 150L },
        )
        var sinkCalls = 0
        val sink = UiProgressSink { _, _, _, _ -> sinkCalls++ }

        val outcome = ProgressReconciler(store, sink).reconcile(SERVER, ITEM, remote)

        assertEquals(ReconcileOutcome.Superseded, outcome)
        assertEquals(0, sinkCalls)
    }

    /**
     * The other non-ServerWon outcomes (LocalPushed, InSync, Offline, PushFailed) must also skip
     * the UI sink — the sink is strictly "server has newer state, mirror it into UI columns".
     */
    @Test
    fun `LocalPushed InSync Offline PushFailed all skip the UI sink`() = runTest {
        var sinkCalls = 0
        val sink = UiProgressSink { _, _, _, _ -> sinkCalls++ }

        // LocalPushed
        ProgressReconciler(
            FakeSyncStore(position = "local", localUpdatedAt = 300L, lastSyncedAt = 100L),
            sink,
        ).reconcile(SERVER, ITEM, FakeRemote(RemoteProgress("srv", 200L), patchStamp = 305L))

        // InSync (equal stamps)
        ProgressReconciler(
            FakeSyncStore(position = "cfi", localUpdatedAt = 200L, lastSyncedAt = 100L),
            sink,
        ).reconcile(SERVER, ITEM, FakeRemote(RemoteProgress("cfi", 200L)))

        // Offline (GET returned null)
        ProgressReconciler(
            FakeSyncStore(position = "local", localUpdatedAt = 300L, lastSyncedAt = 100L),
            sink,
        ).reconcile(SERVER, ITEM, FakeRemote<String>(getResult = null))

        // PushFailed (patch returned null)
        ProgressReconciler(
            FakeSyncStore(position = "local", localUpdatedAt = 300L, lastSyncedAt = 100L),
            sink,
        ).reconcile(SERVER, ITEM, FakeRemote(RemoteProgress("srv", 200L), patchStamp = null))

        assertEquals(0, sinkCalls)
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
