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
import com.riffle.core.domain.ServerType
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
        // True when the item belongs to a Storyteller-backed (Readaloud) Library.
        val isReadaloud: Boolean = false,
        // Surfaced as the Readaloud-side / ABS-side footer per ADR 0021 when the matcher has
        // produced a Confirmed link.
        val readaloudFooter: ReadaloudFooterState? = null,
        // True when the epub is available locally (cached or downloaded). Used by the UI to decide
        // whether to disable the Read button when offline (#35).
        val isCachedOrDownloaded: Boolean = false,
        // True when the device is currently offline. Reactive — updated via combine with
        // ConnectivityObserver.isOnline in the ViewModel.
        val isOffline: Boolean = false,
    ) : LibraryItemDetailUiState
    data object Error : LibraryItemDetailUiState
}

sealed interface ReadaloudFooterState {
    /** Shown on the ABS-side detail. Tapping navigates to the Readaloud item's detail screen. */
    data class AbsHasReadaloud(
        val readaloudLibraryName: String,
        val readaloudItemId: String,
    ) : ReadaloudFooterState

    /**
     * Shown on the Readaloud-side detail. Lists every ABS counterpart since a readaloud can
     * legitimately link to multiple ABS items (e.g. ebook + audiobook stub). Unlink drops
     * every row paired with this readaloud.
     */
    data class ReadaloudLinkedToAbs(
        val targets: List<AbsTarget>,
        val storytellerServerId: String,
        val storytellerBookId: String,
    ) : ReadaloudFooterState {
        data class AbsTarget(val absTitle: String, val absLibraryName: String)
    }
}

sealed interface DownloadState {
    data object NotDownloaded : DownloadState
    data object InProgress : DownloadState
    data object Downloaded : DownloadState
}

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
    private val connectivityObserver: ConnectivityObserver,
) : ViewModel() {

    private val itemId: String = savedStateHandle.get<String>("itemId") ?: ""

    private val _uiState = MutableStateFlow<LibraryItemDetailUiState>(LibraryItemDetailUiState.Loading)
    val uiState: StateFlow<LibraryItemDetailUiState> = _uiState

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.NotDownloaded)
    val downloadState: StateFlow<DownloadState> = _downloadState

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarEvents: SharedFlow<String> = _snackbarEvents.asSharedFlow()

    var authToken: String by mutableStateOf("")
        private set

    init {
        viewModelScope.launch {
            val server = serverRepository.getActive()
            if (server != null) {
                authToken = tokenStorage.getToken(server.id) ?: ""
            }
            val isReadaloud = server?.serverType == ServerType.STORYTELLER
            _uiState.value = try {
                val item = repository.getItem(itemId)
                if (item != null) {
                    _downloadState.value = deriveDownloadState(item)
                    if (!isReadaloud) toReadRepository.refresh(item.libraryId)
                    val isInToRead = if (isReadaloud) false else toReadRepository.isInToRead(item.id, item.libraryId)
                    val footer = resolveReadaloudFooter(item, isReadaloud, server?.id)
                    val isCachedOrDownloaded = epubRepository.isCached(item.id) || epubRepository.isDownloaded(item.id)
                    LibraryItemDetailUiState.Ready(
                        item = item,
                        isInToRead = isInToRead,
                        isReadaloud = isReadaloud,
                        readaloudFooter = footer,
                        isCachedOrDownloaded = isCachedOrDownloaded,
                        isOffline = !connectivityObserver.isOnline.value,
                    )
                } else {
                    LibraryItemDetailUiState.Error
                }
            } catch (_: Exception) {
                LibraryItemDetailUiState.Error
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
            when ((uiState.value as? LibraryItemDetailUiState.Ready)?.item?.ebookFormat) {
                EbookFormat.Epub -> epubRepository.removeDownload(itemId)
                EbookFormat.Pdf -> pdfRepository.removeDownload(itemId)
                else -> {}
            }
            _downloadState.value = DownloadState.NotDownloaded
            refreshCacheState()
        }
    }

    fun unlinkFromAbs() {
        val current = _uiState.value as? LibraryItemDetailUiState.Ready ?: return
        val footer = current.readaloudFooter as? ReadaloudFooterState.ReadaloudLinkedToAbs ?: return
        viewModelScope.launch {
            // Readaloud-side unlink drops every ABS row paired with this readaloud (each
            // ABS slot has its own row under the ABS-keyed schema).
            readaloudLinkRepository.unlinkStorytellerBook(footer.storytellerServerId, footer.storytellerBookId)
            _uiState.value = current.copy(readaloudFooter = null)
        }
    }

    private suspend fun resolveReadaloudFooter(
        item: LibraryItem,
        isReadaloud: Boolean,
        serverId: String?,
    ): ReadaloudFooterState? {
        if (serverId == null) return null
        return if (isReadaloud) {
            // A readaloud can have multiple ABS rows (ebook + audiobook stub). Surface
            // every counterpart, ebooks first so the most useful target leads the list.
            val links = readaloudLinkRepository.findByStorytellerBook(serverId, item.id)
            if (links.isEmpty()) return null
            val targets = links
                .mapNotNull { link ->
                    val absItem = repository.getItem(link.absLibraryItemId) ?: return@mapNotNull null
                    val library = repository.getLibrary(absItem.libraryId)
                    absItem to ReadaloudFooterState.ReadaloudLinkedToAbs.AbsTarget(
                        absTitle = absItem.title,
                        absLibraryName = library?.name ?: absItem.libraryId,
                    )
                }
                .sortedWith(
                    compareBy(
                        { if (it.first.ebookFormat == EbookFormat.Unsupported) 1 else 0 },
                        { it.second.absLibraryName },
                    )
                )
                .map { it.second }
            if (targets.isEmpty()) return null
            ReadaloudFooterState.ReadaloudLinkedToAbs(
                targets = targets,
                storytellerServerId = serverId,
                storytellerBookId = item.id,
            )
        } else {
            val link = readaloudLinkRepository.findByAbsItem(serverId, item.id) ?: return null
            val readaloudItem = repository.getItem(link.storytellerBookId) ?: return null
            val readaloudLibrary = repository.getLibrary(readaloudItem.libraryId)
            ReadaloudFooterState.AbsHasReadaloud(
                readaloudLibraryName = readaloudLibrary?.name ?: readaloudItem.libraryId,
                readaloudItemId = link.storytellerBookId,
            )
        }
    }

    private fun refreshCacheState() {
        val current = _uiState.value as? LibraryItemDetailUiState.Ready ?: return
        val refreshed = epubRepository.isCached(itemId) || epubRepository.isDownloaded(itemId)
        _uiState.value = current.copy(isCachedOrDownloaded = refreshed)
    }

    private fun deriveDownloadState(item: LibraryItem): DownloadState {
        return when {
            isDownloadedForFormat(item) -> DownloadState.Downloaded
            else -> DownloadState.NotDownloaded
        }
    }

    private fun isDownloadedForFormat(item: LibraryItem): Boolean = when (item.ebookFormat) {
        EbookFormat.Epub -> epubRepository.isDownloaded(item.id)
        EbookFormat.Pdf -> pdfRepository.isDownloaded(item.id)
        else -> false
    }
}
