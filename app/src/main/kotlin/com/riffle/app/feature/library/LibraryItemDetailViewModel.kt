package com.riffle.app.feature.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface LibraryItemDetailUiState {
    data object Loading : LibraryItemDetailUiState
    data class Ready(
        val item: LibraryItem,
        val isInToRead: Boolean = false,
        // True when the item belongs to a Storyteller-backed (Readaloud) Library. In this slice the
        // Read button is disabled and the EPUB/audio downloads are hidden — reader-side bundle
        // fetch lands in #35 and #37 per ADR 0020.
        val isReadaloud: Boolean = false,
        // Surfaced as the Readaloud-side / ABS-side footer per ADR 0021 when the matcher has
        // produced a Confirmed link.
        val readaloudFooter: ReadaloudFooterState? = null,
    ) : LibraryItemDetailUiState
    data object Error : LibraryItemDetailUiState
}

sealed interface ReadaloudFooterState {
    /** Shown on the ABS-side detail. Tapping navigates to the Readaloud item's detail screen. */
    data class AbsHasReadaloud(
        val readaloudLibraryName: String,
        val readaloudItemId: String,
    ) : ReadaloudFooterState

    /** Shown on the Readaloud-side detail. Includes an unlink action. */
    data class ReadaloudLinkedToAbs(
        val absTitle: String,
        val absLibraryName: String,
        val storytellerServerId: String,
        val storytellerBookId: String,
    ) : ReadaloudFooterState
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
                    LibraryItemDetailUiState.Ready(
                        item = item,
                        isInToRead = isInToRead,
                        isReadaloud = isReadaloud,
                        readaloudFooter = footer,
                    )
                } else {
                    LibraryItemDetailUiState.Error
                }
            } catch (_: Exception) {
                LibraryItemDetailUiState.Error
            }
        }
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
            _downloadState.value = when (item.ebookFormat) {
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
        }
    }

    fun unlinkFromAbs() {
        val current = _uiState.value as? LibraryItemDetailUiState.Ready ?: return
        val footer = current.readaloudFooter as? ReadaloudFooterState.ReadaloudLinkedToAbs ?: return
        viewModelScope.launch {
            readaloudLinkRepository.unlink(footer.storytellerServerId, footer.storytellerBookId)
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
            val link = readaloudLinkRepository.findByStorytellerBook(serverId, item.id) ?: return null
            val absItem = repository.getItem(link.absLibraryItemId) ?: return null
            val absLibrary = repository.getLibrary(absItem.libraryId)
            ReadaloudFooterState.ReadaloudLinkedToAbs(
                absTitle = absItem.title,
                absLibraryName = absLibrary?.name ?: absItem.libraryId,
                storytellerServerId = link.storytellerServerId,
                storytellerBookId = link.storytellerBookId,
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
