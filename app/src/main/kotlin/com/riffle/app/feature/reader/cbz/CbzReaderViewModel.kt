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
import com.riffle.core.domain.WakeLockPreferencesStore
import com.riffle.core.domain.comic.CbzArchive
import com.riffle.core.domain.comic.ComicArchive
import com.riffle.core.domain.usecase.UpdateReadingProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Backs the comic (CBZ) reader. Deliberately smaller than [com.riffle.app.feature.reader.PdfReaderViewModel]:
 * comics don't go through Readium — page images come straight out of a [CbzArchive] — so there's no
 * Publication, no navigator, no formatting session, no annotations, no TOC.
 *
 * v1 scope per ADR 0042:
 *   - Position is `Int pageIndex`; wire form is a Readium-shaped Locator JSON keyed on
 *     `locations.position` (1-based). Same shape as PDF, so no new wire codec.
 *   - No annotations, no book search, no formatting panel, no Cadence/Readaloud/Auto-Scroll.
 *   - Volume-key navigation and screen wake lock work via the shared reader plumbing.
 */
@HiltViewModel
class CbzReaderViewModel @Inject constructor(
    application: Application,
    savedStateHandle: SavedStateHandle,
    private val libraryObserver: LibraryObserver,
    private val cbzRepository: CbzRepository,
    private val updateReadingProgressUseCase: UpdateReadingProgress,
    private val wakeLockPreferencesStore: WakeLockPreferencesStore,
    private val volumeNavigationController: VolumeNavigationController,
    private val volumeKeyDispatcher: VolumeKeyDispatcher,
    private val readerStateHolder: ReaderStateHolder,
) : AndroidViewModel(application) {

    private val itemId: String = checkNotNull(savedStateHandle["itemId"])

    private var archive: ComicArchive? = null
    private var lastSavedPage: Int = -1
    private var closeSyncDone: Boolean = false

    private val _state = MutableStateFlow<CbzReaderState>(CbzReaderState.Loading)
    val state: StateFlow<CbzReaderState> = _state

    private val _currentPage = MutableStateFlow(0)
    /** 0-based page index of the currently displayed page. */
    val currentPage: StateFlow<Int> = _currentPage

    val keepScreenOn: StateFlow<Boolean> = wakeLockPreferencesStore.keepScreenOn
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val volumeNavEvents: SharedFlow<VolumeNavEvent> = volumeNavigationController.events

    val volumeKeyNavigationEnabled = volumeKeyDispatcher.volumeKeyNavigationEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val invertVolumeKeys = volumeKeyDispatcher.invertVolumeKeys
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch { openBook() }
    }

    private suspend fun openBook() {
        val item = libraryObserver.getItem(itemId)
        if (item == null) {
            _state.value = CbzReaderState.Error("Book not found")
            return
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
        val (opened, pageCount) = withContext(Dispatchers.IO) {
            val a = CbzArchive(file)
            a to a.pageCount
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
        _state.value = CbzReaderState.Ready(
            title = title,
            pageCount = pageCount,
            imageSource = ArchiveImageSource(opened),
        )
    }

    private fun parsePageIndex(json: String): Int? = try {
        // Same shape PDF writes: locations.position is 1-based. Also tolerate a raw integer for
        // pre-existing rows or hand-migrated data.
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
            savePosition(next, ready.pageCount)
        }
    }

    fun previousPage() {
        val next = (_currentPage.value - 1).coerceAtLeast(0)
        if (next != _currentPage.value) {
            _currentPage.value = next
            val ready = _state.value as? CbzReaderState.Ready ?: return
            savePosition(next, ready.pageCount)
        }
    }

    fun jumpToPage(index: Int) {
        val ready = _state.value as? CbzReaderState.Ready ?: return
        val clamped = index.coerceIn(0, ready.pageCount - 1)
        if (clamped != _currentPage.value) {
            _currentPage.value = clamped
            savePosition(clamped, ready.pageCount)
        }
    }

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
            // ADR 0042 (move 2): comic Completed is the last page reached.
            val progressFraction = if (pageCount > 1) pageIndex.toFloat() / (pageCount - 1).toFloat() else 1f
            updateReadingProgressUseCase(itemId, progressFraction)
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

    fun onReaderResumed() {
        readerStateHolder.isReaderActive = true
        closeSyncDone = false
    }

    fun onReaderClosed() {
        readerStateHolder.isReaderActive = false
        readerStateHolder.isPanelOpen = false
        if (closeSyncDone) return
        closeSyncDone = true
        val ready = _state.value as? CbzReaderState.Ready
        if (ready != null) savePosition(_currentPage.value, ready.pageCount)
    }

    override fun onCleared() {
        super.onCleared()
        archive?.close()
        archive = null
    }
}
