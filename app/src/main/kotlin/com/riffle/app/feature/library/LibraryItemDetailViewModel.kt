package com.riffle.app.feature.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.EpubDownloadResult
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.LibraryRepository
import com.riffle.core.domain.PdfDownloadResult
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.data.ToReadRepository
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface LibraryItemDetailUiState {
    data object Loading : LibraryItemDetailUiState
    data class Ready(
        val item: LibraryItem,
        val isInToRead: Boolean = false,
        // True when the epub is available locally (cached or downloaded). Used by the UI to decide
        // whether to disable the Read button when offline (#35).
        val isCachedOrDownloaded: Boolean = false,
        // True when the device is currently offline. Reactive — updated via combine with
        // ConnectivityObserver.isOnline in the ViewModel.
        val isOffline: Boolean = false,
    ) : LibraryItemDetailUiState
    data object Error : LibraryItemDetailUiState
}

sealed interface DownloadState {
    data object NotDownloaded : DownloadState
    data object InProgress : DownloadState
    data object Downloaded : DownloadState
}

internal fun readaloudDownloadStateFor(bundlePresent: Boolean): DownloadState =
    if (bundlePresent) DownloadState.Downloaded else DownloadState.NotDownloaded

@HiltViewModel
class LibraryItemDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: LibraryRepository,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
    private val epubRepository: EpubRepository,
    private val pdfRepository: PdfRepository,
    private val sessionRepository: ReadingSessionRepository,
    private val toReadRepository: ToReadRepository,
    private val readaloudLinkRepository: com.riffle.core.domain.ReadaloudLinkRepository,
    private val readaloudAudioRepository: com.riffle.core.domain.ReadaloudAudioRepository,
    private val connectivityObserver: ConnectivityObserver,
) : ViewModel() {

    private val itemId: String = savedStateHandle.get<String>("itemId") ?: ""

    private val _uiState = MutableStateFlow<LibraryItemDetailUiState>(LibraryItemDetailUiState.Loading)
    val uiState: StateFlow<LibraryItemDetailUiState> = _uiState

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.NotDownloaded)
    val downloadState: StateFlow<DownloadState> = _downloadState

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarEvents: SharedFlow<String> = _snackbarEvents.asSharedFlow()

    private var readaloudLink: com.riffle.core.domain.ReadaloudLink? = null
    private val _readaloudDownloadState = MutableStateFlow<DownloadState?>(null)
    val readaloudDownloadState: StateFlow<DownloadState?> = _readaloudDownloadState

    var authToken: String by mutableStateOf("")
        private set

    init {
        viewModelScope.launch {
            val server = serverRepository.getActive()
            if (server != null) {
                authToken = tokenStorage.getToken(server.id) ?: ""
            }
            _uiState.value = try {
                val item = repository.getItem(itemId)
                if (item != null) {
                    _downloadState.value = deriveDownloadState(item)
                    val link = if (server?.id != null) {
                        readaloudLinkRepository.findByAbsItem(server.id, item.id)
                    } else {
                        null
                    }
                    readaloudLink = link
                    _readaloudDownloadState.value = link?.let {
                        readaloudDownloadStateFor(readaloudAudioRepository.isAudioAvailable(it.storytellerServerId, it.storytellerBookId))
                    }
                    val isCachedOrDownloaded = epubRepository.isCached(item.serverId, item.id) || epubRepository.isDownloaded(item.serverId, item.id)
                    // Render from the locally-cached To Read state immediately. The server refresh
                    // below runs off the critical path so a slow/unreachable ABS server can't keep
                    // the detail screen stuck in Loading for the network timeout (~10s).
                    val isInToRead = toReadRepository.isInToRead(item.id, item.libraryId)
                    LibraryItemDetailUiState.Ready(
                        item = item,
                        isInToRead = isInToRead,
                        isCachedOrDownloaded = isCachedOrDownloaded,
                        isOffline = !connectivityObserver.isOnline.value,
                    )
                } else {
                    LibraryItemDetailUiState.Error
                }
            } catch (_: Exception) {
                LibraryItemDetailUiState.Error
            }

            // Refresh To Read from the server without blocking the initial render; patch the
            // isInToRead badge once it returns.
            val ready = _uiState.value
            if (ready is LibraryItemDetailUiState.Ready) {
                launch {
                    if (toReadRepository.refresh(ready.item.libraryId)) {
                        val refreshed = toReadRepository.isInToRead(ready.item.id, ready.item.libraryId)
                        val latest = _uiState.value
                        if (latest is LibraryItemDetailUiState.Ready) {
                            _uiState.value = latest.copy(isInToRead = refreshed)
                        }
                    }
                }
            }
        }

        // Reactively update isOffline in Ready state when connectivity changes.
        connectivityObserver.isOnline
            .onEach { online ->
                val current = _uiState.value
                if (current is LibraryItemDetailUiState.Ready) {
                    _uiState.value = current.copy(isOffline = !online)
                }
            }
            .launchIn(viewModelScope)
    }

    fun markOpened() {
        viewModelScope.launch { repository.markItemOpened(itemId) }
    }

    fun markAsRead() {
        viewModelScope.launch {
            repository.updateReadingProgress(itemId, 1.0f)
            sessionRepository.setProgress(itemId, 1.0f)
            val current = _uiState.value
            if (current is LibraryItemDetailUiState.Ready) {
                // invariant: ADR 0018 — Read books are never in To Read
                toReadRepository.removeFromToRead(current.item.id, current.item.libraryId)
                _uiState.value = current.copy(
                    item = current.item.copy(readingProgress = 1.0f),
                    isInToRead = false,
                )
            }
        }
    }

    fun markAsUnread() {
        viewModelScope.launch {
            repository.updateReadingProgress(itemId, 0.0f)
            sessionRepository.setProgress(itemId, 0.0f)
            val current = _uiState.value
            if (current is LibraryItemDetailUiState.Ready) {
                _uiState.value = current.copy(item = current.item.copy(readingProgress = 0.0f))
            }
        }
    }

    fun toggleToRead() {
        val current = _uiState.value as? LibraryItemDetailUiState.Ready ?: return
        val wasInToRead = current.isInToRead
        _uiState.value = current.copy(isInToRead = !wasInToRead)
        viewModelScope.launch {
            val itemId = current.item.id
            val libraryId = current.item.libraryId
            val ok = if (wasInToRead) {
                toReadRepository.removeFromToRead(itemId, libraryId)
            } else {
                toReadRepository.addToToRead(itemId, libraryId)
            }
            if (!ok) {
                val now = _uiState.value as? LibraryItemDetailUiState.Ready ?: return@launch
                _uiState.value = now.copy(isInToRead = wasInToRead)
                _snackbarEvents.emit(
                    if (wasInToRead) "Couldn't remove from To Read" else "Couldn't add to To Read"
                )
            }
        }
    }

    fun startDownload() {
        if (_downloadState.value == DownloadState.InProgress) return
        val item = (uiState.value as? LibraryItemDetailUiState.Ready)?.item ?: return
        _downloadState.value = DownloadState.InProgress
        viewModelScope.launch {
            val newDownloadState = when (item.ebookFormat) {
                EbookFormat.Epub -> when (epubRepository.downloadEpub(item)) {
                    EpubDownloadResult.Success, EpubDownloadResult.AlreadyDownloaded -> DownloadState.Downloaded
                    is EpubDownloadResult.NetworkError -> DownloadState.NotDownloaded
                }
                EbookFormat.Pdf -> when (pdfRepository.downloadPdf(item)) {
                    PdfDownloadResult.Success, PdfDownloadResult.AlreadyDownloaded -> DownloadState.Downloaded
                    is PdfDownloadResult.NetworkError -> DownloadState.NotDownloaded
                }
                else -> DownloadState.NotDownloaded
            }
            _downloadState.value = newDownloadState
            if (newDownloadState == DownloadState.Downloaded) {
                refreshCacheState()
            }
        }
    }

    fun removeDownload() {
        viewModelScope.launch {
            val item = (uiState.value as? LibraryItemDetailUiState.Ready)?.item
            when (item?.ebookFormat) {
                EbookFormat.Epub -> epubRepository.removeDownload(item.serverId, item.id)
                EbookFormat.Pdf -> pdfRepository.removeDownload(item.serverId, item.id)
                else -> {}
            }
            _downloadState.value = DownloadState.NotDownloaded
            refreshCacheState()
        }
    }

    fun onDownloadReadaloud() {
        val link = readaloudLink ?: return
        if (_readaloudDownloadState.value == DownloadState.InProgress) return
        _readaloudDownloadState.value = DownloadState.InProgress
        viewModelScope.launch {
            val result = readaloudAudioRepository.downloadAudio(
                link.storytellerServerId, link.storytellerBookId,
            ) { _, _ -> }
            _readaloudDownloadState.value = readaloudDownloadStateFor(
                result is com.riffle.core.domain.AudioDownloadResult.Success,
            )
            if (result !is com.riffle.core.domain.AudioDownloadResult.Success) {
                _snackbarEvents.tryEmit("Couldn't download readaloud audio")
            }
        }
    }

    fun onRemoveReadaloud() {
        val link = readaloudLink ?: return
        viewModelScope.launch {
            readaloudAudioRepository.removeAudio(link.storytellerServerId, link.storytellerBookId)
            _readaloudDownloadState.value = DownloadState.NotDownloaded
        }
    }

    private fun refreshCacheState() {
        val current = _uiState.value as? LibraryItemDetailUiState.Ready ?: return
        val refreshed = epubRepository.isCached(current.item.serverId, itemId) || epubRepository.isDownloaded(current.item.serverId, itemId)
        _uiState.value = current.copy(isCachedOrDownloaded = refreshed)
    }

    private fun deriveDownloadState(item: LibraryItem): DownloadState {
        return when {
            isDownloadedForFormat(item) -> DownloadState.Downloaded
            else -> DownloadState.NotDownloaded
        }
    }

    private fun isDownloadedForFormat(item: LibraryItem): Boolean = when (item.ebookFormat) {
        EbookFormat.Epub -> epubRepository.isDownloaded(item.serverId, item.id)
        EbookFormat.Pdf -> pdfRepository.isDownloaded(item.serverId, item.id)
        else -> false
    }
}
