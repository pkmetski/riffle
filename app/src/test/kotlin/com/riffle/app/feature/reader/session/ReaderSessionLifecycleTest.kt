package com.riffle.app.feature.reader.session

import com.riffle.core.data.OpenReconcileTargets
import com.riffle.core.domain.Annotation
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.AudioIdentity
import com.riffle.core.domain.AudioIdentityResolver
import com.riffle.core.domain.AudioPlaybackPreferencesStore
import com.riffle.core.domain.AudiobookIdentityResult
import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.EpubOpenResult
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.ReadaloudLink
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.SourceUrl
import com.riffle.core.logging.RecordingLogger
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderSessionLifecycleTest {

    private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    // ── Fakes ────────────────────────────────────────────────────────────────────────────

    private class FakeLibraryObserver(
        private val items: Map<Pair<String, String>, LibraryItem>,
        private val activeItems: Map<String, LibraryItem>,
    ) : LibraryObserver {
        override fun observeLibraries(): Flow<List<com.riffle.core.domain.Library>> = flowOf(emptyList())
        override fun observeLibraries(sourceId: String): Flow<List<com.riffle.core.domain.Library>> = flowOf(emptyList())
        override fun observeLibraryItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeUngroupedLibraryItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeInProgressItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeFinishedItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeRecentlyAddedItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeAllBooks(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeSeries(libraryId: String): Flow<List<com.riffle.core.domain.Series>> = flowOf(emptyList())
        override fun observeCollections(libraryId: String): Flow<List<com.riffle.core.domain.Collection>> = flowOf(emptyList())
        override fun observeSeriesItems(seriesId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeContinueSeriesItems(libraryId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override fun observeCollectionItems(collectionId: String): Flow<List<LibraryItem>> = flowOf(emptyList())
        override suspend fun getItem(itemId: String): LibraryItem? = activeItems[itemId]
        override fun observeItem(itemId: String): Flow<LibraryItem?> = MutableStateFlow(activeItems[itemId])
        override suspend fun getItem(sourceId: String, itemId: String): LibraryItem? = items[sourceId to itemId]
        override suspend fun getLibrary(libraryId: String): com.riffle.core.domain.Library? = null
        override suspend fun getSeriesIdForItem(sourceId: String, itemId: String): String? = null
    }

    private class FakeEpubRepository(
        private val outcome: EpubOpenResult,
    ) : EpubRepository {
        override suspend fun openEpub(item: LibraryItem): EpubOpenResult = outcome
        override suspend fun downloadEpub(item: LibraryItem, onProgress: (Long, Long) -> Unit) =
            error("not needed")
        override suspend fun removeDownload(sourceId: String, itemId: String) {}
        override fun isDownloaded(sourceId: String, itemId: String) = false
        override fun isCached(sourceId: String, itemId: String) = false
        override suspend fun saveReadingPosition(itemId: String, cfi: String) {}
    }

    private class FakeServerRepository(private val active: Source?) : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = flowOf(active?.let { listOf(it) } ?: emptyList())
        override suspend fun getActive(): Source? = active
        override suspend fun authenticate(
            url: SourceUrl, username: String, password: String,
            insecureAllowed: Boolean, serverType: ServerType,
        ) = com.riffle.core.domain.AuthenticateResult.WrongCredentials()
        override suspend fun commit(
            pending: com.riffle.core.domain.PendingSource, hiddenLibraryIds: Set<String>,
        ) = com.riffle.core.domain.CommitSourceResult.Failure(RuntimeException("not needed"))
        override suspend fun setActive(sourceId: String) {}
        override suspend fun remove(sourceId: String) {}
        override suspend fun getSourceVersion(sourceId: String): String? = null
        override suspend fun ensureAbsUserId(sourceId: String): String? = "abs-user"
    }

    private class FakeReadaloudLinkRepository(
        private val absLookup: Map<Pair<String, String>, ReadaloudLink> = emptyMap(),
        private val storytellerLookup: Map<Pair<String, String>, List<ReadaloudLink>> = emptyMap(),
    ) : ReadaloudLinkRepository {
        override fun observeAll(): Flow<List<ReadaloudLink>> = flowOf(absLookup.values.toList())
        override fun observeLinkedAbsItemIds(): Flow<Set<String>> = flowOf(emptySet())
        override suspend fun findByAbsItem(absSourceId: String, absLibraryItemId: String): ReadaloudLink? =
            absLookup[absSourceId to absLibraryItemId]
        override suspend fun findByStorytellerBook(storytellerSourceId: String, storytellerBookId: String): List<ReadaloudLink> =
            storytellerLookup[storytellerSourceId to storytellerBookId] ?: emptyList()
        override suspend fun unlinkAbsItem(absSourceId: String, absLibraryItemId: String) {}
        override suspend fun countForSource(sourceId: String): Int = 0
    }

    private class FakeAudioIdentityResolver(
        private val identity: AudioIdentity = AudioIdentity("srv", "audio-id"),
    ) : AudioIdentityResolver {
        override suspend fun resolveForStorytellerBook(storytellerSourceId: String, storytellerBookId: String) = identity
    }

    private class FakeAudioPlaybackPreferencesStore(private val speed: Float? = null) : AudioPlaybackPreferencesStore {
        override suspend fun load(identity: AudioIdentity): Float? = speed
        override suspend fun save(identity: AudioIdentity, speed: Float) {}
        override suspend fun clear(identity: AudioIdentity) {}
        override suspend fun rekey(old: AudioIdentity, new: AudioIdentity) {}
    }

    private class FakeListeningPreferencesStore(
        private val defaultSpeed: Float = 1.0f,
    ) : ListeningPreferencesStore {
        override val defaultPlaybackSpeed: Flow<Float> = flowOf(defaultSpeed)
        override val skipIntervalSeconds: Flow<Int> = flowOf(30)
        override val rewindIntervalSeconds: Flow<Int> = flowOf(15)
        override val rewindOnResumeSeconds: Flow<Int> = flowOf(0)
        override suspend fun setDefaultPlaybackSpeed(speed: Float) {}
        override suspend fun setSkipIntervalSeconds(seconds: Int) {}
        override suspend fun setRewindIntervalSeconds(seconds: Int) {}
        override suspend fun setRewindOnResumeSeconds(seconds: Int) {}
    }

    private class FakeAnnotationStore(
        private val byCfi: Map<Triple<String, String, String>, Annotation> = emptyMap(),
    ) : AnnotationStore {
        override fun observeHighlights(sourceId: String, itemId: String): Flow<List<Annotation>> = flowOf(emptyList())
        override fun observeBookmarks(sourceId: String, itemId: String): Flow<List<Annotation>> = flowOf(emptyList())
        override fun observeAnnotations(sourceId: String, itemId: String): Flow<List<Annotation>> = flowOf(emptyList())
        override fun observeAnnotationsForSource(sourceId: String): Flow<List<Annotation>> = flowOf(emptyList())
        override suspend fun createHighlight(
            sourceId: String, itemId: String, cfi: String, textSnippet: String, chapterHref: String,
            textBefore: String, textAfter: String, color: String, spineIndex: Int, progression: Double,
        ): Annotation = error("not needed")
        override suspend fun createBookmark(
            sourceId: String, itemId: String, cfi: String, textSnippet: String, chapterHref: String,
            spineIndex: Int, progression: Double, bookmarkTitle: String,
        ): Annotation = error("not needed")
        override suspend fun createImageAnnotation(
            sourceId: String, itemId: String, cfi: String, textSnippet: String, chapterHref: String,
            spineIndex: Int, progression: Double, imageHref: String?, imageSvg: String?, color: String,
        ): Annotation = error("not needed")
        override suspend fun delete(id: String) {}
        override suspend fun recolor(id: String, color: String) {}
        override suspend fun updateNote(id: String, note: String?) {}
        override suspend fun renameBookmark(id: String, title: String) {}
        override suspend fun findByItemAndCfi(sourceId: String, itemId: String, cfi: String): Annotation? =
            byCfi[Triple(sourceId, itemId, cfi)]
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────

    private val activeServer = Source(
        id = "srv-abs",
        url = SourceUrl.parse("https://example.test")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "u",
        serverType = ServerType.AUDIOBOOKSHELF,
    )

    private val storytellerServer = activeServer.copy(id = "srv-st", serverType = ServerType.STORYTELLER)

    private val ebookItem = LibraryItem(
        id = "item-1",
        libraryId = "lib-1",
        title = "Book Title",
        author = "Author",
        coverUrl = null,
        readingProgress = 0f,
        isCached = true,
        isDownloaded = true,
        ebookFormat = EbookFormat.Epub,
        sourceId = "srv-abs",
    )

    private fun makeLifecycle(
        openReconcileTargets: OpenReconcileTargets = OpenReconcileTargets(),
        libraryObserver: LibraryObserver = FakeLibraryObserver(
            items = mapOf(("srv-abs" to "item-1") to ebookItem),
            activeItems = mapOf("item-1" to ebookItem),
        ),
        epubRepository: EpubRepository = FakeEpubRepository(
            EpubOpenResult.Success(epubFile = File("/tmp/dummy.epub"), lastPosition = null),
        ),
        sourceRepository: SourceRepository = FakeServerRepository(activeServer),
        readaloudLinkRepository: ReadaloudLinkRepository = FakeReadaloudLinkRepository(),
        audioPlaybackPreferencesStore: AudioPlaybackPreferencesStore = FakeAudioPlaybackPreferencesStore(speed = 1.25f),
        annotationStore: AnnotationStore = FakeAnnotationStore(),
        pub: Publication? = mockk(relaxed = true),
        cfiResolver: suspend (String) -> Locator? = { null },
    ): Pair<ReaderSessionLifecycle, OpenReconcileTargets> {
        val readerSyncFactory = mockk<com.riffle.app.feature.reader.ReaderSyncFactory>(relaxed = true).also {
            io.mockk.coEvery { it.createIfApplicable(any()) } returns null
            io.mockk.coEvery { it.createAudiobookFollowIfApplicable(any()) } returns null
        }
        val lifecycle = ReaderSessionLifecycle(
            openPublication = { pub },
            cfiStringToLocator = cfiResolver,
            libraryObserver = libraryObserver,
            epubRepository = epubRepository,
            sourceRepository = sourceRepository,
            readaloudLinkRepository = readaloudLinkRepository,
            audioIdentityResolver = FakeAudioIdentityResolver(),
            audioPlaybackPreferencesStore = audioPlaybackPreferencesStore,
            listeningPreferencesStore = FakeListeningPreferencesStore(defaultSpeed = 1.0f),
            openReconcileTargets = openReconcileTargets,
            readerSyncFactory = readerSyncFactory,
            annotationStore = annotationStore,
            logger = RecordingLogger(),
        )
        return lifecycle to openReconcileTargets
    }

    private fun params(
        itemId: String = "item-1",
        openAtCfi: String? = null,
        startTocHref: String? = null,
    ) = ReaderSessionLifecycle.OpenParams(itemId, openAtCfi, startTocHref)

    // ── Tests ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `open returns Error when item is missing`() = runTest {
        val (lifecycle, _) = makeLifecycle(
            libraryObserver = FakeLibraryObserver(items = emptyMap(), activeItems = emptyMap()),
        )
        val outcome = lifecycle.open(params())
        assertTrue(outcome is ReaderSessionLifecycle.OpenOutcome.Error)
        assertEquals("Book not found", (outcome as ReaderSessionLifecycle.OpenOutcome.Error).message)
        assertNull(lifecycle.publication.value)
    }

    @Test
    fun `open returns Error when EPUB open fails`() = runTest {
        val (lifecycle, _) = makeLifecycle(
            epubRepository = FakeEpubRepository(EpubOpenResult.Offline),
        )
        val outcome = lifecycle.open(params())
        assertTrue(outcome is ReaderSessionLifecycle.OpenOutcome.Error)
        assertEquals("Book not available offline", (outcome as ReaderSessionLifecycle.OpenOutcome.Error).message)
    }

    @Test
    fun `open returns Error when publication cannot be opened`() = runTest {
        val (lifecycle, _) = makeLifecycle(pub = null)
        val outcome = lifecycle.open(params())
        assertTrue(outcome is ReaderSessionLifecycle.OpenOutcome.Error)
        assertEquals("Failed to open EPUB", (outcome as ReaderSessionLifecycle.OpenOutcome.Error).message)
        assertNull(lifecycle.publication.value)
    }

    @Test
    fun `open on ABS server without match uses itemId as audio ids and marks reconcile open`() = runTest {
        val reconcile = OpenReconcileTargets()
        val (lifecycle, _) = makeLifecycle(openReconcileTargets = reconcile)
        val outcome = lifecycle.open(params()) as ReaderSessionLifecycle.OpenOutcome.Ready

        assertFalse(outcome.isStorytellerServer)
        assertEquals("item-1", outcome.resolvedAudioBookId)
        assertEquals("srv-abs", outcome.resolvedAudioServerId)
        assertEquals("srv-abs", outcome.resolvedReaderServerId)
        assertNull(outcome.resolvedAudiobookItemId)
        assertEquals(1.25f, outcome.resolvedInitialSpeed, 0.0001f)
        assertSame(activeServer, outcome.activeServer)
        assertTrue(reconcile.isOpen("srv-abs", "item-1"))
        assertNotNull(lifecycle.publication.value)
    }

    @Test
    fun `open with matched link resolves audiobook id and identity from link`() = runTest {
        val link = ReadaloudLink(
            storytellerSourceId = "srv-st",
            storytellerBookId = "st-book-1",
            absSourceId = "srv-abs",
            absLibraryItemId = "item-1",
            userConfirmed = true,
            identityResult = AudiobookIdentityResult.UNKNOWN,
        )
        val audiobookItem = ebookItem.copy(id = "item-2", hasAudio = true)
        val siblingLinks = listOf(
            link,
            link.copy(absLibraryItemId = "item-2"),
        )
        val (lifecycle, _) = makeLifecycle(
            libraryObserver = FakeLibraryObserver(
                items = mapOf(
                    ("srv-abs" to "item-1") to ebookItem,
                    ("srv-abs" to "item-2") to audiobookItem,
                ),
                activeItems = mapOf("item-1" to ebookItem, "item-2" to audiobookItem),
            ),
            readaloudLinkRepository = FakeReadaloudLinkRepository(
                absLookup = mapOf(("srv-abs" to "item-1") to link),
                storytellerLookup = mapOf(("srv-st" to "st-book-1") to siblingLinks),
            ),
        )
        val outcome = lifecycle.open(params()) as ReaderSessionLifecycle.OpenOutcome.Ready

        assertEquals("st-book-1", outcome.resolvedAudioBookId)
        assertEquals("srv-st", outcome.resolvedAudioServerId)
        assertEquals("item-2", outcome.resolvedAudiobookItemId)
    }

    @Test
    fun `open on Storyteller server keeps itemId as audio ids`() = runTest {
        val (lifecycle, _) = makeLifecycle(
            sourceRepository = FakeServerRepository(storytellerServer),
        )
        val outcome = lifecycle.open(params()) as ReaderSessionLifecycle.OpenOutcome.Ready

        assertTrue(outcome.isStorytellerServer)
        assertEquals("item-1", outcome.resolvedAudioBookId)
        assertEquals("srv-st", outcome.resolvedAudioServerId)
    }

    @Test
    fun `open with openAtCfi resolves openAtLocator and initialFocusAnnotationId`() = runTest {
        val cfi = "epubcfi(/6/2!/4/2)"
        val resolvedLocator = mockk<Locator>(relaxed = true)
        val annotation = mockk<Annotation>(relaxed = true).also { every { it.id } returns "ann-99" }
        val (lifecycle, _) = makeLifecycle(
            annotationStore = FakeAnnotationStore(
                byCfi = mapOf(Triple("srv-abs", "item-1", cfi) to annotation),
            ),
            cfiResolver = { c -> if (c == cfi) resolvedLocator else null },
        )
        val outcome = lifecycle.open(params(openAtCfi = cfi)) as ReaderSessionLifecycle.OpenOutcome.Ready

        assertSame(resolvedLocator, outcome.openAtLocator)
        assertEquals("ann-99", outcome.initialFocusAnnotationId)
        assertSame(resolvedLocator, outcome.initialLocator)
        assertSame(resolvedLocator, outcome.effectiveInitialLocator)
    }

    @Test
    fun `open with TOC nav nulls initialLocator but retains effectiveInitialLocator`() = runTest {
        val cfi = "epubcfi(/6/2!/4/2)"
        val resolved = mockk<Locator>(relaxed = true)
        val (lifecycle, _) = makeLifecycle(
            cfiResolver = { resolved },
        )
        val outcome = lifecycle.open(params(openAtCfi = cfi, startTocHref = "chapter-2.xhtml"))
            as ReaderSessionLifecycle.OpenOutcome.Ready

        assertNull(outcome.initialLocator)
        assertNull(outcome.initialFocusAnnotationId)
        assertSame(resolved, outcome.effectiveInitialLocator)
        assertSame(resolved, outcome.openAtLocator)
    }

    @Test
    fun `open builds matchedSync from readerSyncFactory`() = runTest {
        val (lifecycle, _) = makeLifecycle()
        val outcome = lifecycle.open(params())
        assertTrue(outcome is ReaderSessionLifecycle.OpenOutcome.Ready)
        val ms = lifecycle.matchedSync.value
        assertNotNull(ms)
        assertNull(ms!!.readerSync)
        assertNull(ms.audiobookFollow)
        assertEquals("srv-abs", ms.sourceId)
    }

    @Test
    fun `tryClaimCloseSync is idempotent until resetCloseSync`() = runTest {
        val (lifecycle, _) = makeLifecycle()
        lifecycle.open(params())
        assertTrue(lifecycle.tryClaimCloseSync())
        assertFalse(lifecycle.tryClaimCloseSync())
        lifecycle.resetCloseSync()
        assertTrue(lifecycle.tryClaimCloseSync())
    }

    @Test
    fun `onCleared releases openReconcile claim`() = runTest {
        val reconcile = OpenReconcileTargets()
        val (lifecycle, _) = makeLifecycle(openReconcileTargets = reconcile)
        lifecycle.open(params())
        assertTrue(reconcile.isOpen("srv-abs", "item-1"))
        lifecycle.onCleared()
        assertFalse(reconcile.isOpen("srv-abs", "item-1"))
        assertNull(lifecycle.publication.value)
    }
}
