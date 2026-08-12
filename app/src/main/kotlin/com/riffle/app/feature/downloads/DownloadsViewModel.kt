package com.riffle.app.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.ContentCacheAutoClear
import com.riffle.core.domain.ContentCacheSettingsStore
import com.riffle.core.domain.DownloadsRepository
import com.riffle.core.models.LibraryItem
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.StoredItemArtifact
import com.riffle.core.domain.StoredMediaType
import com.riffle.core.models.isStorytellerService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A locally-available item paired with the on-disk size of its file. */
data class LocalItemUi(
    val sourceId: String,
    val item: LibraryItem,
    val sizeBytes: Long,
    val mediaTypes: Set<LocalMediaType>,
)

enum class LocalMediaType {
    Epub,
    Pdf,
    Comic,
    Audiobook,
    Readaloud,
}

data class DownloadsUiState(
    val downloadedItems: List<LocalItemUi> = emptyList(),
    val cachedItems: List<LocalItemUi> = emptyList(),
    val cacheAutoClear: ContentCacheAutoClear = ContentCacheSettingsStore.DEFAULT_AUTO_CLEAR,
) {
    val downloadedTotalBytes: Long get() = downloadedItems.sumOf { it.sizeBytes }
    val cachedTotalBytes: Long get() = cachedItems.sumOf { it.sizeBytes }
}

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadsRepository: DownloadsRepository,
    private val libraryObserver: LibraryObserver,
    private val sourceRepository: SourceRepository,
    private val sidecarStore: com.riffle.core.data.ReadaloudSidecarStore,
    private val contentCacheSettingsStore: ContentCacheSettingsStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState

    init {
        load()
        viewModelScope.launch {
            contentCacheSettingsStore.autoClear.collect { autoClear ->
                _uiState.value = _uiState.value.copy(cacheAutoClear = autoClear)
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            val downloadedArtifacts = downloadsRepository.getDownloadedArtifacts().toLocalArtifacts()
            val cachedArtifacts = downloadsRepository.getCachedArtifacts().toLocalArtifacts()
            // Prepared readaloud sidecars (ADR 0028): the small audio-free streaming caches. Keyed by the
            // Storyteller (sourceId, bookId); resolve the title from the Storyteller readaloud library item.
            val readaloudSidecars = sidecarStore.listCached().map { sc ->
                LocalArtifact(
                    sourceId = sc.storytellerSourceId,
                    itemId = sc.storytellerBookId,
                    mediaType = LocalMediaType.Readaloud,
                    sizeBytes = sc.sizeBytes,
                )
            }

            val downloadedItems = buildLocalItems(downloadedArtifacts)
            val cachedItems = buildLocalItems(cachedArtifacts + readaloudSidecars)

            _uiState.update { current ->
                current.copy(
                    downloadedItems = downloadedItems,
                    cachedItems = cachedItems,
                )
            }
        }
    }

    private suspend fun buildLocalItems(artifacts: List<LocalArtifact>): List<LocalItemUi> =
        artifacts
            .groupBy { it.sourceId to it.itemId }
            .mapNotNull { (key, group) ->
                val (sourceId, itemId) = key
                libraryObserver.getItem(sourceId, itemId)?.let { item ->
                    val repositoryBytes = if (group.any { it.sizeBytes == null }) {
                        downloadsRepository.sizeOf(sourceId, itemId)
                    } else {
                        0L
                    }
                    LocalItemUi(
                        sourceId = sourceId,
                        item = item,
                        sizeBytes = repositoryBytes + group.sumOf { it.sizeBytes ?: 0L },
                        mediaTypes = group.map { it.mediaType }.toSet(),
                    )
                }
            }

    private fun StoredMediaType.toLocalMediaType(): LocalMediaType = when (this) {
        StoredMediaType.Epub -> LocalMediaType.Epub
        StoredMediaType.Pdf -> LocalMediaType.Pdf
        StoredMediaType.Cbz -> LocalMediaType.Comic
        StoredMediaType.Audiobook -> LocalMediaType.Audiobook
    }

    private suspend fun List<StoredItemArtifact>.toLocalArtifacts(): List<LocalArtifact> =
        buildList {
            for (artifact in this@toLocalArtifacts) {
                add(
                    LocalArtifact(
                        sourceId = artifact.sourceId,
                        itemId = artifact.itemId,
                        mediaType = artifact.toLocalMediaType(),
                        sizeBytes = null,
                    )
                )
            }
        }

    private suspend fun StoredItemArtifact.toLocalMediaType(): LocalMediaType =
        if (mediaType == StoredMediaType.Epub && sourceRepository.getById(sourceId)?.isStorytellerService == true) {
            LocalMediaType.Readaloud
        } else {
            mediaType.toLocalMediaType()
        }

    fun setCacheAutoClear(value: ContentCacheAutoClear) {
        viewModelScope.launch {
            contentCacheSettingsStore.setAutoClear(value)
        }
    }

    fun removeDownloadedItem(sourceId: String, itemId: String) {
        viewModelScope.launch {
            downloadsRepository.removeDownload(sourceId, itemId)
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
            sidecarStore.clearAll()
            load()
        }
    }

    fun removeCachedItem(entry: LocalItemUi) {
        viewModelScope.launch {
            downloadsRepository.removeCached(entry.sourceId, entry.item.id)
            if (LocalMediaType.Readaloud in entry.mediaTypes) {
                sidecarStore.remove(entry.sourceId, entry.item.id)
            }
            load()
        }
    }

    private data class LocalArtifact(
        val sourceId: String,
        val itemId: String,
        val mediaType: LocalMediaType,
        val sizeBytes: Long?,
    )
}
