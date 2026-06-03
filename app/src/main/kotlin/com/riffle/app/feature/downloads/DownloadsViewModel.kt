package com.riffle.app.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.DownloadsRepository
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.LibraryRepository
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
) {
    val downloadedTotalBytes: Long get() = downloadedItems.sumOf { it.sizeBytes }
    val cachedTotalBytes: Long get() = cachedItems.sumOf { it.sizeBytes }
}

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadsRepository: DownloadsRepository,
    private val libraryRepository: LibraryRepository,
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
                libraryRepository.getItem(ref.serverId, ref.itemId)?.let {
                    LocalItemUi(ref.serverId, it, downloadsRepository.sizeOf(ref.serverId, ref.itemId))
                }
            }
            val cachedItems = cached.mapNotNull { ref ->
                libraryRepository.getItem(ref.serverId, ref.itemId)?.let {
                    LocalItemUi(ref.serverId, it, downloadsRepository.sizeOf(ref.serverId, ref.itemId))
                }
            }

            _uiState.value = DownloadsUiState(downloadedItems = downloadedItems, cachedItems = cachedItems)
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
}
