package com.riffle.shared.downloads

import com.riffle.core.domain.DownloadsRepository
import com.riffle.core.domain.StoredItemArtifact
import com.riffle.core.domain.StoredItemRef
import com.riffle.core.domain.StoredMediaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class DownloadsUiState(
    val downloadedItems: List<LocalItemUiState> = emptyList(),
    val cachedItems: List<LocalItemUiState> = emptyList(),
    val isLoading: Boolean = true,
) {
    val downloadedTotalBytes: Long get() = downloadedItems.sumOf { it.sizeBytes }
    val cachedTotalBytes: Long get() = cachedItems.sumOf { it.sizeBytes }
}

data class LocalItemUiState(
    val sourceId: String,
    val itemId: String,
    val sizeBytes: Long,
    val mediaType: StoredMediaType,
)

class DownloadsViewModel(
    private val downloadsRepository: DownloadsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState

    init {
        load()
    }

    fun load() {
        scope.launch {
            val downloaded = downloadsRepository.getDownloadedArtifacts().map { it.toUiState() }
            val cached = downloadsRepository.getCachedArtifacts().map { it.toUiState() }
            _uiState.value = DownloadsUiState(
                downloadedItems = downloaded,
                cachedItems = cached,
                isLoading = false,
            )
        }
    }

    fun removeDownload(sourceId: String, itemId: String) {
        scope.launch {
            downloadsRepository.removeDownload(sourceId, itemId)
            load()
        }
    }

    fun removeAllDownloads() {
        scope.launch {
            downloadsRepository.removeAllDownloads()
            load()
        }
    }

    fun removeCached(sourceId: String, itemId: String) {
        scope.launch {
            downloadsRepository.removeCached(sourceId, itemId)
            load()
        }
    }

    fun clearAllCached() {
        scope.launch {
            downloadsRepository.clearAllCached()
            load()
        }
    }

    private fun StoredItemArtifact.toUiState() = LocalItemUiState(
        sourceId = sourceId,
        itemId = itemId,
        sizeBytes = downloadsRepository.sizeOf(sourceId, itemId),
        mediaType = mediaType,
    )
}
