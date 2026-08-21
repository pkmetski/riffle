package com.riffle.core.data

import com.riffle.core.database.AudiobookPositionEntity
import com.riffle.core.database.AudiobookPositionDao
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.ReadingPositionDao
import com.riffle.core.database.ReadingPositionEntity
import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import com.riffle.core.sources.webdav.EnumeratedProgress
import com.riffle.core.sources.webdav.WebDavProgressEnumerator
import com.riffle.core.domain.DefaultDispatcherProvider
import io.ktor.client.HttpClient
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRemoteProgressIndexTest {

    private val webDavConfig = AnnotationSyncConfig("https://dav.test/", "user", "pass")
    private val chitankaSourceId = "chitanka-1"
    private val chitankaSource = Source(
        id = chitankaSourceId,
        url = SourceUrl.parse("https://chitanka.info/")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "",
        type = SourceType.CHITANKA,
    )
    private val absSource = Source(
        id = "abs-1",
        url = SourceUrl.parse("https://abs.example.com/")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "u",
        type = SourceType.ABS,
    )

    private fun configStore(config: AnnotationSyncConfig?) = object : AnnotationSyncConfigStore {
        private val flow = MutableStateFlow(config)
        override fun observe(): StateFlow<AnnotationSyncConfig?> = flow
        override suspend fun save(c: AnnotationSyncConfig) {}
        override suspend fun clear() {}
    }

    private fun sourceRepo(vararg sources: Source) = object : SourceRepository {
        override fun observeAll() = MutableStateFlow(sources.toList())
        override suspend fun getActive() = sources.firstOrNull()
        override suspend fun getById(sourceId: String) = sources.find { it.id == sourceId }
        override suspend fun commit(p: PendingSource, h: Set<String>): CommitSourceResult =
            CommitSourceResult.Failure(RuntimeException())
        override suspend fun setActive(sourceId: String) {}
        override suspend fun remove(sourceId: String) {}
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private fun fakeEnumerator(ebookIds: List<String>, audioIds: List<String> = emptyList()) =
        object : WebDavProgressEnumerator(
            mockk<HttpClient>(relaxed = true),
            DefaultDispatcherProvider,
        ) {
            override suspend fun enumerate(config: AnnotationSyncConfig, namespace: String) =
                EnumeratedProgress(ebookIds, audioIds)
        }

    private fun readingDao(vararg ids: String) = object : ReadingPositionDao {
        private val rows = ids.map { ReadingPositionEntity(chitankaSourceId, it, "", 100L, 100L) }
        override suspend fun upsert(e: ReadingPositionEntity) {}
        override suspend fun getByItemId(s: String, i: String) = rows.find { it.itemId == i }
        override suspend fun updateLocalTimestamp(s: String, i: String, m: Long) {}
        override suspend fun acceptServerIfUnchanged(s: String, i: String, p: String, ss: Long, ila: Long) = 0
        override suspend fun confirmPushedIfUnchanged(s: String, i: String, ss: Long, ila: Long) = 0
        override suspend fun confirmInSyncIfUnchanged(s: String, i: String, ila: Long) = 0
        override suspend fun dirtyForSource(s: String) = emptyList<ReadingPositionEntity>()
        override suspend fun sourcesWithDirtyRows() = emptyList<String>()
        override suspend fun allForSource(s: String) = rows.filter { it.sourceId == s }
    }

    private fun audioDao(vararg ids: String) = object : AudiobookPositionDao {
        private val rows = ids.map { AudiobookPositionEntity(chitankaSourceId, it, 0.0, 100L, 100L) }
        override suspend fun upsert(e: AudiobookPositionEntity) {}
        override suspend fun getByItemId(s: String, i: String) = rows.find { it.itemId == i }
        override suspend fun acceptServerIfUnchanged(s: String, i: String, p: Double, ss: Long, ila: Long) = 0
        override suspend fun confirmPushedIfUnchanged(s: String, i: String, ss: Long, ila: Long) = 0
        override suspend fun confirmInSyncIfUnchanged(s: String, i: String, ila: Long) = 0
        override suspend fun dirtyForSource(s: String) = emptyList<AudiobookPositionEntity>()
        override suspend fun sourcesWithDirtyRows() = emptyList<String>()
        override suspend fun allForSource(s: String) = rows.filter { it.sourceId == s }
    }

    private fun libraryItemDao(vararg ids: String): LibraryItemDao {
        val items = ids.map {
            LibraryItemEntity(chitankaSourceId, it, "lib1", it, "", null, 0f, addedAt = 0L)
        }
        val dao = mockk<LibraryItemDao>(relaxed = true)
        io.mockk.every { dao.observeBySource(any()) } returns MutableStateFlow(items)
        return dao
    }

    private fun makeIndex(
        config: AnnotationSyncConfig? = webDavConfig,
        sources: List<Source> = listOf(chitankaSource),
        ebookSafeIds: List<String> = emptyList(),
        audioSafeIds: List<String> = emptyList(),
        localEbookIds: List<String> = emptyList(),
        localAudioIds: List<String> = emptyList(),
        libraryItemIds: List<String> = emptyList(),
    ) = CatalogRemoteProgressIndex(
        sourceRepository = sourceRepo(*sources.toTypedArray()),
        annotationSyncConfigStore = configStore(config),
        enumerator = fakeEnumerator(ebookSafeIds, audioSafeIds),
        readingPositionDao = readingDao(*localEbookIds.toTypedArray()),
        audiobookPositionDao = audioDao(*localAudioIds.toTypedArray()),
        libraryItemDao = libraryItemDao(*libraryItemIds.toTypedArray()),
    )

    // ── sourcesWithRemote ────────────────────────────────────────────────────

    @Test fun `sourcesWithRemote returns web source IDs when WebDAV is configured`() = runTest {
        val idx = makeIndex()
        assertEquals(listOf(chitankaSourceId), idx.sourcesWithRemote())
    }

    @Test fun `sourcesWithRemote returns empty when WebDAV is not configured`() = runTest {
        val idx = makeIndex(config = null)
        assertTrue(idx.sourcesWithRemote().isEmpty())
    }

    @Test fun `sourcesWithRemote excludes server sources`() = runTest {
        val idx = makeIndex(sources = listOf(absSource, chitankaSource))
        assertEquals(listOf(chitankaSourceId), idx.sourcesWithRemote())
    }

    // ── remoteEbookItems ────────────────────────────────────────────────────

    @Test fun `remoteEbookItems resolves safe IDs to local IDs`() = runTest {
        val idx = makeIndex(
            ebookSafeIds = listOf("book.12073-title"),
            localEbookIds = listOf("book/12073-title"),
        )
        assertEquals(listOf("book/12073-title"), idx.remoteEbookItems(chitankaSourceId))
    }

    @Test fun `remoteEbookItems resolves ID that needs no slash substitution`() = runTest {
        val idx = makeIndex(ebookSafeIds = listOf("84"), localEbookIds = listOf("84"))
        assertEquals(listOf("84"), idx.remoteEbookItems(chitankaSourceId))
    }

    @Test fun `remoteEbookItems resolves safe IDs via library items when no position row exists`() = runTest {
        // Regression: on a new device there is no position row yet, but the library item exists.
        // Progress must be pulled without requiring the book to be opened first.
        val idx = makeIndex(
            ebookSafeIds = listOf("book.12073-title"),
            localEbookIds = emptyList(),
            libraryItemIds = listOf("book/12073-title"),
        )
        assertEquals(listOf("book/12073-title"), idx.remoteEbookItems(chitankaSourceId))
    }

    @Test fun `remoteEbookItems reverses encoding for safe IDs with no local row or library item`() = runTest {
        // No position row, no library item — book never opened on this device.
        // The reversal book.12073-title → book/12073-title must be returned so the reconciler
        // can upsert a position row without requiring the library item to exist first.
        val idx = makeIndex(ebookSafeIds = listOf("book.12073-title"), localEbookIds = emptyList())
        assertEquals(listOf("book/12073-title"), idx.remoteEbookItems(chitankaSourceId))
    }

    @Test fun `remoteEbookItems returns empty when WebDAV is not configured`() = runTest {
        val idx = makeIndex(config = null, ebookSafeIds = listOf("book.1"))
        assertTrue(idx.remoteEbookItems(chitankaSourceId).isEmpty())
    }

    @Test fun `remoteEbookItems returns empty for server source`() = runTest {
        val idx = makeIndex(sources = listOf(absSource))
        assertTrue(idx.remoteEbookItems("abs-1").isEmpty())
    }

    @Test fun `remoteEbookItems includes previously-synced items missing from server`() = runTest {
        // book/12073 was once synced (lastSyncedAt=100>0) but has no file on the server.
        // The reconciler's re-sync branch will push it back; the index must surface it.
        val idx = makeIndex(
            ebookSafeIds = emptyList(),         // PROPFIND returns nothing
            localEbookIds = listOf("book/12073-title"),  // local row with lastSyncedAt=100>0
        )
        assertEquals(listOf("book/12073-title"), idx.remoteEbookItems(chitankaSourceId))
    }

    @Test fun `remoteEbookItems does not duplicate items that are both on server and previously synced`() = runTest {
        val idx = makeIndex(
            ebookSafeIds = listOf("book.12073-title"),
            localEbookIds = listOf("book/12073-title"),
        )
        assertEquals(listOf("book/12073-title"), idx.remoteEbookItems(chitankaSourceId))
    }

    // ── remoteAudioItems ────────────────────────────────────────────────────

    @Test fun `remoteAudioItems resolves audio safe IDs to local IDs`() = runTest {
        val idx = makeIndex(audioSafeIds = listOf("audio.42"), localAudioIds = listOf("audio/42"))
        assertEquals(listOf("audio/42"), idx.remoteAudioItems(chitankaSourceId))
    }

    @Test fun `remoteAudioItems resolves safe IDs via library items when no position row exists`() = runTest {
        val idx = makeIndex(
            audioSafeIds = listOf("audio.1"),
            localAudioIds = emptyList(),
            libraryItemIds = listOf("audio/1"),
        )
        assertEquals(listOf("audio/1"), idx.remoteAudioItems(chitankaSourceId))
    }

    @Test fun `remoteAudioItems reverses encoding for safe IDs with no local row or library item`() = runTest {
        val idx = makeIndex(audioSafeIds = listOf("audio.1"), localAudioIds = emptyList())
        assertEquals(listOf("audio/1"), idx.remoteAudioItems(chitankaSourceId))
    }
}
