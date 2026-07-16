package com.riffle.app.feature.reader.cbz

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.riffle.app.feature.reader.ReaderStateHolder
import com.riffle.app.feature.reader.VolumeNavEvent
import com.riffle.app.feature.reader.VolumeNavigationController
import com.riffle.app.feature.reader.controllers.VolumeKeyDispatcher
import com.riffle.core.domain.CbzOpenResult
import com.riffle.core.domain.CbzRepository
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.ProgressSyncController
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.SessionPayload
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.WakeLockPreferencesStore
import com.riffle.core.domain.comic.CbzArchive
import com.riffle.core.domain.comic.ComicArchive
import com.riffle.core.domain.comic.panel.PagePanels
import com.riffle.core.domain.comic.panel.PanelOrchestrator
import com.riffle.core.domain.comic.panel.PanelViewPreferencesStore
import com.riffle.core.domain.usecase.UpdateReadingProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Backs the comic (CBZ) reader. Deliberately smaller than [com.riffle.app.feature.reader.PdfReaderViewModel]:
 * comics don't go through Readium — page images come straight out of a [CbzArchive] — so there's no
 * Publication, no navigator, no formatting session, no annotations, no TOC.
 *
 * v1 scope per ADR 0042; Panel View overlay per ADR 0043 (opt-in per-book toggle that frames one
 * panel at a time using auto-detected regions).
 */
@HiltViewModel
class CbzReaderViewModel @Inject constructor(
    application: Application,
    savedStateHandle: SavedStateHandle,
    private val libraryObserver: LibraryObserver,
    private val cbzRepository: CbzRepository,
    private val readingSessionRepository: ReadingSessionRepository,
    private val updateReadingProgressUseCase: UpdateReadingProgress,
    private val wakeLockPreferencesStore: WakeLockPreferencesStore,
    private val volumeNavigationController: VolumeNavigationController,
    private val volumeKeyDispatcher: VolumeKeyDispatcher,
    private val readerStateHolder: ReaderStateHolder,
    private val sourceRepository: SourceRepository,
    private val panelOrchestrator: PanelOrchestrator,
    private val panelViewPreferencesStore: PanelViewPreferencesStore,
) : AndroidViewModel(application) {

    private val itemId: String = checkNotNull(savedStateHandle["itemId"])

    private var archive: ComicArchive? = null
    private var lastSavedPage: Int = -1
    private var closeSyncDone: Boolean = false
    private var bookId: String = itemId
    private var panelBook: PanelOrchestrator.Book? = null

    private val syncSession = ProgressSyncController(
        itemId = itemId,
        repository = readingSessionRepository,
        scope = viewModelScope,
    )

    private val _state = MutableStateFlow<CbzReaderState>(CbzReaderState.Loading)
    val state: StateFlow<CbzReaderState> = _state

    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage

    private val _panelViewOn = MutableStateFlow(false)
    /** Panel View toggle for the current book (ADR 0043). */
    val panelViewOn: StateFlow<Boolean> = _panelViewOn

    private val _currentPagePanels = MutableStateFlow<PagePanels?>(null)
    /**
     * Panels resolved for the currently-displayed page (row-band ordered). Null while the
     * detector is still working. When Panel View is off, this is still populated in the
     * background so re-enabling is instant.
     */
    val currentPagePanels: StateFlow<PagePanels?> = _currentPagePanels

    private val _currentPanelIndex = MutableStateFlow(0)
    /** 0-based index into [currentPagePanels]. Advances with next/prev-panel gestures. */
    val currentPanelIndex: StateFlow<Int> = _currentPanelIndex

    val keepScreenOn: StateFlow<Boolean> = wakeLockPreferencesStore.keepScreenOn
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val volumeNavEvents: SharedFlow<VolumeNavEvent> = volumeNavigationController.events

    val volumeKeyNavigationEnabled = volumeKeyDispatcher.volumeKeyNavigationEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val invertVolumeKeys = volumeKeyDispatcher.invertVolumeKeys
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch { openBook() }
        // Collect server-wins events from the sync cycle and jump to the server's page.
        viewModelScope.launch {
            syncSession.serverPositionEvents.collect { serverProgress ->
                val page = parsePageIndex(serverProgress.ebookLocation) ?: return@collect
                val ready = _state.value as? CbzReaderState.Ready ?: return@collect
                val clamped = page.coerceIn(0, ready.pageCount - 1)
                if (clamped != _currentPage.value) {
                    // ADR 0043: remote wins the page → discard local panel-index resume.
                    panelViewPreferencesStore.clearPanelResume(bookId)
                    _currentPage.value = clamped
                    _currentPanelIndex.value = 0
                    lastSavedPage = clamped
                    onCurrentPageChanged(clamped)
                    val progressFraction = if (ready.pageCount > 1) clamped.toFloat() / (ready.pageCount - 1).toFloat() else 1f
                    viewModelScope.launch { updateReadingProgressUseCase(itemId, progressFraction) }
                }
            }
        }
    }

    private suspend fun openBook() {
        val item = libraryObserver.getItem(itemId)
        if (item == null) {
            _state.value = CbzReaderState.Error("Book not found")
            return
        }
        bookId = "${item.sourceId}::${item.id}"
        // Load persisted Panel View toggle before rendering so the reader opens in the last mode.
        val prefState = panelViewPreferencesStore.state(bookId)
        viewModelScope.launch {
            prefState.collect { state -> _panelViewOn.value = state.panelViewOn }
        }
        when (val result = cbzRepository.openCbz(item)) {
            is CbzOpenResult.Success -> loadArchive(result.cbzFile, result.lastPosition, item.title)
            is CbzOpenResult.NetworkError -> _state.value = CbzReaderState.Error(
                result.cause.message ?: "Network error"
            )
            CbzOpenResult.Offline -> _state.value = CbzReaderState.Error("Book not available offline")
        }
    }

    private suspend fun loadArchive(file: File, lastPosition: String?, title: String) {
        val (opened, pageCount, acbf) = withContext(Dispatchers.IO) {
            val a = CbzArchive(file)
            Triple(a, a.pageCount, a.acbfXml())
        }
        if (pageCount == 0) {
            _state.value = CbzReaderState.Error("Comic has no pages")
            opened.close()
            return
        }
        archive = opened
        val resumeIndex = lastPosition
            ?.let { parsePageIndex(it) }
            ?.coerceIn(0, pageCount - 1)
            ?: 0
        _currentPage.value = resumeIndex
        lastSavedPage = resumeIndex
        // Restore panel resume marker if it matches the page we're landing on.
        val stored = panelViewPreferencesStore.state(bookId).first()
        _currentPanelIndex.value = stored.panelIndexForPage(resumeIndex)

        panelBook = panelOrchestrator.forBook(
            bookId = bookId,
            imageBytes = { pageIndex -> opened.imageBytes(pageIndex) },
            acbfXml = acbf,
            // We don't know per-page image dimensions cheaply here; ACBF is uncommon enough that
            // an empty list is acceptable in v1. Auto-detection paths don't need this.
            pageImageDimensions = emptyList(),
        )
        _state.value = CbzReaderState.Ready(
            title = title,
            pageCount = pageCount,
            imageSource = ArchiveImageSource(opened),
        )
        val payload = lastPosition?.takeIf { it.isNotEmpty() }?.let {
            SessionPayload(ebookLocation = it, ebookProgress = 0f)
        } ?: SessionPayload("", 0f)
        syncSession.sync(payload)
        // Prefetch panels for the resume page and the next two.
        onCurrentPageChanged(resumeIndex)
    }

    private fun parsePageIndex(json: String): Int? = try {
        if (json.startsWith("{")) {
            JSONObject(json).optJSONObject("locations")
                ?.let { if (it.has("position")) it.getInt("position") - 1 else null }
        } else {
            json.trim().toIntOrNull()
        }
    } catch (_: Exception) {
        null
    }

    fun nextPage() {
        val ready = _state.value as? CbzReaderState.Ready ?: return
        val next = (_currentPage.value + 1).coerceAtMost(ready.pageCount - 1)
        if (next != _currentPage.value) {
            _currentPage.value = next
            _currentPanelIndex.value = 0
            onCurrentPageChanged(next)
            savePosition(next, ready.pageCount)
        }
    }

    fun previousPage() {
        val next = (_currentPage.value - 1).coerceAtLeast(0)
        if (next != _currentPage.value) {
            _currentPage.value = next
            _currentPanelIndex.value = 0
            onCurrentPageChanged(next)
            val ready = _state.value as? CbzReaderState.Ready ?: return
            savePosition(next, ready.pageCount)
        }
    }

    fun jumpToPage(index: Int) {
        val ready = _state.value as? CbzReaderState.Ready ?: return
        val clamped = index.coerceIn(0, ready.pageCount - 1)
        if (clamped != _currentPage.value) {
            _currentPage.value = clamped
            _currentPanelIndex.value = 0
            onCurrentPageChanged(clamped)
            savePosition(clamped, ready.pageCount)
        }
    }

    // --- Panel View (ADR 0043) ---

    /** Flip the per-book Panel View toggle. Persisted to [panelViewPreferencesStore]. */
    fun togglePanelView() {
        val newValue = !_panelViewOn.value
        viewModelScope.launch { panelViewPreferencesStore.setPanelViewOn(bookId, newValue) }
    }

    /**
     * Advance to the next panel on the current page, or to the first panel of the next page when
     * the current panel is the last one on the page. When Panel View is off this behaves as
     * [nextPage] so tap-right / swipe / vol-down continue to work as they always did.
     */
    fun nextPanel() {
        if (!_panelViewOn.value) return nextPage()
        val panels = _currentPagePanels.value?.panels
        if (panels.isNullOrEmpty() || _currentPagePanels.value?.isFallback == true) return nextPage()
        val nextIndex = _currentPanelIndex.value + 1
        if (nextIndex < panels.size) {
            _currentPanelIndex.value = nextIndex
            rememberResume()
        } else {
            nextPage()
        }
    }

    fun previousPanel() {
        if (!_panelViewOn.value) return previousPage()
        val panels = _currentPagePanels.value?.panels
        if (panels.isNullOrEmpty() || _currentPagePanels.value?.isFallback == true) return previousPage()
        val prevIndex = _currentPanelIndex.value - 1
        if (prevIndex >= 0) {
            _currentPanelIndex.value = prevIndex
            rememberResume()
        } else {
            // Cross-page backwards: land on the last panel of the previous page. The prefetched
            // panels for that page are consulted after the page change lands.
            previousPage()
            viewModelScope.launch {
                val pagePanels = panelBook?.resolvePage(_currentPage.value) ?: return@launch
                _currentPagePanels.value = pagePanels
                val landingIndex = (pagePanels.panels.size - 1).coerceAtLeast(0)
                _currentPanelIndex.value = landingIndex
                rememberResume()
            }
        }
    }

    /**
     * Escape hatch for a mis-ordered page (long-press peek → "skip guided panels on this page"):
     * jumps directly to the first panel of the next page.
     */
    fun skipGuidedPanelsOnPage() = nextPage()

    private fun onCurrentPageChanged(pageIndex: Int) {
        val book = panelBook ?: return
        val ready = _state.value as? CbzReaderState.Ready
        viewModelScope.launch {
            val current = withContext(Dispatchers.Default) { book.resolvePage(pageIndex) }
            _currentPagePanels.value = current
            // Prefetch the next two pages.
            withContext(Dispatchers.Default) {
                for (offset in 1..2) {
                    val target = pageIndex + offset
                    if (ready != null && target >= ready.pageCount) break
                    runCatching { book.resolvePage(target) }
                }
            }
        }
    }

    private fun rememberResume() {
        val page = _currentPage.value
        val panel = _currentPanelIndex.value
        viewModelScope.launch {
            panelViewPreferencesStore.rememberPositionForResume(bookId, page, panel)
        }
    }

    // --- Position sync ---

    private fun savePosition(pageIndex: Int, pageCount: Int) {
        if (pageIndex == lastSavedPage) return
        lastSavedPage = pageIndex
        val progression = if (pageCount > 0) (pageIndex + 1).toDouble() / pageCount.toDouble() else 0.0
        val locatorJson = JSONObject()
            .put("href", "cbz://page")
            .put("type", "application/vnd.comicbook+zip")
            .put(
                "locations", JSONObject()
                    .put("position", pageIndex + 1)
                    .put("totalProgression", progression),
            )
            .toString()
        viewModelScope.launch {
            cbzRepository.saveReadingPosition(itemId, locatorJson)
            val progressFraction = if (pageCount > 1) pageIndex.toFloat() / (pageCount - 1).toFloat() else 1f
            updateReadingProgressUseCase(itemId, progressFraction)
        }
        rememberResume()
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

    fun onReaderResumed() {
        readerStateHolder.isReaderActive = true
        closeSyncDone = false
    }

    fun onReaderClosed() {
        readerStateHolder.isReaderActive = false
        readerStateHolder.isPanelOpen = false
        if (closeSyncDone) return
        closeSyncDone = true
        val ready = _state.value as? CbzReaderState.Ready ?: return
        savePosition(_currentPage.value, ready.pageCount)
        val page = _currentPage.value + 1
        val progression = if (ready.pageCount > 0) page.toDouble() / ready.pageCount.toDouble() else 0.0
        val locatorJson = JSONObject()
            .put("href", "cbz://page")
            .put("type", "application/vnd.comicbook+zip")
            .put("locations", JSONObject().put("position", page).put("totalProgression", progression))
            .toString()
        syncSession.sync(SessionPayload(ebookLocation = locatorJson, ebookProgress = progression.toFloat()))
    }

    override fun onCleared() {
        super.onCleared()
        archive?.close()
        archive = null
    }
}
