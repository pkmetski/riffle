package com.riffle.core.data

import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.AnnotationMergeService
import com.riffle.core.domain.AnnotationSweepEnqueuer
import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.domain.DeviceLabelResolver
import com.riffle.core.domain.NamespaceDeviceListing
import com.riffle.core.domain.NamespaceSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM lifecycle tests for [AnnotationSyncController]. Exercises the full
 * read-merge-upsert flow (syncOnOpen), debounced pushes (scheduleDebounce), and the
 * close-flush handshake (syncOnClose) against an in-memory dao and a recording target.
 *
 * Note: the controller takes a separate `serverId` (DAO key, local per-device) and
 * `namespace` (target key, cross-device-stable). Most lifecycle tests use the constant
 * [NS] and care only that the controller threads the same value through; the dedicated
 * cross-device test below verifies that namespace, not serverId, is what scopes the
 * target so two devices with different serverIds can still find each other's files.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnnotationSyncControllerLifecycleTest {

    private val dao = LifecycleInMemoryAnnotationDao()
    private val target = LifecycleRecordingTarget()
    private val deviceIdStore = object : DeviceIdStore {
        override suspend fun getOrCreate() = DEVICE_ID
    }
    private var currentTarget: AnnotationSyncTarget? = target

    /** Build the controller against the test's scope so debounce delays advance with virtual time. */
    private fun TestScope.newController(
        statusStore: AnnotationSyncStatusStore = AnnotationSyncStatusStore(),
        sweepEnqueuer: AnnotationSweepEnqueuer = RecordingEnqueuer(),
        clock: () -> Long = { 5_000L },
    ) = AnnotationSyncController(
        targetProvider = { currentTarget },
        mergeService = AnnotationMergeService(),
        annotationDao = dao,
        deviceIdStore = deviceIdStore,
        deviceLabelResolver = LifecycleStubLabelResolver,
        scope = this,
        statusStore = statusStore,
        sweepEnqueuer = sweepEnqueuer,
        nowIso = { "2026-01-01T00:00:00Z" },
        clock = clock,
    )

    // ===== syncOnOpen =====

    @Test
    fun `syncOnOpen with no target is a no-op (no list, no upsert)`() = runTest {
        currentTarget = null

        newController().syncOnOpen(SRV, NS, ITEM)

        assertEquals(0, target.listCalls)
        assertTrue(dao.upserts.isEmpty())
    }

    @Test
    fun `syncOnOpen reads each device file, parses the JSON array, and upserts merged annotations`() = runTest {
        target.files["annotations-device-A.jsonld"] = jsonArrayOf(
            w3c("uuid-1", updatedAt = 100L, deviceId = "device-A"),
            w3c("uuid-2", updatedAt = 200L, deviceId = "device-A"),
        )
        target.files["annotations-device-B.jsonld"] = jsonArrayOf(
            w3c("uuid-3", updatedAt = 50L, deviceId = "device-B"),
        )

        newController().syncOnOpen(SRV, NS, ITEM)

        assertEquals(setOf("uuid-1", "uuid-2", "uuid-3"), dao.upserts.map { it.id }.toSet())
        assertTrue(dao.upserts.all { it.serverId == SRV && it.itemId == ITEM })
    }

    @Test
    fun `syncOnOpen merge applies last-write-wins by updatedAt across device files`() = runTest {
        // Same UUID across two devices; B wrote later → B's payload wins.
        target.files["annotations-device-A.jsonld"] = jsonArrayOf(
            w3c("uuid-shared", updatedAt = 100L, deviceId = "device-A", color = "yellow"),
        )
        target.files["annotations-device-B.jsonld"] = jsonArrayOf(
            w3c("uuid-shared", updatedAt = 200L, deviceId = "device-B", color = "green"),
        )

        newController().syncOnOpen(SRV, NS, ITEM)

        val winner = dao.upserts.single { it.id == "uuid-shared" }
        assertEquals("device-B", winner.lastModifiedByDeviceId)
        assertEquals("green", winner.color)
    }

    @Test
    fun `syncOnOpen skips a corrupt file but still merges the well-formed ones`() = runTest {
        target.files["annotations-good.jsonld"] = jsonArrayOf(
            w3c("uuid-ok", updatedAt = 100L, deviceId = "device-A"),
        )
        target.files["annotations-bad.jsonld"] = "this is { not json"

        newController().syncOnOpen(SRV, NS, ITEM)

        assertEquals(setOf("uuid-ok"), dao.upserts.map { it.id }.toSet())
    }

    @Test
    fun `syncOnOpen swallows a target list failure and leaves the dao untouched`() = runTest {
        target.failNextList = true

        newController().syncOnOpen(SRV, NS, ITEM)

        assertTrue(dao.upserts.isEmpty())
    }

    @Test
    fun `syncOnOpen calls target with the namespace and dao with the local serverId`() = runTest {
        // Repro of the cross-device bug: device A pushed annotations under its own per-device
        // serverId-A but the WebDAV namespace is the shared ABS user.id NS. Device B has
        // serverId-B but the SAME NS. When device B opens, the target lookup must use NS (so
        // it sees device A's file), while the DAO scope must use serverId-B (the local row).
        target.files["annotations-device-A.jsonld"] = jsonArrayOf(
            w3c("uuid-from-A", updatedAt = 100L, deviceId = "device-A"),
        )

        newController().syncOnOpen(serverId = "serverId-B", namespace = NS, itemId = ITEM)

        // Target was queried with NS (not serverId-B) — that's how cross-device discovery works.
        assertEquals(listOf(NS), target.listNamespaceArgs)
        assertNotEquals("serverId-B", NS)
        // DAO upsert carries the local serverId-B, not the namespace.
        assertEquals("serverId-B", dao.upserts.single().serverId)
    }

    // ===== scheduleDebounce =====

    @Test
    fun `scheduleDebounce is a no-op when no target is configured`() = runTest {
        currentTarget = null
        dao.localAnnotations += highlightEntity("uuid-local", updatedAt = 100L)

        newController().scheduleDebounce(SRV, NS, ITEM)
        advanceUntilIdle()

        assertTrue("write should not have been called", target.writes.isEmpty())
    }

    @Test
    fun `scheduleDebounce fires the push after the debounce delay`() = runTest {
        dao.localAnnotations += highlightEntity("uuid-local", updatedAt = 100L)
        val controller = newController()

        controller.scheduleDebounce(SRV, NS, ITEM)
        // Before the delay elapses, nothing has been written.
        advanceTimeBy(500L)
        assertTrue(target.writes.isEmpty())

        // After the delay, the push has fired exactly once.
        advanceTimeBy(600L)
        advanceUntilIdle()
        assertEquals(1, target.writes.size)
        assertEquals("annotations-$DEVICE_ID.jsonld", target.writes.first().filename)
        assertEquals(NS, target.writes.first().namespace)
    }

    @Test
    fun `scheduleDebounce coalesces rapid edits into a single push`() = runTest {
        dao.localAnnotations += highlightEntity("uuid-1", updatedAt = 100L)
        val controller = newController()

        // Fire three edits in quick succession — each one cancels the previous timer.
        controller.scheduleDebounce(SRV, NS, ITEM)
        advanceTimeBy(300L)
        controller.scheduleDebounce(SRV, NS, ITEM)
        advanceTimeBy(300L)
        controller.scheduleDebounce(SRV, NS, ITEM)
        advanceUntilIdle()

        assertEquals(1, target.writes.size)
    }

    @Test
    fun `scheduleDebounce keeps per-book timers independent of each other`() = runTest {
        dao.localAnnotations += highlightEntity("uuid-a", updatedAt = 100L, itemId = "book-A")
        dao.localAnnotations += highlightEntity("uuid-b", updatedAt = 100L, itemId = "book-B")
        val controller = newController()

        controller.scheduleDebounce(SRV, NS, "book-A")
        controller.scheduleDebounce(SRV, NS, "book-B")
        advanceUntilIdle()

        val items = target.writes.map { it.itemId }.toSet()
        assertEquals(setOf("book-A", "book-B"), items)
    }

    // ===== syncOnClose =====

    @Test
    fun `syncOnClose pushes immediately even if a debounce is pending`() = runTest {
        dao.localAnnotations += highlightEntity("uuid-1", updatedAt = 100L)
        val controller = newController()

        controller.scheduleDebounce(SRV, NS, ITEM)
        // Don't wait for the debounce — close should pre-empt and write right away.
        controller.syncOnClose(SRV, NS, ITEM)
        advanceUntilIdle()

        assertEquals(1, target.writes.size)
    }

    @Test
    fun `syncOnClose with no target is a no-op`() = runTest {
        currentTarget = null

        newController().syncOnClose(SRV, NS, ITEM)
        advanceUntilIdle()

        assertTrue(target.writes.isEmpty())
    }

    @Test
    fun `pushPending writes the device file as a JSON array decodable back to all local entities`() = runTest {
        dao.localAnnotations += highlightEntity("uuid-a", updatedAt = 100L)
        dao.localAnnotations += highlightEntity("uuid-b", updatedAt = 200L)

        newController().syncOnClose(SRV, NS, ITEM)
        advanceUntilIdle()

        val payload = target.writes.single().content
        val parsed = AnnotationW3CCodec.w3cFileToAnnotations(payload)
        assertEquals(setOf("uuid-a", "uuid-b"), parsed.map { it.id }.toSet())
    }

    @Test
    fun `pushPending success writes the device-meta sentinel under the namespace`() = runTest {
        // Sentinel write is the only way a peer's Maintenance screen ever advances "Last synced"
        // for this device — without it, the cycle is invisible to other devices. The write is
        // best-effort (failure is swallowed), but a successful push MUST emit one.
        dao.localAnnotations += highlightEntity("uuid-a", updatedAt = 100L)

        newController().syncOnClose(SRV, NS, ITEM)
        advanceUntilIdle()

        assertEquals(1, target.deviceMetaWrites.size)
        val sentinel = target.deviceMetaWrites.single()
        assertEquals(NS, sentinel.namespace)
        assertEquals(DEVICE_ID, sentinel.deviceId)
        val parsed = AnnotationDeviceMetaCodec.decode(sentinel.content)!!
        assertEquals(DEVICE_ID, parsed.deviceId)
        assertEquals("test-label", parsed.label)
    }

    @Test
    fun `pushPending failure does NOT write a sentinel`() = runTest {
        // The sentinel announces "this device just synced". A failed push has not synced, so the
        // sentinel must not advance — peers would otherwise show a fresh "Last synced" timestamp
        // for a device that never actually got its content through.
        target.writeException = RuntimeException("simulated push failure")
        dao.localAnnotations += highlightEntity("uuid-a", updatedAt = 100L)

        newController().syncOnClose(SRV, NS, ITEM)
        advanceUntilIdle()

        assertTrue(target.deviceMetaWrites.isEmpty())
    }

    // ===== Code review fixes =====

    @Test
    fun `pushPending includes tombstones so deletes propagate to other devices`() = runTest {
        // A live row plus a tombstoned one — both must reach the file so receivers can LWW-delete.
        dao.localAnnotations += highlightEntity("uuid-live", updatedAt = 100L)
        dao.localAnnotations += highlightEntity("uuid-dead", updatedAt = 200L, deleted = true)

        newController().syncOnClose(SRV, NS, ITEM)
        advanceUntilIdle()

        val payload = target.writes.single().content
        val parsed = AnnotationW3CCodec.w3cFileToAnnotations(payload)
        val byId = parsed.associateBy { it.id }
        assertEquals(2, byId.size)
        assertEquals(false, byId["uuid-live"]?.deleted)
        assertEquals(true, byId["uuid-dead"]?.deleted)
    }

    @Test
    fun `pushPending skips the write entirely when the local set is empty`() = runTest {
        // No local rows at all. Pre-condition: don't overwrite this device's existing remote file
        // with `[]` — a transient local empty (clear-data, mid-migration) would otherwise erase
        // the cloud copy of this device's annotations.
        newController().syncOnClose(SRV, NS, ITEM)
        advanceUntilIdle()

        assertTrue("expected no write when local is empty, got ${target.writes}", target.writes.isEmpty())
    }

    @Test
    fun `syncOnOpen does not clobber a newer local edit with an older remote copy`() = runTest {
        // Local has the user's latest edit at t=200; the remote file (from the previous push) is
        // still at t=100. The merge must keep the local copy.
        dao.localAnnotations += highlightEntity(
            id = "uuid-shared", updatedAt = 200L, lastModifiedByDeviceId = "this-device", color = "green",
        )
        target.files["annotations-this-device.jsonld"] = jsonArrayOf(
            w3c("uuid-shared", updatedAt = 100L, deviceId = "this-device", color = "yellow"),
        )

        newController().syncOnOpen(SRV, NS, ITEM)

        val merged = dao.upserts.single { it.id == "uuid-shared" }
        assertEquals(200L, merged.updatedAt)
        assertEquals("green", merged.color)
    }

    @Test
    fun `syncOnOpen lets a remote tombstone override a live local row`() = runTest {
        // Device A wrote a delete (tombstone at t=200) and pushed it. This device still has the
        // live row at t=100. After syncOnOpen, the merge should keep the tombstone.
        dao.localAnnotations += highlightEntity("uuid-x", updatedAt = 100L)
        target.files["annotations-device-A.jsonld"] = jsonArrayOf(
            w3cTombstone("uuid-x", updatedAt = 200L, deviceId = "device-A"),
        )

        newController().syncOnOpen(SRV, NS, ITEM)

        val merged = dao.upserts.single { it.id == "uuid-x" }
        assertTrue("expected the tombstone to win the merge", merged.deleted)
    }

    // ===== startLiveSync =====

    @Test
    fun `startLiveSync runs a syncOnOpen-equivalent read every interval`() = runTest {
        target.files["annotations-device-A.jsonld"] = jsonArrayOf(
            w3c("uuid-1", updatedAt = 100L, deviceId = "device-A"),
        )
        val controller = newController()

        val job = controller.startLiveSync(SRV, NS, ITEM)

        // Nothing happens before the first interval elapses — the caller is expected to
        // have already done the open-time syncOnOpen separately. NB: across all the
        // live-sync tests, we deliberately do NOT call advanceUntilIdle: the loop schedules
        // a fresh delay() after every tick, so draining "to idle" never terminates and the
        // test body would hit runTest's wall-clock budget. advanceTimeBy runs all tasks
        // scheduled within the advanced window, which is exactly what we want.
        assertEquals(0, target.listCalls)

        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(1, target.listCalls)

        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(2, target.listCalls)

        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(3, target.listCalls)

        job.cancelAndJoin()
    }

    @Test
    fun `startLiveSync stops firing after the returned job is cancelled`() = runTest {
        val controller = newController()

        val job = controller.startLiveSync(SRV, NS, ITEM)
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(1, target.listCalls)

        job.cancelAndJoin()
        advanceTimeBy(120_000L)
        runCurrent()

        assertEquals("no further cycles should run after cancel", 1, target.listCalls)
    }

    @Test
    fun `startLiveSync survives a transient failure and resumes on the next tick`() = runTest {
        // First tick fails (list throws). The loop must not die — the next tick must still fire.
        target.failNextList = true
        val controller = newController()

        val job = controller.startLiveSync(SRV, NS, ITEM)
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(1, target.listCalls)
        // Failure was swallowed inside syncOnOpen — dao untouched.
        assertTrue(dao.upserts.isEmpty())

        // Second tick: list succeeds.
        target.files["annotations-device-A.jsonld"] = jsonArrayOf(
            w3c("uuid-1", updatedAt = 100L, deviceId = "device-A"),
        )
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(2, target.listCalls)
        assertEquals(setOf("uuid-1"), dao.upserts.map { it.id }.toSet())

        job.cancelAndJoin()
    }

    @Test
    fun `startLiveSync skips the per-file merge when this device's file is the only one in the namespace`() = runTest {
        // Only our own device's file is on the target. The cheap PROPFIND should run, but the
        // expensive read+merge pass must NOT — there's nothing to learn by re-reading our own copy.
        target.files["annotations-$DEVICE_ID.jsonld"] = jsonArrayOf(
            w3c("uuid-mine", updatedAt = 100L, deviceId = DEVICE_ID),
        )
        val status = AnnotationSyncStatusStore()
        val controller = newController(statusStore = status)

        val job = controller.startLiveSync(SRV, NS, ITEM)
        advanceTimeBy(30_000L)
        runCurrent()

        assertEquals("PROPFIND should have run", 1, target.listCalls)
        assertTrue("no upserts — solo-device tick is a no-op merge", dao.upserts.isEmpty())
        assertTrue(
            "solo tick still reports Success so the badge can recover",
            status.lastCycleOutcome.value is CycleOutcome.Success,
        )

        job.cancelAndJoin()
    }

    @Test
    fun `startLiveSync skips when the namespace is empty (no files at all yet)`() = runTest {
        // No files anywhere — first tick should not merge.
        val controller = newController()

        val job = controller.startLiveSync(SRV, NS, ITEM)
        advanceTimeBy(30_000L)
        runCurrent()

        assertEquals(1, target.listCalls)
        assertTrue(dao.upserts.isEmpty())

        job.cancelAndJoin()
    }

    @Test
    fun `startLiveSync picks up a peer file that appears mid-session`() = runTest {
        // Tick 1: only our own file → skip merge.
        target.files["annotations-$DEVICE_ID.jsonld"] = jsonArrayOf(
            w3c("uuid-mine", updatedAt = 100L, deviceId = DEVICE_ID),
        )
        val controller = newController()
        val job = controller.startLiveSync(SRV, NS, ITEM)

        advanceTimeBy(30_000L)
        runCurrent()
        assertTrue("tick 1 skipped the merge", dao.upserts.isEmpty())

        // Tick 2: a peer device just pushed its file. Now the merge must fire.
        target.files["annotations-device-B.jsonld"] = jsonArrayOf(
            w3c("uuid-from-B", updatedAt = 200L, deviceId = "device-B"),
        )
        advanceTimeBy(30_000L)
        runCurrent()

        assertEquals(setOf("uuid-mine", "uuid-from-B"), dao.upserts.map { it.id }.toSet())

        job.cancelAndJoin()
    }

    @Test
    fun `startLiveSync solo-tick Success recovers a previously Failed badge state`() = runTest {
        // Tick 1: PROPFIND fails → Failed badge.
        target.failNextList = true
        val status = AnnotationSyncStatusStore()
        val controller = newController(statusStore = status)
        val job = controller.startLiveSync(SRV, NS, ITEM)

        advanceTimeBy(30_000L)
        runCurrent()
        assertTrue(status.lastCycleOutcome.value is CycleOutcome.Failed)

        // Tick 2: namespace is still empty, but the PROPFIND now succeeds. The skip path must
        // report Success so the badge can clear — otherwise solo readers would stay stuck on
        // a transient failure forever.
        advanceTimeBy(30_000L)
        runCurrent()
        assertTrue(
            "skip-tick must report Success after a recovered PROPFIND",
            status.lastCycleOutcome.value is CycleOutcome.Success,
        )

        job.cancelAndJoin()
    }

    @Test
    fun `startLiveSync is a no-op when no target is configured`() = runTest {
        currentTarget = null
        val controller = newController()

        val job = controller.startLiveSync(SRV, NS, ITEM)
        advanceTimeBy(120_000L)
        runCurrent()

        assertEquals(0, target.listCalls)
        job.cancelAndJoin()
    }

    // ===== helpers =====

    private fun jsonArrayOf(vararg jsonObjects: String): String =
        "[\n" + jsonObjects.joinToString(",\n") + "\n]"

    private fun w3c(
        id: String,
        updatedAt: Long,
        deviceId: String,
        color: String = "yellow",
    ): String = AnnotationW3CCodec.annotationEntityToW3C(
        AnnotationEntity(
            id = id,
            serverId = SRV,
            itemId = ITEM,
            type = AnnotationEntity.TYPE_HIGHLIGHT,
            cfi = "epubcfi(/6/4!/4/2,/1:0,/1:5)",
            color = color,
            note = null,
            textSnippet = "x",
            textBefore = "",
            textAfter = "",
            chapterHref = "c1",
            createdAt = updatedAt,
            updatedAt = updatedAt,
            originDeviceId = deviceId,
            lastModifiedByDeviceId = deviceId,
        ),
    )

    private fun highlightEntity(
        id: String,
        updatedAt: Long,
        itemId: String = ITEM,
        deleted: Boolean = false,
        lastModifiedByDeviceId: String = DEVICE_ID,
        color: String = "yellow",
    ) = AnnotationEntity(
        id = id, serverId = SRV, itemId = itemId, type = AnnotationEntity.TYPE_HIGHLIGHT,
        cfi = "epubcfi(/6/4!/4/2,/1:0,/1:5)", color = color, note = null,
        textSnippet = "x", textBefore = "", textAfter = "", chapterHref = "c1",
        createdAt = updatedAt, updatedAt = updatedAt,
        originDeviceId = DEVICE_ID, lastModifiedByDeviceId = lastModifiedByDeviceId,
        deleted = deleted,
    )

    private fun w3cTombstone(id: String, updatedAt: Long, deviceId: String): String =
        AnnotationW3CCodec.annotationEntityToW3C(
            AnnotationEntity(
                id = id, serverId = SRV, itemId = ITEM, type = AnnotationEntity.TYPE_HIGHLIGHT,
                cfi = "epubcfi(/6/4!/4/2,/1:0,/1:5)", color = "yellow", note = null,
                textSnippet = "", textBefore = "", textAfter = "", chapterHref = "c1",
                createdAt = updatedAt, updatedAt = updatedAt,
                originDeviceId = deviceId, lastModifiedByDeviceId = deviceId,
                deleted = true,
            ),
        )

    // ===== Fix 1: syncOnOpen preserves lastSyncedAt on local-wins rows =====

    @Test
    fun `syncOnOpen preserves lastSyncedAt on local-wins rows`() = runTest {
        // Seed Room with one annotation stamped lastSyncedAt = 500 and updatedAt = 1000.
        val local = highlightEntity("uuid-local-win", updatedAt = 1000L)
        // Manually set lastSyncedAt = 500 on the seeded entity.
        dao.localAnnotations += local.copy(lastSyncedAt = 500L)

        // Remote file has the SAME annotation id with updatedAt = 800 (older — local wins LWW).
        target.files["annotations-remote.jsonld"] = jsonArrayOf(
            w3c("uuid-local-win", updatedAt = 800L, deviceId = "remote-device"),
        )

        newController().syncOnOpen(SRV, NS, ITEM)

        // The upserted row must still carry lastSyncedAt = 500, not 0.
        val upserted = dao.upserts.single { it.id == "uuid-local-win" }
        assertEquals("local wins — lastSyncedAt must be preserved", 500L, upserted.lastSyncedAt)
    }

    // ===== stamp + report + enqueue-on-failure =====

    @Test
    fun `pushPending success stamps lastSyncedAt and reports Success`() = runTest {
        dao.localAnnotations += highlightEntity("ann-1", updatedAt = 100L)
        dao.localAnnotations += highlightEntity("ann-2", updatedAt = 200L)
        val status = AnnotationSyncStatusStore()
        val enqueuer = RecordingEnqueuer()

        newController(statusStore = status, sweepEnqueuer = enqueuer, clock = { 5_000L })
            .syncOnClose(SRV, NS, ITEM)
        advanceUntilIdle()

        assertEquals(listOf("ann-1", "ann-2"), dao.lastMarkSyncedIds)
        assertEquals(5_000L, dao.lastMarkSyncedAt)
        assertTrue(status.lastCycleOutcome.value is CycleOutcome.Success)
        assertEquals(0, enqueuer.enqueueCalls)
    }

    @Test
    fun `pushPending failure reports Failed and enqueues a sweep`() = runTest {
        dao.localAnnotations += highlightEntity("ann-1", updatedAt = 100L)
        val status = AnnotationSyncStatusStore()
        val enqueuer = RecordingEnqueuer()
        target.writeException = AnnotationSyncException.NetworkError("offline")

        newController(statusStore = status, sweepEnqueuer = enqueuer, clock = { 7_000L })
            .syncOnClose(SRV, NS, ITEM)
        advanceUntilIdle()

        assertEquals(0, dao.markSyncedCalls)
        val outcome = status.lastCycleOutcome.value
        assertTrue(outcome is CycleOutcome.Failed.Network)
        assertEquals(1, enqueuer.enqueueCalls)
    }

    private class RecordingEnqueuer : AnnotationSweepEnqueuer {
        var enqueueCalls = 0
        override fun enqueue() { enqueueCalls++ }
    }

    private companion object {
        const val SRV = "srv-1"
        const val NS = "abs-user-uuid-shared-across-devices"
        const val ITEM = "item-1"
        const val DEVICE_ID = "dev-test"
    }
}

private class LifecycleRecordingTarget : AnnotationSyncTarget {
    val files: MutableMap<String, String> = mutableMapOf()
    val writes: MutableList<Write> = mutableListOf()
    val deviceMetaWrites: MutableList<DeviceMetaWrite> = mutableListOf()
    val listNamespaceArgs: MutableList<String> = mutableListOf()
    var listCalls = 0
    var failNextList: Boolean = false
    var writeException: Throwable? = null

    data class Write(val namespace: String, val itemId: String, val filename: String, val content: String)
    data class DeviceMetaWrite(val namespace: String, val deviceId: String, val content: String)

    override suspend fun list(namespace: String, itemId: String): List<String> {
        listCalls++
        listNamespaceArgs += namespace
        if (failNextList) {
            failNextList = false
            throw RuntimeException("simulated PROPFIND failure")
        }
        return files.keys.toList()
    }

    override suspend fun read(namespace: String, itemId: String, filename: String): String? = files[filename]

    override suspend fun write(namespace: String, itemId: String, filename: String, content: String) {
        writeException?.let { throw it }
        files[filename] = content
        writes += Write(namespace, itemId, filename, content)
    }

    override suspend fun delete(namespace: String, itemId: String, filename: String) {
        files.remove(filename)
    }
    override suspend fun readDeviceMeta(namespace: String, deviceId: String): String? = null
    override suspend fun writeDeviceMeta(namespace: String, deviceId: String, content: String) {
        deviceMetaWrites += DeviceMetaWrite(namespace, deviceId, content)
    }
    override suspend fun deleteDeviceMeta(namespace: String, deviceId: String) {}
    override suspend fun enumerateDevices(namespace: String) = NamespaceDeviceListing(emptyList())
    override suspend fun enumerateNamespaces(): List<NamespaceSummary> = emptyList()
    override suspend fun forgetNamespace(namespace: String): Int = 0
}

private object LifecycleStubLabelResolver : DeviceLabelResolver {
    override suspend fun resolveLabel(deviceId: String) = "test-label"
    override fun deviceModel() = "test-model"
}

/**
 * Just-enough AnnotationDao for the controller's needs: getForItem returns the seeded list,
 * upsert collects entities for assertion. All other methods are unused — return defaults.
 */
private class LifecycleInMemoryAnnotationDao : AnnotationDao {
    val localAnnotations: MutableList<AnnotationEntity> = mutableListOf()
    val upserts: MutableList<AnnotationEntity> = mutableListOf()
    var lastMarkSyncedIds: List<String> = emptyList()
    var lastMarkSyncedAt: Long = -1L
    var markSyncedCalls: Int = 0

    override suspend fun getForItem(serverId: String, itemId: String): List<AnnotationEntity> =
        localAnnotations.filter { it.serverId == serverId && it.itemId == itemId && !it.deleted }

    override suspend fun getAllForItemIncludingDeleted(serverId: String, itemId: String): List<AnnotationEntity> =
        localAnnotations.filter { it.serverId == serverId && it.itemId == itemId }

    override suspend fun upsert(entity: AnnotationEntity) { upserts += entity }
    override suspend fun upsertAll(annotations: List<AnnotationEntity>) { upserts += annotations }

    override fun observeForItem(serverId: String, itemId: String): Flow<List<AnnotationEntity>> = flowOf(emptyList())
    override fun observeForServer(serverId: String): Flow<List<AnnotationEntity>> = flowOf(emptyList())
    override suspend fun getById(id: String): AnnotationEntity? = null
    override suspend fun getByItemAndCfi(serverId: String, itemId: String, cfi: String): AnnotationEntity? = null
    override suspend fun tombstone(id: String, updatedAt: Long, deviceId: String) = Unit
    override suspend fun recolor(id: String, color: String, updatedAt: Long, deviceId: String) = Unit
    override suspend fun updateNote(id: String, note: String?, updatedAt: Long, deviceId: String) = Unit
    override fun observeAnnotationsByPosition(serverId: String, itemId: String): Flow<List<AnnotationEntity>> = flowOf(emptyList())
    override suspend fun renameBookmark(id: String, title: String, updatedAt: Long, deviceId: String) = Unit
    override fun observePendingCountForBook(serverId: String, itemId: String): Flow<Int> = flowOf(0)
    override fun observePendingCountAcrossAll(): Flow<Int> = flowOf(0)
    override suspend fun dirtyServerItems(): List<AnnotationDao.DirtyServerItem> = emptyList()
    override suspend fun markSynced(ids: List<String>, syncedAt: Long) {
        markSyncedCalls++
        lastMarkSyncedIds = ids
        lastMarkSyncedAt = syncedAt
    }
}
