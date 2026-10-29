package com.riffle.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.ReadingSessionsCapability
import com.riffle.core.common.Clock
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.BookFormattingOverrides
import com.riffle.core.domain.BookFormattingPreferencesStore
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.ProgressSyncController
import com.riffle.core.domain.ReadingSessionCoordinator
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.ReadingSpeedSession
import com.riffle.core.domain.ReadingSpeedStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.WakeLockPreferencesStore
import com.riffle.core.domain.appearance.AppearanceCoordinator
import com.riffle.core.domain.appearance.withResolvedTheme
import com.riffle.core.domain.usecase.UpdateReadingProgress
import com.riffle.core.models.Annotation
import com.riffle.core.models.ScreenDimensionBucket
import com.riffle.core.models.SessionPayload
import com.riffle.core.models.TocEntry
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * Shared PDF reader ViewModel (Android + iOS).
 *
 * Owns session state, annotations, formatting, sync and lifecycle for a PDF open.
 * The platform navigator (Pdfium on Android, PDFKit on iOS) is controlled by the screen
 * via [serverPageEvents] and [onPageChanged] / [onNavigatorReady] callbacks.
 */
class PdfReaderViewModel constructor(
    private val itemId: String,
    private val navSourceId: String? = null,
    private val sharedPdfRepository: SharedPdfRepository,
    private val readingSessionRepository: ReadingSessionRepository,
    private val updateReadingProgressUseCase: UpdateReadingProgress,
    private val wakeLockPreferencesStore: WakeLockPreferencesStore,
    val volumeNavigationController: VolumeNavigationController,
    private val volumeKeyDispatcher: VolumeKeyDispatcher,
    private val readerStateHolder: ReaderStateHolder,
    private val annotationStore: AnnotationStore,
    private val formattingPreferencesStore: FormattingPreferencesStore,
    private val bookFormattingPreferencesStore: BookFormattingPreferencesStore,
    private val appearanceCoordinator: AppearanceCoordinator,
    private val progressFlushScope: ProgressFlushScope,
    private val sourceRepository: SourceRepository,
    private val clock: Clock,
    private val readingSpeedStore: ReadingSpeedStore,
    @Suppress("UNUSED_PARAMETER") dispatchers: DispatcherProvider,
    private val catalogRegistry: CatalogRegistry,
) : ViewModel() {

    @Volatile private var readingSessionsEnabled: Boolean = false
    @Volatile private var closeSyncDone: Boolean = false

    private val _state = MutableStateFlow<PdfReaderState>(PdfReaderState.Loading)
    val state: StateFlow<PdfReaderState> = _state

    // ---- Formatting -------------------------------------------------------

    private val _globalPrefs = MutableStateFlow(FormattingPreferences())
    private val _bookOverrides = MutableStateFlow(BookFormattingOverrides())

    val formattingPreferences: StateFlow<FormattingPreferences> = combine(
        _globalPrefs, _bookOverrides,
    ) { global, overrides -> overrides.applyTo(global) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, FormattingPreferences())

    val effectiveFormattingPreferences: StateFlow<FormattingPreferences> = combine(
        formattingPreferences, appearanceCoordinator.resolved,
    ) { prefs, appearance -> prefs.withResolvedTheme(appearance) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, FormattingPreferences())

    val hasBookOverrides: StateFlow<Boolean> = _bookOverrides
        .map { !it.isEmpty }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ---- WakeLock / volume ------------------------------------------------

    val keepScreenOn: StateFlow<Boolean> = wakeLockPreferencesStore.keepScreenOn
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val volumeNavEvents: SharedFlow<VolumeNavEvent> = volumeNavigationController.events

    val volumeKeyNavigationEnabled: StateFlow<Boolean> = volumeKeyDispatcher.volumeKeyNavigationEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val invertVolumeKeys: StateFlow<Boolean> = volumeKeyDispatcher.invertVolumeKeys
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ---- Sync / session ---------------------------------------------------

    private val _syncErrorEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val syncErrorEvents: SharedFlow<Unit> = _syncErrorEvents.asSharedFlow()

    private val syncSession = ProgressSyncController(
        itemId = itemId,
        repository = readingSessionRepository,
        scope = viewModelScope,
        onSyncError = { _syncErrorEvents.tryEmit(Unit) },
    )

    private val readingSessionCoordinator = ReadingSessionCoordinator(
        scope = viewModelScope,
        enabled = { readingSessionsEnabled },
    )

    private val readingSpeedSession = ReadingSpeedSession(
        clock = clock,
        readingSpeedStore = readingSpeedStore,
        scope = viewModelScope,
        enabled = { readingSessionsEnabled },
    )

    // ---- Page / TOC / rail -----------------------------------------------

    private val _currentPage = MutableStateFlow<Int?>(null)
    /** 0-based current page index. */
    val currentPage: StateFlow<Int?> = _currentPage

    private val _railSegments = MutableStateFlow<List<RailSegment>>(emptyList())
    val railSegments: StateFlow<List<RailSegment>> = _railSegments

    private val _toc = MutableStateFlow<List<TocEntry>>(emptyList())
    val toc: StateFlow<List<TocEntry>> = _toc

    private var totalPages: Int = 0

    val activeRailSegmentIndex: StateFlow<Int> = combine(_railSegments, _currentPage) { segs, page ->
        if (segs.isEmpty()) 0
        else findActivePdfSegmentIndex(segs, (page ?: 0).coerceAtLeast(0))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val railCursorPosition: StateFlow<Float> = combine(_railSegments, _currentPage) { segs, page ->
        if (segs.isEmpty() || totalPages <= 0) 0f
        else {
            val zeroPage = (page ?: 0).coerceAtLeast(0)
            val active = findActivePdfSegmentIndex(segs, zeroPage)
            val withinSeg = pdfProgressionWithinActiveSegment(
                segments = segs,
                activeIndex = active,
                currentPageIndex = zeroPage,
                intraPageOffset = 0f,
                totalPages = totalPages,
            )
            weightedRailCursorPosition(active, segs, withinSeg)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

    // ---- Panel visibility -------------------------------------------------

    private val _tocVisible = MutableStateFlow(false)
    val tocVisible: StateFlow<Boolean> = _tocVisible

    private val _annotationsPanelVisible = MutableStateFlow(false)
    val annotationsPanelVisible: StateFlow<Boolean> = _annotationsPanelVisible

    // ---- Annotations ------------------------------------------------------

    private var annotationSourceId: String? = null

    private val _annotations = MutableStateFlow<List<Annotation>>(emptyList())
    val annotations: StateFlow<List<Annotation>> = _annotations

    private val _bookmarks = MutableStateFlow<List<Annotation>>(emptyList())

    val currentPageBookmarked: StateFlow<Boolean> = combine(_bookmarks, _currentPage) { bookmarks, page ->
        val p = page ?: return@combine false
        bookmarks.any { PdfLocatorCodec.decode0Based(it.cfi) == p }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ---- Navigation -------------------------------------------------------

    private val _serverPageChannel = Channel<Int>(Channel.CONFLATED)
    /** Emits 0-based page index targets when the server or TOC/rail requests navigation. */
    val serverPageEvents: Flow<Int> = _serverPageChannel.receiveAsFlow()

    private val pageChangeGate = PdfPageChangeGate()
    private var lastPage: Int = 0
    private var lastProgression: Float = 0f

    private val positionSaveCoordinator = PositionSaveCoordinator<String>(
        savePosition = { locatorJson ->
            val sid = navSourceId ?: sourceRepository.getActive()?.id
            if (sid != null) sharedPdfRepository.saveReadingPosition(sid, itemId, locatorJson)
        },
        updateProgress = { progress ->
            val sid = navSourceId ?: sourceRepository.getActive()?.id
            if (sid != null) updateReadingProgressUseCase(sid, itemId, progress)
            else updateReadingProgressUseCase(itemId, progress)
        },
    )

    private val _screenDimensionBucket = MutableStateFlow<ScreenDimensionBucket?>(null)

    // ---- Init -------------------------------------------------------------

    init {
        viewModelScope.launch {
            val bookSource = navSourceId?.let { sourceRepository.getById(it) }
                ?: sourceRepository.getActive()
            val catalog = bookSource?.let { catalogRegistry.forSource(it) }
            readingSessionsEnabled = catalog is ReadingSessionsCapability
        }
        viewModelScope.launch {
            formattingPreferencesStore.preferences.collect { _globalPrefs.value = it }
        }
        viewModelScope.launch {
            loadItemAndSetupState()
        }
        viewModelScope.launch {
            syncSession.serverPositionEvents.collect { serverProgress ->
                val page = PdfLocatorCodec.decode0Based(serverProgress.ebookLocation) ?: return@collect
                lastPage = page
                _currentPage.value = page
                _serverPageChannel.trySend(page)
            }
        }
    }

    /** Must be called from the UI before or shortly after the reader opens. */
    fun setScreenDimensionBucket(bucket: ScreenDimensionBucket) {
        _screenDimensionBucket.value = bucket
    }

    private suspend fun loadItemAndSetupState() {
        val bucket = _screenDimensionBucket.filterNotNull().first()
        val formattingSourceId = navSourceId ?: sourceRepository.getActive()?.id
        if (formattingSourceId != null) {
            val overrides = bookFormattingPreferencesStore.load(
                sourceId = formattingSourceId,
                itemId = itemId,
                dimension = bucket,
            )
            if (overrides != null) _bookOverrides.value = overrides
        }

        when (val result = sharedPdfRepository.loadItem(itemId, navSourceId)) {
            is PdfItemResult.Found -> {
                lastPage = result.initialPage
                _currentPage.value = result.initialPage
                annotationSourceId = navSourceId ?: sourceRepository.getActive()?.id
                startObservingAnnotations()
                _state.value = PdfReaderState.AwaitingNavigator(result.title, result.initialPage)
            }
            is PdfItemResult.BookNotFound -> _state.value = PdfReaderState.BookNotFound
            is PdfItemResult.NetworkError -> _state.value = PdfReaderState.Error(result.message)
            is PdfItemResult.Offline -> _state.value = PdfReaderState.Error("Book not available offline")
        }
    }

    /**
     * Called by the screen once the platform navigator has opened the PDF and can report metadata.
     */
    fun onNavigatorReady(totalPages: Int, outline: List<PdfOutlineNode>) {
        this.totalPages = totalPages
        _toc.value = pdfOutlineToTocEntries(outline)
        _railSegments.value = buildPdfRailSegments(
            pdfTocEntries = pdfOutlineToFlatRailEntries(outline),
            totalPages = totalPages,
        )
        val ready = _state.value as? PdfReaderState.AwaitingNavigator ?: return
        _state.value = PdfReaderState.Ready(ready.title, totalPages)
        val progression = if (totalPages > 0) lastPage.toFloat() / totalPages else 0f
        syncCurrentPosition(lastPage, progression)
        readingSpeedSession.onResumed(initialTotalProgression = null)
        readingSessionCoordinator.onResumed(onTick = { syncCurrentPosition(lastPage, lastProgression) })
    }

    // ---- Public API -------------------------------------------------------

    fun onPageChanged(page0Based: Int, totalPages: Int, progression: Float) {
        if (this.totalPages != totalPages && totalPages > 0) this.totalPages = totalPages
        _currentPage.value = page0Based
        lastPage = page0Based
        lastProgression = progression
        if (!pageChangeGate.advance()) return
        viewModelScope.launch {
            val locatorJson = PdfLocatorCodec.encode(page0Based, totalPages)
            positionSaveCoordinator.onChanged(locatorJson)
        }
    }

    fun onReaderResumed() {
        readerStateHolder.isReaderActive = true
        closeSyncDone = false
        pageChangeGate.reset()
        if (_state.value is PdfReaderState.Ready) {
            syncCurrentPosition(lastPage, lastProgression)
            readingSpeedSession.onResumed(initialTotalProgression = null)
            readingSessionCoordinator.onResumed(onTick = { syncCurrentPosition(lastPage, lastProgression) })
        }
    }

    fun onReaderClosed() {
        readerStateHolder.isReaderActive = false
        readerStateHolder.isPanelOpen = false
        readingSessionCoordinator.onClosed()
        readingSpeedSession.onClosed(currentTotalProgression = null, totalPositions = 0f)
        if (closeSyncDone) return
        closeSyncDone = true
        val capturedPage = lastPage
        val capturedTotal = totalPages
        val capturedProgression = lastProgression
        val capturedSourceId = navSourceId
        progressFlushScope.flush {
            val sid = capturedSourceId ?: sourceRepository.getActive()?.id ?: return@flush
            val locatorJson = PdfLocatorCodec.encode(capturedPage, capturedTotal)
            sharedPdfRepository.saveReadingPosition(sid, itemId, locatorJson)
            positionSaveCoordinator.onClose(capturedProgression)
        }
        viewModelScope.launch {
            val payload = SessionPayload(
                ebookLocation = PdfLocatorCodec.encode(capturedPage, capturedTotal),
                ebookProgress = capturedProgression,
            )
            syncSession.sync(payload, navSourceId)
        }
    }

    fun navigateToEntry(entry: TocEntry) {
        val pageIdx = entry.href.removePrefix("page=").toIntOrNull() ?: return
        _serverPageChannel.trySend(pageIdx)
        closeToc()
    }

    fun navigateToSegment(segment: RailSegment) {
        val pageIdx = segment.href.removePrefix("page=").toIntOrNull() ?: return
        _serverPageChannel.trySend(pageIdx)
    }

    fun navigateToAnnotation(id: String) {
        val cfi = _annotations.value.firstOrNull { it.id == id }?.cfi ?: return
        val page = PdfLocatorCodec.decode0Based(cfi) ?: return
        _serverPageChannel.trySend(page)
        closeAnnotationsPanel()
    }

    fun toggleBookmark() {
        val srcId = annotationSourceId ?: return
        val page = lastPage
        val total = totalPages
        viewModelScope.launch {
            val existing = _bookmarks.value.firstOrNull { PdfLocatorCodec.decode0Based(it.cfi) == page }
            if (existing != null) {
                annotationStore.delete(existing.id)
            } else {
                val progression = if (total > 0) page.toDouble() / total else 0.0
                val locatorJson = PdfLocatorCodec.encode(page, total)
                annotationStore.createBookmark(
                    sourceId = srcId,
                    itemId = itemId,
                    cfi = locatorJson,
                    textSnippet = "",
                    chapterHref = PdfLocatorCodec.pageHref(page),
                    spineIndex = 0,
                    progression = progression,
                    bookmarkTitle = "Page ${page + 1}",
                    originFontFamily = "serif",
                )
            }
        }
    }

    fun deleteAnnotation(id: String) {
        viewModelScope.launch { annotationStore.delete(id) }
    }

    fun renameBookmark(id: String, title: String) {
        viewModelScope.launch { annotationStore.renameBookmark(id, title) }
    }

    fun updateFormatting(prefs: FormattingPreferences) {
        val previous = formattingPreferences.value
        val updatedOverrides = _bookOverrides.value.withChanges(previous, prefs)
        _bookOverrides.value = updatedOverrides
        val sid = navSourceId
        val bucket = _screenDimensionBucket.value
        viewModelScope.launch {
            formattingPreferencesStore.update(prefs)
            if (sid != null && bucket != null) {
                bookFormattingPreferencesStore.save(sid, itemId, bucket, updatedOverrides)
            }
        }
    }

    fun resetToGlobalDefaults() {
        _bookOverrides.value = BookFormattingOverrides()
        val sid = navSourceId
        val bucket = _screenDimensionBucket.value
        viewModelScope.launch {
            if (sid != null && bucket != null) {
                bookFormattingPreferencesStore.clear(sid, itemId, bucket)
            }
        }
    }

    fun setKeepScreenOn(value: Boolean) {
        viewModelScope.launch { wakeLockPreferencesStore.setKeepScreenOn(value) }
    }

    fun setVolumeKeyNavigationEnabled(value: Boolean) {
        viewModelScope.launch { volumeKeyDispatcher.setVolumeKeyNavigationEnabled(value) }
    }

    fun setInvertVolumeKeys(value: Boolean) {
        viewModelScope.launch { volumeKeyDispatcher.setInvertVolumeKeys(value) }
    }

    fun openToc() { _tocVisible.value = true }
    fun closeToc() { _tocVisible.value = false }
    fun openAnnotationsPanel() { _annotationsPanelVisible.value = true }
    fun closeAnnotationsPanel() { _annotationsPanelVisible.value = false }

    // ---- Private helpers --------------------------------------------------

    private fun startObservingAnnotations() {
        val src = annotationSourceId ?: return
        viewModelScope.launch {
            annotationStore.observeAnnotations(src, itemId).collect { _annotations.value = it }
        }
        viewModelScope.launch {
            annotationStore.observeBookmarks(src, itemId).collect { _bookmarks.value = it }
        }
    }

    private fun syncCurrentPosition(page: Int, progression: Float) {
        val payload = SessionPayload(
            ebookLocation = PdfLocatorCodec.encode(page, totalPages),
            ebookProgress = progression,
        )
        syncSession.sync(payload, navSourceId)
    }
}
