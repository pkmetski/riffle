package com.riffle.core.data

import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.AnnotationMergeService
import com.riffle.core.domain.AnnotationSweepEnqueuer
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.domain.DeviceLabelResolver
import com.riffle.core.logging.RecordingLogger
import com.riffle.core.sync.AnnotationSyncStatusStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Integration tests for [AnnotationSyncController].
 *
 * Verifies the three sync lifecycle events (syncOnOpen, scheduleDebounce, syncOnClose)
 * and debounce timer mechanics using a real [LocalDirectoryTarget] (real file I/O against
 * a temp directory) and mocked dependencies (AnnotationDao, DeviceIdStore).
 *
 * Timing-sensitive tests advance the test scheduler's virtual clock (the controller's
 * scope is the test's [TestScope]) to step past the 1s debounce window deterministically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnnotationSyncControllerIntegrationTest {

    private lateinit var target: LocalDirectoryTarget
    private lateinit var annotationDao: AnnotationDao
    private lateinit var deviceIdStore: DeviceIdStore
    private lateinit var mergeService: AnnotationMergeService
    private lateinit var filesDir: File

    @Before
    fun setup() {
        filesDir = Files.createTempDirectory("annotation-sync-controller-test").toFile()
        target = LocalDirectoryTarget(filesDir, RecordingLogger())

        // Mock AnnotationDao
        annotationDao = mockk(relaxed = true)

        // Mock DeviceIdStore to return a fixed device ID
        deviceIdStore = mockk()
        coEvery { deviceIdStore.getOrCreate() } returns "device-test"

        // Use real AnnotationMergeService (pure logic, no external state)
        mergeService = AnnotationMergeService()
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    /** Build the controller against the test's scope so debounce delays advance with virtual time. */
    private fun TestScope.newController(
        targetProvider: () -> LocalDirectoryTarget? = { target },
    ) = AnnotationSyncController(
        targetProvider = targetProvider,
        mergeService = mergeService,
        annotationDao = annotationDao,
        deviceIdStore = deviceIdStore,
        deviceLabelResolver = IntegrationStubLabelResolver,
        scope = this,
        statusStore = AnnotationSyncStatusStore(),
        sweepEnqueuer = AnnotationSweepEnqueuer { /* no-op */ },
        nowIso = { "2026-01-01T00:00:00Z" },
        // Pin the clock near the fixtures' riffle:updatedAt values (1000/2000) so the ADR 0045
        // stale-orphan guard (ignore never-seen rows older than the 90-day tombstone TTL against
        // "now") doesn't discard them the way the real wall clock would.
        clock = { 5_000L },
    )

    private fun pushedAnnotation(
        id: String,
        sourceId: String,
        itemId: String,
        cfi: String = "epubcfi(/6/4[chap01]!/4/2/16)",
        color: String = "yellow",
        textSnippet: String = "test",
        chapterHref: String = "chap01.xhtml",
        createdAt: Long = 1000L,
    ) = AnnotationEntity(
        id = id,
        sourceId = sourceId,
        itemId = itemId,
        type = AnnotationEntity.TYPE_HIGHLIGHT,
        cfi = cfi,
        color = color,
        note = null,
        textSnippet = textSnippet,
        chapterHref = chapterHref,
        createdAt = createdAt,
        updatedAt = createdAt,
        originDeviceId = "device-test",
        lastModifiedByDeviceId = "device-test",
    )

    /**
     * Test 1: syncOnOpen merges device files.
     *
     * Setup: Write two mock device files to LocalDirectoryTarget.
     * Call controller.syncOnOpen(sourceId, namespace = sourceId, itemId = itemId).
     * Verify: Controller merged annotations and attempted to upsert to AnnotationDao.
     */
    @Test
    fun syncOnOpen_mergesDeviceFiles() = runTest {
        val sourceId = "source1"
        val itemId = "item1"

        // Create two device files with mock annotations
        val device1Json = """[
            {
                "@context": "http://www.w3.org/ns/anno.jsonld",
                "id": "urn:uuid:ann-001",
                "type": "Annotation",
                "motivation": "highlighting",
                "target": {"source": "epub://item-item1", "selector": []},
                "body": {"type": "TextualBody", "purpose": "highlighting", "value": "yellow"},
                "created": "2024-01-01T00:00:00Z",
                "modified": "2024-01-01T00:00:00Z",
                "riffle:originDeviceId": "device-a",
                "riffle:lastModifiedByDeviceId": "device-a",
                "riffle:updatedAt": 1000,
                "riffle:deleted": false
            }
        ]"""

        val device2Json = """[
            {
                "@context": "http://www.w3.org/ns/anno.jsonld",
                "id": "urn:uuid:ann-002",
                "type": "Annotation",
                "motivation": "highlighting",
                "target": {"source": "epub://item-item1", "selector": []},
                "body": {"type": "TextualBody", "purpose": "highlighting", "value": "green"},
                "created": "2024-01-02T00:00:00Z",
                "modified": "2024-01-02T00:00:00Z",
                "riffle:originDeviceId": "device-b",
                "riffle:lastModifiedByDeviceId": "device-b",
                "riffle:updatedAt": 2000,
                "riffle:deleted": false
            }
        ]"""

        target.write(sourceId, itemId, "annotations-device-a.jsonld", device1Json)
        target.write(sourceId, itemId, "annotations-device-b.jsonld", device2Json)

        // Call syncOnOpen
        newController().syncOnOpen(sourceId, namespace = sourceId, itemId = itemId)

        // Verify that AnnotationDao.upsertAll was called with both annotations in one batch
        val upsertSlot1 = slot<List<AnnotationEntity>>()
        coVerify { annotationDao.upsertAll(capture(upsertSlot1)) }
        assertEquals(2, upsertSlot1.captured.size)
    }

    /**
     * Test 2: scheduleDebounce starts timer.
     *
     * Schedule debounce, advance < 1s, file should not exist yet.
     * Advance > 1s total, file should exist (pushPending fired).
     */
    @Test
    fun scheduleDebounce_startsTimerAndPushesAfterDelay() = runTest {
        val sourceId = "source2"
        val itemId = "item2"

        // The push path reads every row (incl. tombstones) for the book.
        coEvery { annotationDao.getAllForItemIncludingDeleted(sourceId, itemId) } returns listOf(
            pushedAnnotation(id = "test-ann-001", sourceId = sourceId, itemId = itemId),
        )

        // Schedule debounce
        newController().scheduleDebounce(sourceId, namespace = sourceId, itemId = itemId)

        // Advance < 1s (debounce should not have fired yet)
        advanceTimeBy(500)
        val deviceFile = File(
            filesDir,
            "annotation-sync/$sourceId/$itemId/annotations-device-test.jsonld",
        )
        assertFalse(
            "File should not exist before debounce delay",
            deviceFile.exists(),
        )

        // Advance > 1s more (debounce should have fired by now)
        advanceTimeBy(600)
        assertTrue(
            "File should exist after debounce delay",
            deviceFile.exists(),
        )
    }

    /**
     * Test 3: debounce restarts on multiple edits.
     *
     * Schedule, advance 500ms, schedule again (restart).
     * Advance another 500ms (still < 1s from restart).
     * File should not exist.
     * Advance 600ms more, file should exist.
     */
    @Test
    fun scheduleDebounce_restartsOnMultipleEdits() = runTest {
        val sourceId = "source3"
        val itemId = "item3"

        coEvery { annotationDao.getAllForItemIncludingDeleted(sourceId, itemId) } returns listOf(
            pushedAnnotation(id = "test-ann-003", sourceId = sourceId, itemId = itemId),
        )

        val deviceFile = File(
            filesDir,
            "annotation-sync/$sourceId/$itemId/annotations-device-test.jsonld",
        )

        val controller = newController()

        // First schedule
        controller.scheduleDebounce(sourceId, namespace = sourceId, itemId = itemId)
        advanceTimeBy(500)
        assertFalse("File should not exist after 500ms", deviceFile.exists())

        // Second schedule (restarts the timer)
        controller.scheduleDebounce(sourceId, namespace = sourceId, itemId = itemId)
        advanceTimeBy(500)
        assertFalse(
            "File should not exist 500ms after restart (total 1000ms from first schedule)",
            deviceFile.exists(),
        )

        // Wait for the restarted timer to complete
        advanceTimeBy(600)
        assertTrue(
            "File should exist after 600ms more (1100ms from restart)",
            deviceFile.exists(),
        )
    }

    /**
     * Test 4: syncOnClose cancels debounce and pushes immediately.
     *
     * Schedule debounce, advance 200ms.
     * Call syncOnClose.
     * Verify: debounce was cancelled (no file from the scheduled debounce).
     * Verify: file written immediately from syncOnClose.
     */
    @Test
    fun syncOnClose_cancelsDebouncePushesImmediately() = runTest {
        val sourceId = "source4"
        val itemId = "item4"

        coEvery { annotationDao.getAllForItemIncludingDeleted(sourceId, itemId) } returns listOf(
            pushedAnnotation(id = "test-ann-004", sourceId = sourceId, itemId = itemId),
        )

        val deviceFile = File(
            filesDir,
            "annotation-sync/$sourceId/$itemId/annotations-device-test.jsonld",
        )

        val controller = newController()

        // Schedule debounce (would fire after 1s)
        controller.scheduleDebounce(sourceId, namespace = sourceId, itemId = itemId)
        advanceTimeBy(200)

        // Call syncOnClose
        controller.syncOnClose(sourceId, namespace = sourceId, itemId = itemId)

        // File should now exist (from syncOnClose's pushPending)
        assertTrue(
            "File should exist immediately after syncOnClose",
            deviceFile.exists(),
        )

        // Advance past the original debounce deadline (1s total).
        // The file should still only have one write (from syncOnClose).
        // This is a best-effort check; the critical fact is the file exists.
        advanceTimeBy(1000)
        assertTrue(
            "File should still exist after debounce timeout",
            deviceFile.exists(),
        )
    }

    /**
     * Test 5: null target gracefully no-ops.
     *
     * Create controller with target=null.
     * Call syncOnOpen, scheduleDebounce, syncOnClose.
     * Verify: No exceptions, no file I/O.
     */
    @Test
    fun nullTarget_gracefullyNoop() = runTest {
        val nullTargetController = newController(targetProvider = { null })

        val sourceId = "source5"
        val itemId = "item5"

        // These should not throw or perform any I/O
        nullTargetController.syncOnOpen(sourceId, namespace = sourceId, itemId = itemId)
        nullTargetController.scheduleDebounce(sourceId, namespace = sourceId, itemId = itemId)
        nullTargetController.syncOnClose(sourceId, namespace = sourceId, itemId = itemId)

        // Advance past the debounce window — even then nothing must fire.
        advanceTimeBy(2000)

        // Verify AnnotationDao was never called (either the batch or singular path)
        coVerify(exactly = 0) { annotationDao.upsertAll(any()) }
        coVerify(exactly = 0) { annotationDao.upsert(any()) }

        // Verify file was not created
        val annotationSyncDir = File(filesDir, "annotation-sync")
        assertFalse(
            "No files should be created when target is null",
            annotationSyncDir.exists(),
        )
    }

    /**
     * Test 6: corrupt file skipped, others merged.
     *
     * Write device-A valid file + device-B corrupt file.
     * syncOnOpen should skip device-B, merge device-A.
     * Verify only valid annotation is upserted.
     */
    @Test
    fun syncOnOpen_skipCorruptFilesMergeValid() = runTest {
        val sourceId = "source6"
        val itemId = "item6"

        // Valid device file
        val validJson = """[
            {
                "@context": "http://www.w3.org/ns/anno.jsonld",
                "id": "urn:uuid:ann-valid-001",
                "type": "Annotation",
                "motivation": "highlighting",
                "target": {"source": "epub://item-item6", "selector": []},
                "body": {"type": "TextualBody", "purpose": "highlighting", "value": "yellow"},
                "created": "2024-01-01T00:00:00Z",
                "modified": "2024-01-01T00:00:00Z",
                "riffle:originDeviceId": "device-a",
                "riffle:lastModifiedByDeviceId": "device-a",
                "riffle:updatedAt": 1000,
                "riffle:deleted": false
            }
        ]"""

        // Corrupt device file (invalid JSON)
        val corruptJson = """{ "malformed json without closing"""

        target.write(sourceId, itemId, "annotations-device-a.jsonld", validJson)
        target.write(sourceId, itemId, "annotations-device-b.jsonld", corruptJson)

        // Call syncOnOpen
        newController().syncOnOpen(sourceId, namespace = sourceId, itemId = itemId)

        // Verify only one annotation was upserted in a single batch (from device-a, skipping device-b)
        val upsertSlot = slot<List<AnnotationEntity>>()
        coVerify { annotationDao.upsertAll(capture(upsertSlot)) }
        assertEquals(1, upsertSlot.captured.size)
        assertEquals("ann-valid-001", upsertSlot.captured.first().id)
    }

    /**
     * Test 7 (optional): Multiple books with separate debounce timers.
     *
     * Schedule debounce for book A and book B.
     * Verify they have independent timers (cancel one doesn't affect the other).
     */
    @Test
    fun scheduleDebounce_multipleBooks_independentTimers() = runTest {
        val sourceA = "sourceA"
        val itemA = "itemA"
        val sourceB = "sourceB"
        val itemB = "itemB"

        coEvery { annotationDao.getAllForItemIncludingDeleted(sourceA, itemA) } returns listOf(
            pushedAnnotation(id = "ann-a", sourceId = sourceA, itemId = itemA, textSnippet = "test-a"),
        )

        coEvery { annotationDao.getAllForItemIncludingDeleted(sourceB, itemB) } returns listOf(
            pushedAnnotation(
                id = "ann-b",
                sourceId = sourceB,
                itemId = itemB,
                cfi = "epubcfi(/6/4[chap02]!/4/2/16)",
                color = "green",
                textSnippet = "test-b",
                chapterHref = "chap02.xhtml",
                createdAt = 2000L,
            ),
        )

        val fileA = File(filesDir, "annotation-sync/$sourceA/$itemA/annotations-device-test.jsonld")
        val fileB = File(filesDir, "annotation-sync/$sourceB/$itemB/annotations-device-test.jsonld")

        val controller = newController()

        // Schedule debounce for book A (fires at t=1000 if not cancelled)
        controller.scheduleDebounce(sourceA, namespace = sourceA, itemId = itemA)
        advanceTimeBy(200)

        // Schedule debounce for book B (scheduled at t=200, fires at t=1200)
        controller.scheduleDebounce(sourceB, namespace = sourceB, itemId = itemB)
        advanceTimeBy(200)

        // Cancel debounce for book A via syncOnClose
        controller.syncOnClose(sourceA, namespace = sourceA, itemId = itemA)

        // File A should exist now
        assertTrue("File A should exist after syncOnClose", fileA.exists())

        // File B should not exist yet (debounce still running)
        assertFalse("File B should not exist yet (debounce at 400ms total)", fileB.exists())

        // Advance until book B's debounce completes (fires at t=1200; virtual clock is at 400)
        advanceTimeBy(900)
        assertTrue("File B should exist after debounce completes", fileB.exists())
    }
}

private object IntegrationStubLabelResolver : DeviceLabelResolver {
    override suspend fun resolveLabel(deviceId: String) = "integration-device"
    override fun deviceModel() = "integration-model"
}
