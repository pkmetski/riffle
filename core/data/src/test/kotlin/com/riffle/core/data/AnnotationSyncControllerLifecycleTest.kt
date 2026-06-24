package com.riffle.core.data

import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.AnnotationMergeService
import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.domain.DeviceIdStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM lifecycle tests for [AnnotationSyncController]. Exercises the full
 * read-merge-upsert flow (syncOnOpen), debounced pushes (scheduleDebounce), and the
 * close-flush handshake (syncOnClose) against an in-memory dao and a recording target.
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
    private fun TestScope.newController() = AnnotationSyncController(
        targetProvider = { currentTarget },
        mergeService = AnnotationMergeService(),
        annotationDao = dao,
        deviceIdStore = deviceIdStore,
        scope = this,
    )

    // ===== syncOnOpen =====

    @Test
    fun `syncOnOpen with no target is a no-op (no list, no upsert)`() = runTest {
        currentTarget = null

        newController().syncOnOpen(SRV, ITEM)

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

        newController().syncOnOpen(SRV, ITEM)

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

        newController().syncOnOpen(SRV, ITEM)

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

        newController().syncOnOpen(SRV, ITEM)

        assertEquals(setOf("uuid-ok"), dao.upserts.map { it.id }.toSet())
    }

    @Test
    fun `syncOnOpen swallows a target list failure and leaves the dao untouched`() = runTest {
        target.failNextList = true

        newController().syncOnOpen(SRV, ITEM)

        assertTrue(dao.upserts.isEmpty())
    }

    // ===== scheduleDebounce =====

    @Test
    fun `scheduleDebounce is a no-op when no target is configured`() = runTest {
        currentTarget = null
        dao.localAnnotations += highlightEntity("uuid-local", updatedAt = 100L)

        newController().scheduleDebounce(SRV, ITEM)
        advanceUntilIdle()

        assertTrue("write should not have been called", target.writes.isEmpty())
    }

    @Test
    fun `scheduleDebounce fires the push after the debounce delay`() = runTest {
        dao.localAnnotations += highlightEntity("uuid-local", updatedAt = 100L)
        val controller = newController()

        controller.scheduleDebounce(SRV, ITEM)
        // Before the delay elapses, nothing has been written.
        advanceTimeBy(500L)
        assertTrue(target.writes.isEmpty())

        // After the delay, the push has fired exactly once.
        advanceTimeBy(600L)
        advanceUntilIdle()
        assertEquals(1, target.writes.size)
        assertEquals("annotations-$DEVICE_ID.jsonld", target.writes.first().filename)
    }

    @Test
    fun `scheduleDebounce coalesces rapid edits into a single push`() = runTest {
        dao.localAnnotations += highlightEntity("uuid-1", updatedAt = 100L)
        val controller = newController()

        // Fire three edits in quick succession — each one cancels the previous timer.
        controller.scheduleDebounce(SRV, ITEM)
        advanceTimeBy(300L)
        controller.scheduleDebounce(SRV, ITEM)
        advanceTimeBy(300L)
        controller.scheduleDebounce(SRV, ITEM)
        advanceUntilIdle()

        assertEquals(1, target.writes.size)
    }

    @Test
    fun `scheduleDebounce keeps per-book timers independent of each other`() = runTest {
        dao.localAnnotations += highlightEntity("uuid-a", updatedAt = 100L, itemId = "book-A")
        dao.localAnnotations += highlightEntity("uuid-b", updatedAt = 100L, itemId = "book-B")
        val controller = newController()

        controller.scheduleDebounce(SRV, "book-A")
        controller.scheduleDebounce(SRV, "book-B")
        advanceUntilIdle()

        val items = target.writes.map { it.itemId }.toSet()
        assertEquals(setOf("book-A", "book-B"), items)
    }

    // ===== syncOnClose =====

    @Test
    fun `syncOnClose pushes immediately even if a debounce is pending`() = runTest {
        dao.localAnnotations += highlightEntity("uuid-1", updatedAt = 100L)
        val controller = newController()

        controller.scheduleDebounce(SRV, ITEM)
        // Don't wait for the debounce — close should pre-empt and write right away.
        controller.syncOnClose(SRV, ITEM)
        advanceUntilIdle()

        assertEquals(1, target.writes.size)
    }

    @Test
    fun `syncOnClose with no target is a no-op`() = runTest {
        currentTarget = null

        newController().syncOnClose(SRV, ITEM)
        advanceUntilIdle()

        assertTrue(target.writes.isEmpty())
    }

    @Test
    fun `pushPending writes the device file as a JSON array decodable back to all local entities`() = runTest {
        dao.localAnnotations += highlightEntity("uuid-a", updatedAt = 100L)
        dao.localAnnotations += highlightEntity("uuid-b", updatedAt = 200L)

        newController().syncOnClose(SRV, ITEM)
        advanceUntilIdle()

        val payload = target.writes.single().content
        val parsed = AnnotationW3CCodec.w3cFileToAnnotations(payload)
        assertEquals(setOf("uuid-a", "uuid-b"), parsed.map { it.id }.toSet())
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
    ) = AnnotationEntity(
        id = id, serverId = SRV, itemId = itemId, type = AnnotationEntity.TYPE_HIGHLIGHT,
        cfi = "epubcfi(/6/4!/4/2,/1:0,/1:5)", color = "yellow", note = null,
        textSnippet = "x", textBefore = "", textAfter = "", chapterHref = "c1",
        createdAt = updatedAt, updatedAt = updatedAt,
        originDeviceId = DEVICE_ID, lastModifiedByDeviceId = DEVICE_ID,
    )

    private companion object {
        const val SRV = "srv-1"
        const val ITEM = "item-1"
        const val DEVICE_ID = "dev-test"
    }
}

private class LifecycleRecordingTarget : AnnotationSyncTarget {
    val files: MutableMap<String, String> = mutableMapOf()
    val writes: MutableList<Write> = mutableListOf()
    var listCalls = 0
    var failNextList: Boolean = false

    data class Write(val serverId: String, val itemId: String, val filename: String, val content: String)

    override suspend fun list(serverId: String, itemId: String): List<String> {
        listCalls++
        if (failNextList) {
            failNextList = false
            throw RuntimeException("simulated PROPFIND failure")
        }
        return files.keys.toList()
    }

    override suspend fun read(serverId: String, itemId: String, filename: String): String? = files[filename]

    override suspend fun write(serverId: String, itemId: String, filename: String, content: String) {
        files[filename] = content
        writes += Write(serverId, itemId, filename, content)
    }
}

/**
 * Just-enough AnnotationDao for the controller's needs: getForItem returns the seeded list,
 * upsert collects entities for assertion. All other methods are unused — return defaults.
 */
private class LifecycleInMemoryAnnotationDao : AnnotationDao {
    val localAnnotations: MutableList<AnnotationEntity> = mutableListOf()
    val upserts: MutableList<AnnotationEntity> = mutableListOf()

    override suspend fun getForItem(serverId: String, itemId: String): List<AnnotationEntity> =
        localAnnotations.filter { it.serverId == serverId && it.itemId == itemId && !it.deleted }

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
}
