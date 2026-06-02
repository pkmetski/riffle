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
            val downloadedIds = downloadsRepository.getDownloadedItemIds()
            val cachedIds = downloadsRepository.getCachedItemIds()

            val downloadedItems = downloadedIds.mapNotNull { id ->
                libraryRepository.getItem(id)?.let { LocalItemUi(it, downloadsRepository.sizeOf(id)) }
            }
            val cachedItems = cachedIds.mapNotNull { id ->
                libraryRepository.getItem(id)?.let { LocalItemUi(it, downloadsRepository.sizeOf(id)) }
            }

            _uiState.value = DownloadsUiState(downloadedItems = downloadedItems, cachedItems = cachedItems)
        }
    }

    fun removeDownloadedItem(itemId: String) {
        viewModelScope.launch {
            downloadsRepository.removeDownload(itemId)
            load()
        }
    }

    fun removeCachedItem(itemId: String) {
        viewModelScope.launch {
            downloadsRepository.removeCached(itemId)
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
