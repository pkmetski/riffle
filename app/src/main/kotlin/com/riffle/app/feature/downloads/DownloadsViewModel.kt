package com.riffle.app.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.DownloadsCapability
import com.riffle.core.catalog.ReadaloudCapability
import com.riffle.core.domain.DownloadsRepository
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.LibraryObserver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A locally-available item paired with the on-disk size of its file. */
data class LocalItemUi(
    val sourceId: String,
    val item: LibraryItem,
    val sizeBytes: Long,
)

data class DownloadsUiState(
    val downloadedItems: List<LocalItemUi> = emptyList(),
    val cachedItems: List<LocalItemUi> = emptyList(),
    val readaloudSidecars: List<LocalItemUi> = emptyList(),
    /** True when the active Source's Catalog declares [DownloadsCapability] — gates both the
     *  Downloaded and Cached sections (Cached is a tier of the same local store). */
    val showCachedSection: Boolean = true,
    /** True when the active Source's Catalog declares [ReadaloudCapability] — gates the
     *  "Readaloud (streaming)" section header. */
    val showReadaloudSection: Boolean = true,
) {
    val downloadedTotalBytes: Long get() = downloadedItems.sumOf { it.sizeBytes }
    val cachedTotalBytes: Long get() = cachedItems.sumOf { it.sizeBytes }
    val readaloudSidecarsTotalBytes: Long get() = readaloudSidecars.sumOf { it.sizeBytes }
}

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadsRepository: DownloadsRepository,
    private val libraryObserver: LibraryObserver,
    private val sidecarStore: com.riffle.core.data.ReadaloudSidecarStore,
    private val catalogRegistry: CatalogRegistry,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val downloaded = downloadsRepository.getDownloadedItems()
            val cached = downloadsRepository.getCachedItems()

            val downloadedItems = downloaded.mapNotNull { ref ->
                libraryObserver.getItem(ref.sourceId, ref.itemId)?.let {
                    LocalItemUi(ref.sourceId, it, downloadsRepository.sizeOf(ref.sourceId, ref.itemId))
                }
            }
            val cachedItems = cached.mapNotNull { ref ->
                libraryObserver.getItem(ref.sourceId, ref.itemId)?.let {
                    LocalItemUi(ref.sourceId, it, downloadsRepository.sizeOf(ref.sourceId, ref.itemId))
                }
            }
            // Prepared readaloud sidecars (ADR 0028): the small audio-free streaming caches. Keyed by the
            // Storyteller (sourceId, bookId); resolve the title from the Storyteller readaloud library item.
            val readaloudSidecars = sidecarStore.listCached().mapNotNull { sc ->
                libraryObserver.getItem(sc.storytellerSourceId, sc.storytellerBookId)?.let {
                    LocalItemUi(sc.storytellerSourceId, it, sc.sizeBytes)
                }
            }

            val activeCatalog = catalogRegistry.forActive()
            // Absent active catalog → default to showing sections; a transient no-source-yet UI
            // shouldn't flicker sections in and out. `is` check (not the inline `has<T>()`)
            // because core:catalog compiles at JVM target 21 and this module pins 17 — the
            // reified inline can't cross that boundary. Same rationale as
            // [LibraryItemsViewModel.tabVisibility].
            val hasDownloads = activeCatalog?.let { it is DownloadsCapability } ?: true
            val hasReadaloud = activeCatalog?.let { it is ReadaloudCapability } ?: true
            _uiState.value = DownloadsUiState(
                downloadedItems = downloadedItems,
                cachedItems = cachedItems,
                readaloudSidecars = readaloudSidecars,
                showCachedSection = hasDownloads,
                showReadaloudSection = hasReadaloud,
            )
        }
    }

    fun removeDownloadedItem(sourceId: String, itemId: String) {
        viewModelScope.launch {
            downloadsRepository.removeDownload(sourceId, itemId)
            load()
        }
    }

    fun removeCachedItem(sourceId: String, itemId: String) {
        viewModelScope.launch {
            downloadsRepository.removeCached(sourceId, itemId)
            load()
        }
    }

    fun removeAllDownloads() {
        viewModelScope.launch {
            downloadsRepository.removeAllDownloads()
            load()
        }
    }

    fun clearAllCached() {
        viewModelScope.launch {
            downloadsRepository.clearAllCached()
            load()
        }
    }

    fun removeReadaloudSidecar(storytellerSourceId: String, storytellerBookId: String) {
        sidecarStore.remove(storytellerSourceId, storytellerBookId)
        load()
    }

    fun clearAllReadaloudSidecars() {
        sidecarStore.clearAll()
        load()
    }
}
