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
        override suspend fun snapshot(serverId: String, itemId: String) =
            PositionSnapshot(position, localUpdatedAt, lastSyncedAt)

        override suspend fun acceptServerPosition(
            serverId: String, itemId: String, position: P, serverStamp: Long, ifLocalUpdatedAt: Long,
        ): Boolean {
            if (localUpdatedAt != ifLocalUpdatedAt) return false
            this.position = position
            localUpdatedAt = serverStamp
            lastSyncedAt = serverStamp
            return true
        }

        override suspend fun confirmPushed(
            serverId: String, itemId: String, serverStamp: Long, ifLocalUpdatedAt: Long,
        ): Boolean {
            if (localUpdatedAt != ifLocalUpdatedAt) return false
            localUpdatedAt = serverStamp
            lastSyncedAt = serverStamp
            return true
        }

        override suspend fun confirmInSync(
            serverId: String, itemId: String, ifLocalUpdatedAt: Long,
        ): Boolean {
            if (localUpdatedAt != ifLocalUpdatedAt) return false
            lastSyncedAt = localUpdatedAt
            return true
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
}
