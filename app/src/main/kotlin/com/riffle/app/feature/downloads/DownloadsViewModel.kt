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

data class DownloadsUiState(
    val downloadedItems: List<LibraryItem> = emptyList(),
    val cachedItems: List<LibraryItem> = emptyList(),
)

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

            val downloadedItems = downloadedIds.mapNotNull { libraryRepository.getItem(it) }
            val cachedItems = cachedIds.mapNotNull { libraryRepository.getItem(it) }

            _uiState.value = DownloadsUiState(downloadedItems = downloadedItems, cachedItems = cachedItems)
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
