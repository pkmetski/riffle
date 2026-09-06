package com.riffle.feature.downloads

import com.riffle.core.domain.ContentCacheAutoClear
import com.riffle.core.domain.ContentCacheSettingsStore
import com.riffle.core.domain.DownloadsRepository
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.ReadaloudSidecarDownloads
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.StoredItemArtifact
import com.riffle.core.domain.StoredMediaType
import com.riffle.core.models.AudiobookIdentityResult
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.Library
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.ReadaloudLink
import com.riffle.core.models.ServerType
import com.riffle.core.models.Source
import com.riffle.core.models.SourceUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun item(
        sourceId: String,
        id: String,
        title: String,
        ebookFormat: EbookFormat = EbookFormat.Epub,
        hasAudio: Boolean = false,
    ) = LibraryItem(
        id = id,
        libraryId = "lib-$sourceId",
        title = title,
        author = "author",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = ebookFormat,
        hasAudio = hasAudio,
        sourceId = sourceId,
    )

    /** Regression: PR fix ungating Cached and Readaloud sections from the active source.
     *
     *  Prior behavior threaded the active Catalog's DownloadsCapability / ReadaloudCapability into
     *  showCachedSection / showReadaloudSection flags. When the active library was LocalFiles (which
     *  omits both capabilities), the ViewModel silently produced state that told the UI to hide
     *  every ABS/Chitanka/Storyteller Cached row and every Storyteller readaloud sidecar — even
     *  though the Downloaded list was rendered app-wide. The fix removes the capability lookup so
     *  downloaded and cached media behave the same way (populated whenever local artifacts exist).
     *
     *  This test would fail if someone re-adds capability gating: injecting a CatalogRegistry (or any
     *  active-source predicate) that drops rows from non-matching sources would make the cached list
     *  lose the cached artifacts and folded readaloud sidecars here. */
    @Test
    fun `populates all sections regardless of active source`() = runTest(dispatcher) {
        val downloadsRepo = FakeDownloadsRepository(
            downloaded = listOf(
                StoredItemArtifact("abs", "abs-book", StoredMediaType.Epub),
                StoredItemArtifact("chitanka", "ch-book", StoredMediaType.Pdf),
                StoredItemArtifact("abs", "ab-book", StoredMediaType.Audiobook),
                StoredItemArtifact("storyteller", "st-down", StoredMediaType.Epub),
            ),
            cached = listOf(
                StoredItemArtifact("abs", "abs-cached", StoredMediaType.Cbz),
                StoredItemArtifact("abs", "ab-cached", StoredMediaType.Audiobook),
            ),
            sizeOfFn = { _, _ -> 1024L },
        )

        val items = mapOf(
            ("abs" to "abs-book") to item("abs", "abs-book", "ABS Downloaded"),
            ("chitanka" to "ch-book") to item("chitanka", "ch-book", "Chitanka Downloaded"),
            ("abs" to "abs-cached") to item("abs", "abs-cached", "ABS Cached"),
            ("abs" to "ab-book") to item("abs", "ab-book", "ABS Audiobook Downloaded", EbookFormat.Unsupported, hasAudio = true),
            ("abs" to "abs-readaloud-down") to item("abs", "abs-readaloud-down", "ABS Readaloud Downloaded"),
            ("abs" to "ab-cached") to item("abs", "ab-cached", "ABS Audiobook Cached", EbookFormat.Unsupported, hasAudio = true),
            ("abs" to "abs-readaloud-cached") to item("abs", "abs-readaloud-cached", "ABS Readaloud Cached"),
        )
        val libraryObserver = FakeLibraryObserver(items)

        val sources = mapOf(
            "abs" to source("abs", ServerType.AUDIOBOOKSHELF),
            "chitanka" to source("chitanka", ServerType.AUDIOBOOKSHELF),
            "storyteller" to source("storyteller", ServerType.STORYTELLER_SERVICE),
        )
        val sourceRepository = FakeSourceRepository(sources)

        val readaloudLinks = mapOf(
            ("storyteller" to "st-down") to listOf(link("storyteller", "st-down", "abs", "abs-readaloud-down")),
            ("storyteller" to "st-book") to listOf(link("storyteller", "st-book", "abs", "abs-readaloud-cached")),
        )
        val readaloudLinkRepository = FakeReadaloudLinkRepository(readaloudLinks)

        val sidecarStore = FakeReadaloudSidecarDownloads(
            cached = listOf(ReadaloudSidecarDownloads.CachedSidecar("storyteller", "st-book", 2048L))
        )

        val cacheSettingsStore = FakeContentCacheSettingsStore(ContentCacheSettingsStore.DEFAULT_AUTO_CLEAR)

        val vm = DownloadsViewModel(
            downloadsRepo,
            libraryObserver,
            sourceRepository,
            readaloudLinkRepository,
            sidecarStore,
            cacheSettingsStore,
        )
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(
            listOf("ABS Downloaded", "Chitanka Downloaded", "ABS Audiobook Downloaded", "ABS Readaloud Downloaded"),
            state.downloadedItems.map { it.item.title },
        )
        assertEquals(
            listOf("ABS Cached", "ABS Audiobook Cached", "ABS Readaloud Cached"),
            state.cachedItems.map { it.item.title },
        )
        assertEquals(
            listOf(
                setOf(LocalMediaType.Epub),
                setOf(LocalMediaType.Pdf),
                setOf(LocalMediaType.Audiobook),
                setOf(LocalMediaType.Readaloud),
            ),
            state.downloadedItems.map { it.mediaTypes },
        )
        assertEquals(
            listOf(
                setOf(LocalMediaType.Comic),
                setOf(LocalMediaType.Audiobook),
                setOf(LocalMediaType.Readaloud),
            ),
            state.cachedItems.map { it.mediaTypes },
        )
        assertEquals(ContentCacheSettingsStore.DEFAULT_AUTO_CLEAR, state.cacheAutoClear)
    }

    @Test
    fun `cached readaloud sidecar opens linked source item and removes storyteller sidecar`() = runTest(dispatcher) {
        val downloadsRepo = FakeDownloadsRepository(emptyList(), emptyList(), sizeOfFn = { _, _ -> 0L })

        val items = mapOf(("abs" to "ebook") to item("abs", "ebook", "Linked Source Item"))
        val libraryObserver = FakeLibraryObserver(items)

        val readaloudLinks = mapOf(
            ("storyteller" to "sidecar-book") to listOf(link("storyteller", "sidecar-book", "abs", "ebook")),
        )
        val readaloudLinkRepository = FakeReadaloudLinkRepository(readaloudLinks)

        val sidecarStore = FakeReadaloudSidecarDownloads(
            cached = listOf(ReadaloudSidecarDownloads.CachedSidecar("storyteller", "sidecar-book", 4096L))
        )

        val vm = DownloadsViewModel(
            downloadsRepo,
            libraryObserver,
            FakeSourceRepository(emptyMap()),
            readaloudLinkRepository,
            sidecarStore,
            FakeContentCacheSettingsStore(ContentCacheSettingsStore.DEFAULT_AUTO_CLEAR),
        )
        advanceUntilIdle()

        val entry = vm.uiState.value.cachedItems.single()
        assertEquals("abs", entry.sourceId)
        assertEquals("ebook", entry.item.id)
        assertEquals(setOf(LocalMediaType.Readaloud), entry.mediaTypes)

        vm.removeCachedItem(entry)
        advanceUntilIdle()

        assertEquals(listOf("storyteller" to "sidecar-book"), sidecarStore.removedSidecars)
        assertEquals(emptyList<Pair<String, String>>(), downloadsRepo.removedCached)
    }

    // --- Fakes ---

    private class FakeDownloadsRepository(
        private val downloaded: List<StoredItemArtifact>,
        private val cached: List<StoredItemArtifact>,
        private val sizeOfFn: (String, String) -> Long,
    ) : DownloadsRepository {
        val removedCached = mutableListOf<Pair<String, String>>()
        val removedDownloaded = mutableListOf<Pair<String, String>>()

        override fun getDownloadedArtifacts() = downloaded
        override fun getCachedArtifacts() = cached
        override fun sizeOf(sourceId: String, itemId: String): Long = sizeOfFn(sourceId, itemId)
        override suspend fun removeDownload(sourceId: String, itemId: String) { removedDownloaded += sourceId to itemId }
        override suspend fun removeCached(sourceId: String, itemId: String) { removedCached += sourceId to itemId }
        override suspend fun removeAllDownloads() {}
        override suspend fun clearAllCached() {}
    }

    private class FakeLibraryObserver(private val items: Map<Pair<String, String>, LibraryItem>) : LibraryObserver {
        override suspend fun getItem(sourceId: String, itemId: String) = items[sourceId to itemId]
        override suspend fun getItem(itemId: String): LibraryItem? = null
        override fun observeItem(itemId: String): Flow<LibraryItem?> = throw UnsupportedOperationException()
        override fun observeItem(sourceId: String, itemId: String): Flow<LibraryItem?> = throw UnsupportedOperationException()
        override fun observeLibraries() = throw UnsupportedOperationException()
        override fun observeLibraries(sourceId: String) = throw UnsupportedOperationException()
        override fun observeLibraryItems(libraryId: String) = throw UnsupportedOperationException()
        override fun observeUngroupedLibraryItems(libraryId: String) = throw UnsupportedOperationException()
        override fun observeInProgressItems(libraryId: String) = throw UnsupportedOperationException()
        override fun observeFinishedItems(libraryId: String) = throw UnsupportedOperationException()
        override fun observeRecentlyAddedItems(libraryId: String) = throw UnsupportedOperationException()
        override fun observeAllBooks(libraryId: String) = throw UnsupportedOperationException()
        override fun observeSeries(libraryId: String) = throw UnsupportedOperationException()
        override fun observeCollections(libraryId: String) = throw UnsupportedOperationException()
        override fun observeSeriesItems(seriesId: String) = throw UnsupportedOperationException()
        override fun observeContinueSeriesItems(libraryId: String) = throw UnsupportedOperationException()
        override fun observeCollectionItems(collectionId: String) = throw UnsupportedOperationException()
        override suspend fun getLibrary(libraryId: String): Library? = null
        override suspend fun getSeriesIdForItem(sourceId: String, itemId: String): String? = null
    }

    private class FakeSourceRepository(private val sources: Map<String, Source>) : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = throw UnsupportedOperationException()
        override suspend fun getActive(): Source? = null
        override suspend fun getById(sourceId: String): Source? = sources[sourceId]
        override suspend fun commit(pending: com.riffle.core.domain.PendingSource, hiddenLibraryIds: Set<String>) =
            throw UnsupportedOperationException()
        override suspend fun setActive(sourceId: String) {}
        override suspend fun remove(sourceId: String) {}
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private class FakeReadaloudLinkRepository(
        private val links: Map<Pair<String, String>, List<ReadaloudLink>>,
    ) : ReadaloudLinkRepository {
        override suspend fun findByStorytellerBook(storytellerSourceId: String, storytellerBookId: String) =
            links[storytellerSourceId to storytellerBookId] ?: emptyList()
        override fun observeAll(): Flow<List<ReadaloudLink>> = throw UnsupportedOperationException()
        override fun observeLinkedAbsItemIds(): Flow<Set<String>> = throw UnsupportedOperationException()
        override suspend fun findByAbsItem(absSourceId: String, absLibraryItemId: String): ReadaloudLink? = null
        override suspend fun unlinkAbsItem(absSourceId: String, absLibraryItemId: String) {}
        override suspend fun countForSource(sourceId: String): Int = 0
        override suspend fun updateIdentityResult(absSourceId: String, absLibraryItemId: String, result: AudiobookIdentityResult) {}
    }

    private class FakeReadaloudSidecarDownloads(
        private val cached: List<ReadaloudSidecarDownloads.CachedSidecar>,
    ) : ReadaloudSidecarDownloads {
        val removedSidecars = mutableListOf<Pair<String, String>>()
        var cleared = false

        override fun listCached() = cached
        override fun clearAll() { cleared = true }
        override fun remove(storytellerSourceId: String, storytellerBookId: String) {
            removedSidecars += storytellerSourceId to storytellerBookId
        }
    }

    private class FakeContentCacheSettingsStore(initialValue: ContentCacheAutoClear) : ContentCacheSettingsStore {
        override val autoClear: Flow<ContentCacheAutoClear> = MutableStateFlow(initialValue)
        override suspend fun setAutoClear(value: ContentCacheAutoClear) {}
    }

    private fun source(id: String, serverType: ServerType) = Source(
        id = id,
        url = SourceUrl.parse("http://$id.local")!!,
        isActive = false,
        insecureConnectionAllowed = false,
        username = "user",
        serverType = serverType,
    )

    private fun link(
        storytellerSourceId: String,
        storytellerBookId: String,
        absSourceId: String,
        absLibraryItemId: String,
    ) = ReadaloudLink(
        storytellerSourceId = storytellerSourceId,
        storytellerBookId = storytellerBookId,
        absSourceId = absSourceId,
        absLibraryItemId = absLibraryItemId,
        userConfirmed = true,
    )
}
