package com.riffle.core.data

import com.riffle.core.catalog.AudiobookProgressPeerCapability
import com.riffle.core.catalog.CatalogProgress
import com.riffle.core.catalog.CfiDialect
import com.riffle.core.catalog.ProgressPeerCapability
import com.riffle.core.common.Clock
import com.riffle.core.domain.EbookCfiTranslator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Catalog-backed [com.riffle.core.domain.ProgressRemote] adapters (ADR 0036 / ADR 0013): translate
 * ABS `epubcfi(...)` ↔ Riffle Locator JSON at the Catalog boundary so the local store is never
 * polluted with a foreign format. A null translator defers (returns null) — row stays dirty for
 * the next sweep once the EPUB is cached. [CfiDialect.PAGE_NUMBER] short-circuits the translator
 * so page-based peers (Komga, #528) don't need CFI plumbing.
 */
class CatalogProgressRemoteFactoryTest {

    private class FakePeer(
        var progress: CatalogProgress? = null,
        var failGet: Boolean = false,
        var failPush: Boolean = false,
        override val cfiDialect: CfiDialect = CfiDialect.EPUB_JS,
    ) : ProgressPeerCapability, AudiobookProgressPeerCapability {
        data class Ebook(val itemId: String, val location: String, val progress: Float, val isFinished: Boolean?, val ts: Long)
        data class Audio(val itemId: String, val currentTimeSec: Double, val durationSec: Double, val isFinished: Boolean?, val ts: Long)
        var lastEbook: Ebook? = null
        var lastAudio: Audio? = null

        override suspend fun pushEbookProgress(
            itemId: String, location: String, progress: Float, isFinished: Boolean?, lastUpdateEpochMs: Long,
        ): Long? {
            if (failPush) throw RuntimeException("down")
            lastEbook = Ebook(itemId, location, progress, isFinished, lastUpdateEpochMs)
            return null
        }

        override suspend fun pushAudiobookProgress(
            itemId: String, currentTimeSec: Double, durationSec: Double, isFinished: Boolean?, lastUpdateEpochMs: Long,
        ): Long? {
            if (failPush) throw RuntimeException("down")
            lastAudio = Audio(itemId, currentTimeSec, durationSec, isFinished, lastUpdateEpochMs)
            return null
        }

        override suspend fun pullProgress(itemId: String): CatalogProgress? {
            if (failGet) throw RuntimeException("down")
            return progress
        }

        override suspend fun pullAllProgress(): List<CatalogProgress> = emptyList()
    }

    private class FakeTranslator(
        private val cfiResult: suspend (String) -> String?,
        private val locatorResult: suspend (String) -> String?,
    ) : EbookCfiTranslator {
        override suspend fun cfiToLocatorJson(epubcfi: String) = cfiResult(epubcfi)
        override suspend fun locatorJsonToCfi(locatorJson: String) = locatorResult(locatorJson)
    }

    private val clock = object : Clock { override fun nowMs() = 1800L; override fun nowNs() = 0L }
    private val locatorJson = """{"href":"OPS/ch1.xhtml","type":"application/xhtml+xml","locations":{"progression":0.5}}"""

    private fun ebookRemote(peer: ProgressPeerCapability, translator: EbookCfiTranslator?, progress: Float = 0.5f) =
        CatalogEbookProgressRemote(peer, "item-1", translator, { progress }, clock)

    private fun audioRemote(peer: FakePeer, duration: Double = 3600.0) =
        CatalogAudioProgressRemote(peer = peer, itemId = "item-1", duration = { duration }, clock = clock)

    // --- ebook get ---

    @Test
    fun `ebook get - null translator returns null (EPUB not cached, defers row)`() = runTest {
        val peer = FakePeer(progress = CatalogProgress("item-1", ebookLocation = "epubcfi(/6/4!/4)", lastUpdate = 1700L))
        assertNull(ebookRemote(peer, translator = null).get())
    }

    @Test
    fun `ebook get - translates epubcfi to Locator JSON and preserves lastUpdate`() = runTest {
        val peer = FakePeer(progress = CatalogProgress("item-1", ebookLocation = "epubcfi(/6/4!/4)", lastUpdate = 1700L))
        val translator = FakeTranslator(cfiResult = { locatorJson }, locatorResult = { it })
        val read = ebookRemote(peer, translator).get()
        assertEquals(locatorJson, read?.position)
        assertEquals(1700L, read?.lastUpdate)
    }

    @Test
    fun `ebook get - returns null when translation fails (CFI unresolvable)`() = runTest {
        val peer = FakePeer(progress = CatalogProgress("item-1", ebookLocation = "epubcfi(/6/4!/4)", lastUpdate = 1700L))
        val translator = FakeTranslator(cfiResult = { null }, locatorResult = { null })
        assertNull(ebookRemote(peer, translator).get())
    }

    @Test
    fun `ebook get - blank ebookLocation passes through as empty without translation`() = runTest {
        val peer = FakePeer(progress = CatalogProgress("item-1", ebookLocation = "", lastUpdate = 0L))
        val translator = FakeTranslator(cfiResult = { error("should not be called") }, locatorResult = { it })
        val read = ebookRemote(peer, translator).get()
        assertEquals("", read?.position)
        assertEquals(0L, read?.lastUpdate)
    }

    @Test
    fun `ebook get - returns null on network error`() = runTest {
        val peer = FakePeer(failGet = true)
        val translator = FakeTranslator(cfiResult = { locatorJson }, locatorResult = { it })
        assertNull(ebookRemote(peer, translator).get())
    }

    // --- ebook patch ---

    @Test
    fun `ebook patch - null translator returns null without sending PATCH`() = runTest {
        val peer = FakePeer()
        assertNull(ebookRemote(peer, translator = null, progress = 0.73f).patch(locatorJson))
        assertNull(peer.lastEbook)
    }

    @Test
    fun `ebook patch - translates Locator JSON to epubcfi and sends it with progress fraction`() = runTest {
        val peer = FakePeer()
        val translator = FakeTranslator(cfiResult = { it }, locatorResult = { "epubcfi(/6/8!/2)" })
        val stamp = ebookRemote(peer, translator, progress = 0.73f).patch(locatorJson)
        assertEquals(1800L, stamp)
        assertEquals("epubcfi(/6/8!/2)", peer.lastEbook?.location)
        assertEquals(0.73f, peer.lastEbook?.progress)
    }

    @Test
    fun `ebook patch - returns null without sending PATCH when translation fails`() = runTest {
        val peer = FakePeer()
        val translator = FakeTranslator(cfiResult = { it }, locatorResult = { null })
        assertNull(ebookRemote(peer, translator).patch(locatorJson))
        assertNull(peer.lastEbook)
    }

    @Test
    fun `ebook patch - returns null on network error`() = runTest {
        val peer = FakePeer(failPush = true)
        val translator = FakeTranslator(cfiResult = { it }, locatorResult = { it })
        assertNull(ebookRemote(peer, translator).patch("epubcfi(/6/4!/4)"))
    }

    // --- ebook, READIUM_NATIVE dialect: translator MUST be skipped ---

    @Test
    fun `ebook get - READIUM_NATIVE dialect passes ebookLocation through verbatim without translator`() = runTest {
        val peer = FakePeer(
            progress = CatalogProgress("item-1", ebookLocation = locatorJson, lastUpdate = 1700L),
            cfiDialect = CfiDialect.READIUM_NATIVE,
        )
        val translator = FakeTranslator(cfiResult = { error("must not translate for READIUM_NATIVE") }, locatorResult = { error("must not translate") })
        val read = ebookRemote(peer, translator).get()
        assertEquals(locatorJson, read?.position)
        assertEquals(1700L, read?.lastUpdate)
    }

    @Test
    fun `ebook get - READIUM_NATIVE dialect succeeds even without a translator`() = runTest {
        val peer = FakePeer(
            progress = CatalogProgress("item-1", ebookLocation = locatorJson, lastUpdate = 1700L),
            cfiDialect = CfiDialect.READIUM_NATIVE,
        )
        val read = ebookRemote(peer, translator = null).get()
        assertEquals(locatorJson, read?.position)
    }

    @Test
    fun `ebook patch - READIUM_NATIVE dialect sends Locator JSON verbatim and stamps`() = runTest {
        val peer = FakePeer(cfiDialect = CfiDialect.READIUM_NATIVE)
        val translator = FakeTranslator(cfiResult = { error("must not translate") }, locatorResult = { error("must not translate") })
        val stamp = ebookRemote(peer, translator, progress = 0.42f).patch(locatorJson)
        assertEquals(1800L, stamp)
        assertEquals(locatorJson, peer.lastEbook?.location)
        assertEquals(0.42f, peer.lastEbook?.progress)
    }

    // --- ebook, PAGE_NUMBER dialect: translator MUST be skipped, position passes through (#528) ---

    @Test
    fun `ebook get - PAGE_NUMBER dialect passes page-number location through verbatim without translator`() = runTest {
        val peer = FakePeer(
            progress = CatalogProgress("item-1", ebookLocation = "42", lastUpdate = 1700L),
            cfiDialect = CfiDialect.PAGE_NUMBER,
        )
        val read = ebookRemote(peer, translator = null).get()
        assertEquals("42", read?.position)
        assertEquals(1700L, read?.lastUpdate)
    }

    @Test
    fun `ebook patch - PAGE_NUMBER dialect sends the opaque position verbatim`() = runTest {
        val peer = FakePeer(cfiDialect = CfiDialect.PAGE_NUMBER)
        val stamp = ebookRemote(peer, translator = null, progress = 0.5f).patch("42")
        assertEquals(1800L, stamp)
        assertEquals("42", peer.lastEbook?.location)
    }

    // --- audio ---

    @Test
    fun `audio get maps currentTime and lastUpdate`() = runTest {
        val peer = FakePeer(progress = CatalogProgress("item-1", audioCurrentTime = 942.0, lastUpdate = 1700L))
        val read = audioRemote(peer).get()
        assertEquals(942.0, read?.position!!, 0.0001)
        assertEquals(1700L, read.lastUpdate)
    }

    @Test
    fun `audio patch sends seconds with the supplied duration and returns the write stamp`() = runTest {
        val peer = FakePeer()
        val stamp = audioRemote(peer).patch(1234.5)
        assertEquals(1800L, stamp)
        assertEquals(1234.5, peer.lastAudio?.currentTimeSec!!, 0.0001)
        assertEquals(3600.0, peer.lastAudio?.durationSec!!, 0.0001)
    }

    @Test
    fun `audio get and patch return null on network error`() = runTest {
        val peer = FakePeer(failGet = true, failPush = true)
        assertNull(audioRemote(peer).get())
        assertNull(audioRemote(peer).patch(10.0))
    }

    // --- UI-progress propagation: the library grid and detail view read
    // `library_items.readingProgress` / `finishedAt`. Regression pin for the sync-doesn't-refresh-
    // library-UI bug: `get()` MUST populate `readingProgress` and `finishedAt` from the pulled
    // CatalogProgress so the reconciler's UiProgressSink can mirror them into `library_items`.

    @Test
    fun `ebook get - propagates ebookProgress and finishedAt into RemoteProgress`() = runTest {
        val peer = FakePeer(
            progress = CatalogProgress(
                itemId = "item-1",
                ebookLocation = "epubcfi(/6/4!/4)",
                ebookProgress = 0.73f,
                isFinished = false,
                finishedAt = null,
                lastUpdate = 1700L,
            ),
        )
        val translator = FakeTranslator(cfiResult = { locatorJson }, locatorResult = { it })
        val read = ebookRemote(peer, translator).get()
        assertEquals(0.73f, read?.readingProgress)
        assertNull(read?.finishedAt)
    }

    @Test
    fun `ebook get - isFinished with null finishedAt falls back to lastUpdate as the finished stamp`() = runTest {
        val peer = FakePeer(
            progress = CatalogProgress(
                itemId = "item-1", ebookLocation = "epubcfi(/6/4!/4)",
                ebookProgress = 1f, isFinished = true, finishedAt = null, lastUpdate = 1700L,
            ),
        )
        val translator = FakeTranslator(cfiResult = { locatorJson }, locatorResult = { it })
        val read = ebookRemote(peer, translator).get()
        assertEquals(1f, read?.readingProgress)
        assertEquals(1700L, read?.finishedAt)
    }

    @Test
    fun `audio get - computes readingProgress fraction from currentTime and duration`() = runTest {
        val peer = FakePeer(
            progress = CatalogProgress(
                itemId = "item-1",
                audioCurrentTime = 900.0,
                audioDuration = 3600.0,
                isFinished = false,
                lastUpdate = 1700L,
            ),
        )
        val read = audioRemote(peer).get()
        assertEquals(0.25f, read?.readingProgress)
        assertNull(read?.finishedAt)
    }

    @Test
    fun `audio get - isFinished forces readingProgress to 1f and stamps finishedAt`() = runTest {
        val peer = FakePeer(
            progress = CatalogProgress(
                itemId = "item-1",
                audioCurrentTime = 3599.0,
                audioDuration = 3600.0,
                isFinished = true,
                finishedAt = 1750L,
                lastUpdate = 1700L,
            ),
        )
        val read = audioRemote(peer).get()
        assertEquals(1f, read?.readingProgress)
        assertEquals(1750L, read?.finishedAt)
    }

    @Test
    fun `audio get - zero duration yields 0f fraction (no NaN)`() = runTest {
        val peer = FakePeer(
            progress = CatalogProgress(
                itemId = "item-1", audioCurrentTime = 42.0, audioDuration = 0.0,
                isFinished = false, lastUpdate = 1700L,
            ),
        )
        val read = audioRemote(peer).get()
        assertEquals(0f, read?.readingProgress)
    }

    /**
     * Regression pin for the "ebook % gets clobbered to 0 on reader open" data-loss bug:
     * AbsCatalog implements BOTH ProgressPeerCapability and AudiobookProgressPeerCapability, so
     * for an ebook-only book the audio remote is also invoked. Its pullProgress returns
     * audioCurrentTime=0, audioDuration=0, isFinished=false — nothing meaningful for an audio
     * dimension that doesn't exist. Returning a non-null RemoteProgress here would trip the
     * reconciler's ServerWon branch on the empty audiobook_positions row and fire UiSink with
     * fraction=0, overwriting the ebook remote's just-written readingProgress. The get() gate
     * MUST return null so the reconciler treats it as Offline and skips the UI mirror.
     */
    @Test
    fun `audio get - returns null when payload has no audio dimension (ebook-only book)`() = runTest {
        val peer = FakePeer(
            progress = CatalogProgress(
                itemId = "item-1",
                ebookLocation = "epubcfi(/6/4)", ebookProgress = 0.42f, // real ebook progress
                audioCurrentTime = 0.0, audioDuration = 0.0,
                isFinished = false, lastUpdate = 1700L,
            ),
        )
        val read = audioRemote(peer).get()
        assertNull(read)
    }

    /**
     * Boundary of the null-gate: a finished audiobook whose duration wasn't populated in the
     * payload (rare metadata race) still carries a real "done" signal — we should surface it as
     * fraction=1f rather than swallowing to null.
     */
    @Test
    fun `audio get - isFinished with zero duration still surfaces fraction=1f (not null)`() = runTest {
        val peer = FakePeer(
            progress = CatalogProgress(
                itemId = "item-1",
                audioCurrentTime = 0.0, audioDuration = 0.0,
                isFinished = true, finishedAt = 1750L, lastUpdate = 1700L,
            ),
        )
        val read = audioRemote(peer).get()
        assertEquals(1f, read?.readingProgress)
        assertEquals(1750L, read?.finishedAt)
    }

    // ── CatalogProgressRemoteFactory WebDAV branch (ADR 0063) ────────────────

    private fun buildFactory(
        sourceType: com.riffle.core.models.SourceType = com.riffle.core.models.SourceType.CHITANKA,
        sourceId: String = "source-1",
        webDavBaseUrl: String? = "https://dav.example.com/riffle/",
    ): CatalogProgressRemoteFactory {
        val fakeCatalogRegistry = object : com.riffle.core.catalog.CatalogRegistry {
            override suspend fun forActive(): com.riffle.core.catalog.Catalog? = null
            override suspend fun forSource(source: com.riffle.core.models.Source): com.riffle.core.catalog.Catalog? = null
            override suspend fun forSourceId(id: String): com.riffle.core.catalog.Catalog? = null
        }
        val fakeSource = com.riffle.core.models.Source(
            id = sourceId,
            url = com.riffle.core.models.SourceUrl.parse("https://source.example.com")!!,
            isActive = true,
            insecureConnectionAllowed = false,
            username = "u",
            type = sourceType,
        )
        val fakeDaoItem = com.riffle.core.database.LibraryItemEntity(
            sourceId = sourceId, id = "item-1", libraryId = "lib",
            title = "T", author = "A", coverUrl = null, readingProgress = 0.3f, addedAt = 0L,
        )
        val fakeLibraryItemDao = object : com.riffle.core.database.LibraryItemDao {
            override suspend fun getById(sourceId: String, itemId: String) = fakeDaoItem
            override fun observeByLibraryId(sourceId: String, libraryId: String) = kotlinx.coroutines.flow.flowOf(emptyList<com.riffle.core.database.LibraryItemEntity>())
            override fun observeUngroupedByLibraryId(sourceId: String, libraryId: String) = kotlinx.coroutines.flow.flowOf(emptyList<com.riffle.core.database.LibraryItemEntity>())
            override fun observeInProgress(sourceId: String, libraryId: String) = kotlinx.coroutines.flow.flowOf(emptyList<com.riffle.core.database.LibraryItemEntity>())
            override fun observeFinished(sourceId: String, libraryId: String) = kotlinx.coroutines.flow.flowOf(emptyList<com.riffle.core.database.LibraryItemEntity>())
            override fun observeRecentlyAdded(sourceId: String, libraryId: String) = kotlinx.coroutines.flow.flowOf(emptyList<com.riffle.core.database.LibraryItemEntity>())
            override fun observeAllBooks(sourceId: String, libraryId: String) = kotlinx.coroutines.flow.flowOf(emptyList<com.riffle.core.database.LibraryItemEntity>())
            override fun observeBySource(sourceId: String) = kotlinx.coroutines.flow.flowOf(emptyList<com.riffle.core.database.LibraryItemEntity>())
            override fun observeById(sourceId: String, itemId: String) = kotlinx.coroutines.flow.flowOf(null as com.riffle.core.database.LibraryItemEntity?)
            override suspend fun upsertAll(items: List<com.riffle.core.database.LibraryItemEntity>) = Unit
            override suspend fun insertOrIgnore(items: List<com.riffle.core.database.LibraryItemEntity>) = Unit
            override suspend fun updateMetadata(metadata: com.riffle.core.database.LibraryItemMetadata) = Unit
            override suspend fun listByLibraryId(sourceId: String, libraryId: String) = emptyList<com.riffle.core.database.LibraryItemEntity>()
            override suspend fun listByIds(sourceId: String, itemIds: List<String>) = emptyList<com.riffle.core.database.LibraryItemEntity>()
            override suspend fun findSourceIdForItem(itemId: String): String? = null
            override suspend fun deleteByLibraryId(sourceId: String, libraryId: String) = Unit
            override suspend fun deleteById(sourceId: String, itemId: String) = Unit
            override suspend fun deleteByIds(sourceId: String, itemIds: List<String>) = Unit
            override suspend fun idsForLibrary(sourceId: String, libraryId: String) = emptyList<String>()
            override suspend fun updateLastOpenedAt(sourceId: String, itemId: String, timestamp: Long) = Unit
            override suspend fun updateReadingProgress(sourceId: String, itemId: String, progress: Float) = Unit
            override suspend fun updateLibraryId(sourceId: String, itemId: String, libraryId: String) = Unit
            override suspend fun updateFinishedAt(sourceId: String, itemId: String, finishedAt: Long?) = Unit
            override suspend fun getLastOpenedAtMap(sourceId: String, libraryId: String) = emptyList<com.riffle.core.database.LastOpenedAtRow>()
            override suspend fun getReadingProgressMap(sourceId: String, libraryId: String) = emptyList<com.riffle.core.database.ReadingProgressRow>()
            override suspend fun listMatchableBySourceType(serverType: String) = emptyList<com.riffle.core.database.MatchableItemRow>()
        }
        val fakeTranslatorFactory = object : com.riffle.core.domain.EbookCfiTranslatorFactory {
            override fun forItem(sourceId: String, itemId: String) = null
        }
        val fakeConfigStore = object : com.riffle.core.domain.AnnotationSyncConfigStore {
            private val flow = kotlinx.coroutines.flow.MutableStateFlow(
                webDavBaseUrl?.let { com.riffle.core.domain.AnnotationSyncConfig(it, "user", "pass") }
            )
            override fun observe() = flow
            override suspend fun save(config: com.riffle.core.domain.AnnotationSyncConfig) { flow.value = config }
            override suspend fun clear() { flow.value = null }
        }
        val fakeSourceRepository = object : com.riffle.core.domain.SourceRepository {
            override fun observeAll() = kotlinx.coroutines.flow.flowOf(listOf(fakeSource))
            override suspend fun getById(sourceId: String) = if (sourceId == fakeSource.id) fakeSource else null
            override suspend fun getActive(): com.riffle.core.models.Source? = fakeSource
            override suspend fun commit(pending: com.riffle.core.domain.PendingSource, hiddenLibraryIds: Set<String>): com.riffle.core.domain.CommitSourceResult = error("not used")
            override suspend fun setActive(sourceId: String) = Unit
            override suspend fun remove(sourceId: String) = Unit
            override suspend fun getSourceVersion(sourceId: String): String? = null
        }
        val fakeWebDavFactory = com.riffle.core.sources.webdav.WebDavProgressRemoteFactory(
            httpClient = io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp),
            dispatchers = com.riffle.core.domain.DefaultDispatcherProvider,
        )
        return CatalogProgressRemoteFactory(
            catalogRegistry = fakeCatalogRegistry,
            libraryItemDao = fakeLibraryItemDao,
            translatorFactory = fakeTranslatorFactory,
            clock = clock,
            annotationSyncConfigStore = fakeConfigStore,
            webDavProgressRemoteFactory = fakeWebDavFactory,
            sourceRepository = fakeSourceRepository,
        )
    }

    @Test
    fun `factory - ebook returns non-null WebDav remote for isWebSource source when WebDAV configured`() = runTest {
        val factory = buildFactory(sourceType = com.riffle.core.models.SourceType.CHITANKA)
        assertNotNull(factory.ebook("source-1", "item-1"))
    }

    @Test
    fun `factory - ebook returns non-null for GUTENBERG source when WebDAV configured`() = runTest {
        val factory = buildFactory(sourceType = com.riffle.core.models.SourceType.GUTENBERG)
        assertNotNull(factory.ebook("source-1", "item-1"))
    }

    @Test
    fun `factory - ebook returns null for isWebSource source when WebDAV not configured`() = runTest {
        val factory = buildFactory(sourceType = com.riffle.core.models.SourceType.CHITANKA, webDavBaseUrl = null)
        assertNull(factory.ebook("source-1", "item-1"))
    }

    @Test
    fun `factory - ebook returns null for LOCAL_FILES source even when WebDAV configured`() = runTest {
        val factory = buildFactory(sourceType = com.riffle.core.models.SourceType.LOCAL_FILES)
        assertNull(factory.ebook("source-1", "item-1"))
    }

    // ADR 0063: Gramofonche audiobooks are served under the CHITANKA source type with no
    // AudiobookProgressPeerCapability. The factory must return a WebDav-backed Double remote
    // (via asAudioRemote) so ProgressSweep can sync audiobook_positions rows.
    // Removed-test: factory - audio always returns null for isWebSource source
    @Test
    fun `factory - audio returns non-null WebDav remote for isWebSource source when WebDAV configured`() = runTest {
        val factory = buildFactory(sourceType = com.riffle.core.models.SourceType.CHITANKA)
        assertNotNull(factory.audio("source-1", "item-1"))
    }

    @Test
    fun `factory - audio returns null for isWebSource source when WebDAV not configured`() = runTest {
        val factory = buildFactory(sourceType = com.riffle.core.models.SourceType.CHITANKA, webDavBaseUrl = null)
        assertNull(factory.audio("source-1", "item-1"))
    }
}
