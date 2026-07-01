package com.riffle.app.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    val serverId: String,
    val item: LibraryItem,
    val sizeBytes: Long,
)

data class DownloadsUiState(
    val downloadedItems: List<LocalItemUi> = emptyList(),
    val cachedItems: List<LocalItemUi> = emptyList(),
    val readaloudSidecars: List<LocalItemUi> = emptyList(),
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
                libraryObserver.getItem(ref.serverId, ref.itemId)?.let {
                    LocalItemUi(ref.serverId, it, downloadsRepository.sizeOf(ref.serverId, ref.itemId))
                }
            }
            val cachedItems = cached.mapNotNull { ref ->
                libraryObserver.getItem(ref.serverId, ref.itemId)?.let {
                    LocalItemUi(ref.serverId, it, downloadsRepository.sizeOf(ref.serverId, ref.itemId))
                }
            }
            // Prepared readaloud sidecars (ADR 0028): the small audio-free streaming caches. Keyed by the
            // Storyteller (serverId, bookId); resolve the title from the Storyteller readaloud library item.
            val readaloudSidecars = sidecarStore.listCached().mapNotNull { sc ->
                libraryObserver.getItem(sc.storytellerServerId, sc.storytellerBookId)?.let {
                    LocalItemUi(sc.storytellerServerId, it, sc.sizeBytes)
                }
            }

            _uiState.value = DownloadsUiState(
                downloadedItems = downloadedItems,
                cachedItems = cachedItems,
                readaloudSidecars = readaloudSidecars,
            )
        }
    }

    fun removeDownloadedItem(serverId: String, itemId: String) {
        viewModelScope.launch {
            downloadsRepository.removeDownload(serverId, itemId)
            load()
        }
    }

    fun removeCachedItem(serverId: String, itemId: String) {
        viewModelScope.launch {
            downloadsRepository.removeCached(serverId, itemId)
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

    fun removeReadaloudSidecar(storytellerServerId: String, storytellerBookId: String) {
        sidecarStore.remove(storytellerServerId, storytellerBookId)
        load()
    }

    fun clearAllReadaloudSidecars() {
        sidecarStore.clearAll()
        load()
    }
}
