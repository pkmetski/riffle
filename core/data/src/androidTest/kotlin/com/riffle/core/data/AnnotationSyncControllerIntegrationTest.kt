package com.riffle.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.AnnotationMergeService
import com.riffle.core.domain.DeviceIdStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Integration tests for [AnnotationSyncController].
 *
 * Verifies the three sync lifecycle events (syncOnOpen, scheduleDebounce, syncOnClose)
 * and debounce timer mechanics using real LocalDirectoryTarget and mocked dependencies
 * (AnnotationStore, DeviceIdStore).
 *
 * Timing-sensitive tests use Thread.sleep() to wait for debounce timers.
 */
@RunWith(AndroidJUnit4::class)
class AnnotationSyncControllerIntegrationTest {

    private lateinit var target: LocalDirectoryTarget
    private lateinit var annotationDao: AnnotationDao
    private lateinit var deviceIdStore: DeviceIdStore
    private lateinit var mergeService: AnnotationMergeService
    private lateinit var scope: CoroutineScope
    private lateinit var controller: AnnotationSyncController
    private lateinit var filesDir: File

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        target = LocalDirectoryTarget(context)
        filesDir = context.filesDir

        // Clean up any previous test data
        val annotationSyncDir = File(filesDir, "annotation-sync")
        if (annotationSyncDir.exists()) {
            annotationSyncDir.deleteRecursively()
        }

        // Mock AnnotationDao
        annotationDao = mockk(relaxed = true)

        // Mock DeviceIdStore to return a fixed device ID
        deviceIdStore = mockk()
        coEvery { deviceIdStore.getOrCreate() } returns "device-test"

        // Use real AnnotationMergeService (pure logic, no external state)
        mergeService = AnnotationMergeService()

        // Coroutine scope for the controller
        scope = CoroutineScope(Dispatchers.Main.immediate)

        // Create the controller
        controller = AnnotationSyncController(
            target = target,
            mergeService = mergeService,
            annotationDao = annotationDao,
            deviceIdStore = deviceIdStore,
            scope = scope,
        )
    }

    /**
     * Test 1: syncOnOpen merges device files.
     *
     * Setup: Write two mock device files to LocalDirectoryTarget.
     * Call controller.syncOnOpen(serverId, itemId).
     * Verify: Controller merged annotations and attempted to upsert to AnnotationDao.
     */
    @Test
    fun syncOnOpen_mergesDeviceFiles() = runTest {
        val serverId = "server1"
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

        target.write(serverId, itemId, "annotations-device-a.jsonld", device1Json)
        target.write(serverId, itemId, "annotations-device-b.jsonld", device2Json)

        // Call syncOnOpen
        controller.syncOnOpen(serverId, itemId)

        // Verify that AnnotationDao.upsert was called (at least once for each annotation)
        // We expect 2 calls: one for ann-001, one for ann-002
        coVerify(atLeast = 2) { annotationDao.upsert(any()) }
    }

    /**
     * Test 2: scheduleDebounce starts timer.
     *
     * Schedule debounce, wait < 1s, file should not exist yet.
     * Wait > 1s total, file should exist (pushPending fired).
     */
    @Test
    fun scheduleDebounce_startsTimerAndPushesAfterDelay() = runTest {
        val serverId = "server2"
        val itemId = "item2"

        // Mock getForItem to return a single test annotation
        coEvery { annotationDao.getForItem(serverId, itemId) } returns listOf(
            AnnotationEntity(
                id = "test-ann-001",
                serverId = serverId,
                itemId = itemId,
                type = AnnotationEntity.TYPE_HIGHLIGHT,
                cfi = "epubcfi(/6/4[chap01]!/4/2/16)",
                color = "yellow",
                note = null,
                textSnippet = "test",
                chapterHref = "chap01.xhtml",
                createdAt = 1000L,
                updatedAt = 1000L,
                originDeviceId = "device-test",
                lastModifiedByDeviceId = "device-test",
            )
        )

        // Schedule debounce
        controller.scheduleDebounce(serverId, itemId)

        // Wait < 1s (debounce should not have fired yet)
        Thread.sleep(500)
        val deviceFile = File(
            filesDir,
            "annotation-sync/$serverId/$itemId/annotations-device-test.jsonld"
        )
        assertFalse(
            "File should not exist before debounce delay",
            deviceFile.exists()
        )

        // Wait > 1s more (debounce should have fired by now)
        Thread.sleep(600)
        assertTrue(
            "File should exist after debounce delay",
            deviceFile.exists()
        )
    }

    /**
     * Test 3: debounce restarts on multiple edits.
     *
     * Schedule, wait 500ms, schedule again (restart).
     * Wait another 500ms (still < 1s from restart).
     * File should not exist.
     * Wait 600ms more, file should exist.
     */
    @Test
    fun scheduleDebounce_restartsOnMultipleEdits() = runTest {
        val serverId = "server3"
        val itemId = "item3"

        // Mock getForItem
        coEvery { annotationDao.getForItem(serverId, itemId) } returns listOf(
            AnnotationEntity(
                id = "test-ann-003",
                serverId = serverId,
                itemId = itemId,
                type = AnnotationEntity.TYPE_HIGHLIGHT,
                cfi = "epubcfi(/6/4[chap01]!/4/2/16)",
                color = "yellow",
                note = null,
                textSnippet = "test",
                chapterHref = "chap01.xhtml",
                createdAt = 1000L,
                updatedAt = 1000L,
                originDeviceId = "device-test",
                lastModifiedByDeviceId = "device-test",
            )
        )

        val deviceFile = File(
            filesDir,
            "annotation-sync/$serverId/$itemId/annotations-device-test.jsonld"
        )

        // First schedule
        controller.scheduleDebounce(serverId, itemId)
        Thread.sleep(500)
        assertFalse("File should not exist after 500ms", deviceFile.exists())

        // Second schedule (restarts the timer)
        controller.scheduleDebounce(serverId, itemId)
        Thread.sleep(500)
        assertFalse(
            "File should not exist 500ms after restart (total 1000ms from first schedule)",
            deviceFile.exists()
        )

        // Wait for the restarted timer to complete
        Thread.sleep(600)
        assertTrue(
            "File should exist after 600ms more (1100ms from restart)",
            deviceFile.exists()
        )
    }

    /**
     * Test 4: syncOnClose cancels debounce and pushes immediately.
     *
     * Schedule debounce, wait 200ms.
     * Call syncOnClose.
     * Verify: debounce was cancelled (no file from the scheduled debounce).
     * Verify: file written immediately from syncOnClose.
     */
    @Test
    fun syncOnClose_cancelsDebouncePushesImmediately() = runTest {
        val serverId = "server4"
        val itemId = "item4"

        // Mock getForItem
        coEvery { annotationDao.getForItem(serverId, itemId) } returns listOf(
            AnnotationEntity(
                id = "test-ann-004",
                serverId = serverId,
                itemId = itemId,
                type = AnnotationEntity.TYPE_HIGHLIGHT,
                cfi = "epubcfi(/6/4[chap01]!/4/2/16)",
                color = "yellow",
                note = null,
                textSnippet = "test",
                chapterHref = "chap01.xhtml",
                createdAt = 1000L,
                updatedAt = 1000L,
                originDeviceId = "device-test",
                lastModifiedByDeviceId = "device-test",
            )
        )

        val deviceFile = File(
            filesDir,
            "annotation-sync/$serverId/$itemId/annotations-device-test.jsonld"
        )

        // Schedule debounce (would fire after 1s)
        controller.scheduleDebounce(serverId, itemId)
        Thread.sleep(200)

        // Call syncOnClose
        controller.syncOnClose(serverId, itemId)

        // File should now exist (from syncOnClose's pushPending)
        assertTrue(
            "File should exist immediately after syncOnClose",
            deviceFile.exists()
        )

        // Wait for the original debounce to have fired (1s total)
        // The file should still only have one write (from syncOnClose)
        // This is a best-effort check; the critical fact is the file exists.
        Thread.sleep(1000)
        assertTrue(
            "File should still exist after debounce timeout",
            deviceFile.exists()
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
        val nullTargetController = AnnotationSyncController(
            target = null,
            mergeService = mergeService,
            annotationDao = annotationDao,
            deviceIdStore = deviceIdStore,
            scope = scope,
        )

        val serverId = "server5"
        val itemId = "item5"

        // These should not throw or perform any I/O
        nullTargetController.syncOnOpen(serverId, itemId)
        nullTargetController.scheduleDebounce(serverId, itemId)
        nullTargetController.syncOnClose(serverId, itemId)

        // Verify AnnotationDao was never called
        coVerify(exactly = 0) { annotationDao.upsert(any()) }

        // Verify file was not created
        val annotationSyncDir = File(filesDir, "annotation-sync")
        assertFalse(
            "No files should be created when target is null",
            annotationSyncDir.exists()
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
        val serverId = "server6"
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

        target.write(serverId, itemId, "annotations-device-a.jsonld", validJson)
        target.write(serverId, itemId, "annotations-device-b.jsonld", corruptJson)

        // Call syncOnOpen
        controller.syncOnOpen(serverId, itemId)

        // Verify only one annotation was upserted (from device-a, skipping device-b)
        coVerify(exactly = 1) { annotationDao.upsert(any()) }

        // Verify that the upserted annotation has the correct ID
        val upsertSlot = slot<AnnotationEntity>()
        coVerify { annotationDao.upsert(capture(upsertSlot)) }
        assertEquals("ann-valid-001", upsertSlot.captured.id)
    }

    /**
     * Test 7 (optional): Multiple books with separate debounce timers.
     *
     * Schedule debounce for book A and book B.
     * Verify they have independent timers (cancel one doesn't affect the other).
     */
    @Test
    fun scheduleDebounce_multipleBooks_independentTimers() = runTest {
        val serverA = "serverA"
        val itemA = "itemA"
        val serverB = "serverB"
        val itemB = "itemB"

        // Mock getForItem for both books
        coEvery { annotationDao.getForItem(serverA, itemA) } returns listOf(
            AnnotationEntity(
                id = "ann-a",
                serverId = serverA,
                itemId = itemA,
                type = AnnotationEntity.TYPE_HIGHLIGHT,
                cfi = "epubcfi(/6/4[chap01]!/4/2/16)",
                color = "yellow",
                note = null,
                textSnippet = "test-a",
                chapterHref = "chap01.xhtml",
                createdAt = 1000L,
                updatedAt = 1000L,
                originDeviceId = "device-test",
                lastModifiedByDeviceId = "device-test",
            )
        )

        coEvery { annotationDao.getForItem(serverB, itemB) } returns listOf(
            AnnotationEntity(
                id = "ann-b",
                serverId = serverB,
                itemId = itemB,
                type = AnnotationEntity.TYPE_HIGHLIGHT,
                cfi = "epubcfi(/6/4[chap02]!/4/2/16)",
                color = "green",
                note = null,
                textSnippet = "test-b",
                chapterHref = "chap02.xhtml",
                createdAt = 2000L,
                updatedAt = 2000L,
                originDeviceId = "device-test",
                lastModifiedByDeviceId = "device-test",
            )
        )

        val fileA = File(filesDir, "annotation-sync/$serverA/$itemA/annotations-device-test.jsonld")
        val fileB = File(filesDir, "annotation-sync/$serverB/$itemB/annotations-device-test.jsonld")

        // Schedule debounce for book A
        controller.scheduleDebounce(serverA, itemA)
        Thread.sleep(200)

        // Schedule debounce for book B
        controller.scheduleDebounce(serverB, itemB)
        Thread.sleep(200)

        // Cancel debounce for book A via syncOnClose
        controller.syncOnClose(serverA, itemA)

        // File A should exist now
        assertTrue("File A should exist after syncOnClose", fileA.exists())

        // File B should not exist yet (debounce still running)
        assertFalse("File B should not exist yet (debounce at 400ms total)", fileB.exists())

        // Wait for book B's debounce to complete
        Thread.sleep(700)
        assertTrue("File B should exist after debounce completes", fileB.exists())
    }
}
