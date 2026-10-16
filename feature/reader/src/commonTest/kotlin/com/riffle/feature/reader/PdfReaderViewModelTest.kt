package com.riffle.feature.reader

import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.common.Clock
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.BookFormattingOverrides
import com.riffle.core.domain.BookFormattingPreferencesStore
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.ReadingSpeedStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.WakeLockPreferencesStore
import com.riffle.core.domain.appearance.AppearanceCoordinator
import com.riffle.core.domain.appearance.ChromeTheme
import com.riffle.core.domain.appearance.ConcreteReaderTheme
import com.riffle.core.domain.appearance.ResolvedAppearance
import com.riffle.core.domain.usecase.UpdateReadingProgress
import com.riffle.core.models.Annotation
import com.riffle.core.models.EmbeddedFigure
import com.riffle.core.models.ProgressSyncCycleResult
import com.riffle.core.models.ScreenDimensionBucket
import com.riffle.core.models.SessionPayload
import com.riffle.core.models.Source
import com.riffle.core.models.SyncSessionResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@kotlin.OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PdfReaderViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() { Dispatchers.setMain(testDispatcher) }

    @AfterTest
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun onNavigatorReadyTransitionsStateToReady() = runTest(testDispatcher) {
        val vm = buildVm()
        runCurrent()
        assertIs<PdfReaderState.AwaitingNavigator>(vm.state.value)

        vm.onNavigatorReady(totalPages = 10, outline = emptyList())
        runCurrent()

        assertIs<PdfReaderState.Ready>(vm.state.value)
        assertEquals(10, (vm.state.value as PdfReaderState.Ready).totalPages)
        vm.onReaderClosed()
    }

    @Test
    fun updateFormattingPersistsToBookOverrideStore() = runTest(testDispatcher) {
        val bookStore = FakePdfBookFormattingStore()
        val vm = buildVm(bookFormattingStore = bookStore, navSourceId = "src1")
        runCurrent()
        vm.onNavigatorReady(totalPages = 10, outline = emptyList())
        runCurrent()

        vm.updateFormatting(FormattingPreferences(margins = 3.0f))
        runCurrent()

        assertEquals(3.0f, bookStore.captured("src1", "item1")?.margins)
        vm.onReaderClosed()
    }

    @Test
    fun resetToGlobalDefaultsClearsBookOverrides() = runTest(testDispatcher) {
        val bookStore = FakePdfBookFormattingStore()
        val vm = buildVm(bookFormattingStore = bookStore, navSourceId = "src1")
        runCurrent()
        vm.onNavigatorReady(totalPages = 10, outline = emptyList())
        runCurrent()

        vm.updateFormatting(FormattingPreferences(margins = 3.0f))
        runCurrent()
        assertTrue(vm.hasBookOverrides.value)

        vm.resetToGlobalDefaults()
        runCurrent()

        assertFalse(vm.hasBookOverrides.value)
        vm.onReaderClosed()
    }

    @Test
    fun toggleBookmarkCreatesAndDeletesBookmark() = runTest(testDispatcher) {
        val annotationStore = FakePdfAnnotationStore()
        val vm = buildVm(annotationStore = annotationStore, navSourceId = "src1")
        runCurrent()
        vm.onNavigatorReady(totalPages = 10, outline = emptyList())
        vm.onPageChanged(page0Based = 3, totalPages = 10, progression = 0.3f)
        runCurrent()

        vm.toggleBookmark()
        runCurrent()
        assertEquals(1, annotationStore.bookmarkCount)

        vm.toggleBookmark()
        runCurrent()
        assertEquals(0, annotationStore.bookmarkCount)
        vm.onReaderClosed()
    }

    @Test
    fun pageChangeGateSkipsFirstCallAfterResumed() = runTest(testDispatcher) {
        val pdfRepo = FakePdfRepository()
        val vm = buildVm(pdfRepo = pdfRepo, navSourceId = "src1")
        runCurrent()
        vm.onNavigatorReady(totalPages = 10, outline = emptyList())
        runCurrent()

        vm.onReaderResumed()
        vm.onPageChanged(page0Based = 5, totalPages = 10, progression = 0.5f)
        runCurrent()
        assertEquals(0, pdfRepo.savedPositions.size)

        vm.onPageChanged(page0Based = 6, totalPages = 10, progression = 0.6f)
        runCurrent()
        assertEquals(1, pdfRepo.savedPositions.size)
        vm.onReaderClosed()
    }

    // ---- Helpers -----------------------------------------------------------

    private fun buildVm(
        pdfRepo: FakePdfRepository = FakePdfRepository(),
        bookFormattingStore: FakePdfBookFormattingStore = FakePdfBookFormattingStore(),
        annotationStore: AnnotationStore = FakePdfAnnotationStore(),
        navSourceId: String? = null,
    ): PdfReaderViewModel {
        val appScope = FakePdfApplicationScope(testDispatcher)
        return PdfReaderViewModel(
            itemId = "item1",
            navSourceId = navSourceId,
            sharedPdfRepository = pdfRepo,
            readingSessionRepository = PdfFakeReadingSessionRepository(),
            updateReadingProgressUseCase = PdfNoopUpdateReadingProgress(),
            wakeLockPreferencesStore = PdfFakeWakeLockStore(),
            volumeNavigationController = VolumeNavigationController(),
            volumeKeyDispatcher = VolumeKeyDispatcher(PdfFakeVolumeKeyPreferencesStore(), VolumeNavigationController()),
            readerStateHolder = ReaderStateHolder(),
            annotationStore = annotationStore,
            formattingPreferencesStore = FakePdfFormattingPreferencesStore(),
            bookFormattingPreferencesStore = bookFormattingStore,
            appearanceCoordinator = PdfFakeAppearanceCoordinator(),
            progressFlushScope = ProgressFlushScope(appScope),
            sourceRepository = FakePdfSourceRepository(navSourceId),
            clock = FakePdfClock(),
            readingSpeedStore = FakePdfReadingSpeedStore(),
            dispatchers = PdfFakeDispatcherProvider(testDispatcher),
            catalogRegistry = FakePdfCatalogRegistry(),
        )
    }
}

// ---- Fakes -----------------------------------------------------------------
// Names are prefixed with "Pdf" to avoid Kotlin/Native redeclaration conflicts
// with same-named private fakes in CbzReaderViewModelCloseTest.kt.

private class FakePdfRepository : SharedPdfRepository {
    val savedPositions = mutableListOf<Triple<String, String, String>>()
    override suspend fun loadItem(itemId: String, sourceId: String?): PdfItemResult =
        PdfItemResult.Found(title = "Test PDF", initialPage = 0)
    override suspend fun saveReadingPosition(sourceId: String, itemId: String, position: String) {
        savedPositions += Triple(sourceId, itemId, position)
    }
}

private class PdfFakeReadingSessionRepository : ReadingSessionRepository {
    override suspend fun syncProgress(itemId: String, payload: SessionPayload) = SyncSessionResult.Success
    override suspend fun runSyncCycle(itemId: String, payload: SessionPayload, sourceId: String?): ProgressSyncCycleResult =
        ProgressSyncCycleResult.InSync
    override suspend fun markFinished(sourceId: String, itemId: String, finished: Boolean) {}
    override suspend fun touchOpenTimestamp(itemId: String) {}
}

private class PdfNoopUpdateReadingProgress : UpdateReadingProgress(object : com.riffle.core.domain.LibraryMutator {
    override suspend fun markItemOpened(itemId: String) {}
    override suspend fun currentReadingProgress(itemId: String): Float? = null
    override suspend fun currentReadingProgress(sourceId: String, itemId: String): Float? = null
    override suspend fun updateReadingProgress(itemId: String, progress: Float) {}
    override suspend fun updateReadingProgress(sourceId: String, itemId: String, progress: Float) {}
    override suspend fun deleteItem(sourceId: String, itemId: String) {}
}) {}

private class PdfFakeWakeLockStore : WakeLockPreferencesStore {
    override val keepScreenOn: Flow<Boolean> = flowOf(false)
    override suspend fun setKeepScreenOn(enabled: Boolean) {}
}

private class PdfFakeVolumeKeyPreferencesStore : com.riffle.core.domain.VolumeKeyPreferencesStore {
    override val volumeKeyNavigationEnabled: Flow<Boolean> = flowOf(true)
    override val invertVolumeKeys: Flow<Boolean> = flowOf(false)
    override suspend fun setVolumeKeyNavigationEnabled(value: Boolean) {}
    override suspend fun setInvertVolumeKeys(value: Boolean) {}
}

private class FakePdfAnnotationStore : AnnotationStore {
    var bookmarkCount = 0
    private val storedBookmarks = mutableMapOf<String, String>()
    private var nextId = 1
    private val _annotations = MutableStateFlow<List<Annotation>>(emptyList())

    override fun observeHighlights(sourceId: String, itemId: String): Flow<List<Annotation>> = flowOf(emptyList())
    override fun observeBookmarks(sourceId: String, itemId: String): Flow<List<Annotation>> = _annotations
    override fun observeAnnotations(sourceId: String, itemId: String): Flow<List<Annotation>> = _annotations
    override fun observeAnnotationsForSource(sourceId: String): Flow<List<Annotation>> = flowOf(emptyList())

    override suspend fun createBookmark(
        sourceId: String,
        itemId: String,
        cfi: String,
        textSnippet: String,
        chapterHref: String,
        spineIndex: Int,
        progression: Double,
        bookmarkTitle: String,
        originFontFamily: String,
        fragmentAnchor: String?,
    ): Annotation {
        val id = "bm-${nextId++}"
        storedBookmarks[id] = cfi
        bookmarkCount++
        _annotations.value = storedBookmarks.map { (bmId, bmCfi) ->
            fakeAnnotation(id = bmId, sourceId = sourceId, itemId = itemId, cfi = bmCfi)
        }
        return fakeAnnotation(id = id, sourceId = sourceId, itemId = itemId, cfi = cfi)
    }

    override suspend fun delete(id: String) {
        if (storedBookmarks.remove(id) != null) bookmarkCount--
        _annotations.value = _annotations.value.filter { it.id != id }
    }

    override suspend fun createHighlight(
        sourceId: String,
        itemId: String,
        cfi: String,
        textSnippet: String,
        chapterHref: String,
        textBefore: String,
        textAfter: String,
        color: String,
        spineIndex: Int,
        progression: Double,
        embeddedFigures: List<EmbeddedFigure>?,
        originFontFamily: String,
        textSnippetHtml: String?,
    ): Annotation = throw UnsupportedOperationException()

    override suspend fun createImageAnnotation(
        sourceId: String,
        itemId: String,
        cfi: String,
        textSnippet: String,
        chapterHref: String,
        spineIndex: Int,
        progression: Double,
        imageHref: String?,
        imageSvg: String?,
        imageBytes: String?,
        color: String,
    ): Annotation = throw UnsupportedOperationException()

    override suspend fun backfillNullOriginFontFamily(sourceId: String, itemId: String, fontFamily: String) = 0
    override suspend fun healSentinelOriginFontFamily(sourceId: String, itemId: String, sentinel: String, fontFamily: String) = 0
    override suspend fun upgradeImageToCaptionHighlight(
        id: String, cfi: String, textSnippet: String, textBefore: String, textAfter: String, figure: EmbeddedFigure,
    ): Annotation? = null
    override suspend fun mergeFiguresIntoHighlight(id: String, newFigures: List<EmbeddedFigure>): Annotation? = null
    override suspend fun recolor(id: String, color: String) {}
    override suspend fun updateNote(id: String, note: String?) {}
    override suspend fun renameBookmark(id: String, title: String) {}
    override suspend fun findByItemAndCfi(sourceId: String, itemId: String, cfi: String): Annotation? = null
    override suspend fun findImageAnnotationForFigure(
        sourceId: String, itemId: String, chapterHref: String, imageHref: String?, imageSvg: String?,
    ): Annotation? = null

    private fun fakeAnnotation(id: String, sourceId: String, itemId: String, cfi: String): Annotation =
        Annotation(
            id = id, sourceId = sourceId, itemId = itemId,
            type = com.riffle.core.database.AnnotationEntity.TYPE_BOOKMARK,
            cfi = cfi, color = "", note = null, textSnippet = "", textBefore = "", textAfter = "",
            chapterHref = "", spineIndex = 0, progression = 0.0, bookmarkTitle = "Page",
            createdAt = 0L, updatedAt = 0L,
        )
}

private class FakePdfFormattingPreferencesStore : FormattingPreferencesStore {
    private val _flow = MutableStateFlow(FormattingPreferences())
    override val preferences: Flow<FormattingPreferences> = _flow
    override suspend fun update(preferences: FormattingPreferences) { _flow.value = preferences }
    override suspend fun setCadencePlatformSupported(supported: Boolean) {}
}

private class FakePdfBookFormattingStore : BookFormattingPreferencesStore {
    private val saved = mutableMapOf<Triple<String, String, ScreenDimensionBucket>, BookFormattingOverrides>()
    override suspend fun load(sourceId: String, itemId: String, dimension: ScreenDimensionBucket) =
        saved[Triple(sourceId, itemId, dimension)]
    override suspend fun save(sourceId: String, itemId: String, dimension: ScreenDimensionBucket, overrides: BookFormattingOverrides) {
        saved[Triple(sourceId, itemId, dimension)] = overrides
    }
    override suspend fun clear(sourceId: String, itemId: String, dimension: ScreenDimensionBucket) {
        saved.remove(Triple(sourceId, itemId, dimension))
    }
    fun captured(sourceId: String, itemId: String, dimension: ScreenDimensionBucket = ScreenDimensionBucket.PhonePortrait): BookFormattingOverrides? =
        saved[Triple(sourceId, itemId, dimension)]
}

private class PdfFakeAppearanceCoordinator : AppearanceCoordinator {
    override val resolved: StateFlow<ResolvedAppearance> = MutableStateFlow(
        ResolvedAppearance(ChromeTheme.Light, ConcreteReaderTheme.Light, false)
    )
    override fun setSystemDark(isDark: Boolean) {}
}

private class FakePdfSourceRepository(private val activeSourceId: String?) : SourceRepository {
    override fun observeAll(): Flow<List<Source>> = flowOf(emptyList())
    override suspend fun getActive(): Source? = null
    override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
        CommitSourceResult.Failure(UnsupportedOperationException())
    override suspend fun setActive(sourceId: String) {}
    override suspend fun remove(sourceId: String) {}
    override suspend fun getSourceVersion(sourceId: String): String? = null
}

private class FakePdfClock : Clock {
    override fun nowMs(): Long = 0L
    override fun nowNs(): Long = 0L
}

private class FakePdfReadingSpeedStore : ReadingSpeedStore {
    override val speedSecPerPosition: Flow<Double> = flowOf(0.5)
    override suspend fun updateSpeed(newSecPerPosition: Double) {}
}

private class PdfFakeDispatcherProvider(private val dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val main = dispatcher
    override val mainImmediate = dispatcher
    override val io = dispatcher
    override val default = dispatcher
}

private class FakePdfCatalogRegistry : CatalogRegistry {
    override suspend fun forActive(): Catalog? = null
    override suspend fun forSource(source: Source): Catalog? = null
    override suspend fun forSourceId(sourceId: String): Catalog? = null
}

private class FakePdfApplicationScope(dispatcher: CoroutineDispatcher) : ApplicationScope {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    override val coroutineScope: CoroutineScope = scope
    override fun launchSurvivable(block: suspend CoroutineScope.() -> Unit): Job =
        scope.launch { block.invoke(this) }
    override suspend fun <T> withSurvivable(block: suspend CoroutineScope.() -> T): T =
        kotlinx.coroutines.coroutineScope { block.invoke(this) }
}
