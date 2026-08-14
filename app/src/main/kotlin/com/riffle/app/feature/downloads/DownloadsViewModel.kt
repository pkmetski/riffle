package com.riffle.app.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.ContentCacheAutoClear
import com.riffle.core.domain.ContentCacheSettingsStore
import com.riffle.core.domain.DownloadsRepository
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.StoredItemArtifact
import com.riffle.core.domain.StoredMediaType
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.ReadaloudLink
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
    val storageRefs: Set<LocalStorageRef> = setOf(LocalStorageRef(sourceId, item.id)),
    val readaloudSidecarRefs: Set<ReadaloudSidecarRef> = emptySet(),
)

data class LocalStorageRef(val sourceId: String, val itemId: String)

data class ReadaloudSidecarRef(val storytellerSourceId: String, val storytellerBookId: String)

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
    private val readaloudLinkRepository: ReadaloudLinkRepository,
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
            // Prepared readaloud sidecars (ADR 0040): the small audio-free streaming caches. They
            // are stored under the Storyteller service key but displayed on the linked Source item,
            // because a Readaloud is a sidecar capability rather than a browsable item of its own.
            val readaloudSidecars = sidecarStore.listCached().mapNotNull { it.toLocalArtifact() }

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
            .groupBy { it.displaySourceId to it.displayItemId }
            .mapNotNull { (key, group) ->
                val (sourceId, itemId) = key
                libraryObserver.getItem(sourceId, itemId)?.let { item ->
                    val repositoryBytes = group
                        .filter { it.sizeBytes == null }
                        .mapNotNull { it.repositoryStorageRef }
                        .distinct()
                        .sumOf { downloadsRepository.sizeOf(it.sourceId, it.itemId) }
                    LocalItemUi(
                        sourceId = sourceId,
                        item = item,
                        sizeBytes = repositoryBytes + group.sumOf { it.sizeBytes ?: 0L },
                        mediaTypes = group.map { it.mediaType }.toSet(),
                        storageRefs = group.mapNotNull { it.repositoryStorageRef }.toSet(),
                        readaloudSidecarRefs = group.mapNotNull { it.readaloudSidecarRef }.toSet(),
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
                val mediaType = artifact.toLocalMediaType()
                val displayRef = if (mediaType == LocalMediaType.Readaloud) {
                    linkedSourceRef(artifact.sourceId, artifact.itemId) ?: continue
                } else {
                    LocalStorageRef(artifact.sourceId, artifact.itemId)
                }
                add(
                    LocalArtifact(
                        displaySourceId = displayRef.sourceId,
                        displayItemId = displayRef.itemId,
                        repositoryStorageRef = LocalStorageRef(artifact.sourceId, artifact.itemId),
                        mediaType = mediaType,
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

    private suspend fun com.riffle.core.data.ReadaloudSidecarStore.CachedSidecar.toLocalArtifact(): LocalArtifact? {
        val displayRef = linkedSourceRef(storytellerSourceId, storytellerBookId) ?: return null
        return LocalArtifact(
            displaySourceId = displayRef.sourceId,
            displayItemId = displayRef.itemId,
            repositoryStorageRef = null,
            mediaType = LocalMediaType.Readaloud,
            sizeBytes = sizeBytes,
            readaloudSidecarRef = ReadaloudSidecarRef(storytellerSourceId, storytellerBookId),
        )
    }

    private suspend fun linkedSourceRef(storytellerSourceId: String, storytellerBookId: String): LocalStorageRef? =
        readaloudLinkRepository
            .findByStorytellerBook(storytellerSourceId, storytellerBookId)
            .preferredDisplayLink()
            ?.let { LocalStorageRef(it.absSourceId, it.absLibraryItemId) }

    private suspend fun List<ReadaloudLink>.preferredDisplayLink(): ReadaloudLink? {
        var firstExisting: ReadaloudLink? = null
        for (link in this) {
            val item = libraryObserver.getItem(link.absSourceId, link.absLibraryItemId) ?: continue
            if (firstExisting == null) {
                firstExisting = link
            }
            if (item.isReadable) {
                return link
            }
        }
        return firstExisting
    }

    fun setCacheAutoClear(value: ContentCacheAutoClear) {
        viewModelScope.launch {
            contentCacheSettingsStore.setAutoClear(value)
        }
    }

    fun removeDownloadedItem(entry: LocalItemUi) {
        viewModelScope.launch {
            entry.storageRefs.forEach { downloadsRepository.removeDownload(it.sourceId, it.itemId) }
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
            entry.storageRefs.forEach { downloadsRepository.removeCached(it.sourceId, it.itemId) }
            entry.readaloudSidecarRefs.forEach {
                sidecarStore.remove(it.storytellerSourceId, it.storytellerBookId)
            }
            load()
        }
    }

    private data class LocalArtifact(
        val displaySourceId: String,
        val displayItemId: String,
        val repositoryStorageRef: LocalStorageRef?,
        val mediaType: LocalMediaType,
        val sizeBytes: Long?,
        val readaloudSidecarRef: ReadaloudSidecarRef? = null,
    )
}
