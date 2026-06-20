package com.riffle.app.feature.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.AudiobookChapter
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
import com.riffle.core.domain.TocEntry
import com.riffle.core.domain.TokenStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface LibraryItemDetailUiState {
    data object Loading : LibraryItemDetailUiState
    data class Ready(
        val item: LibraryItem,
        // The id of the series this item belongs to, if any. Lets the series line tap through to
        // the existing Series detail (the item carries only its seriesName string). Null = not in a
        // series, or series data not yet synced.
        val seriesId: String? = null,
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
    /** [percent] is 0..100 when the download advertises a size; null means indeterminate (spinner). */
    data class InProgress(val percent: Int? = null) : DownloadState
    data object Downloaded : DownloadState
}

sealed interface TocState {
    data object Loading : TocState
    data class Ready(val entries: List<TocEntry>) : TocState
}

sealed interface ChaptersState {
    data object Loading : ChaptersState
    data class Ready(val chapters: List<AudiobookChapter>) : ChaptersState
}

internal fun readaloudDownloadStateFor(bundlePresent: Boolean): DownloadState =
    if (bundlePresent) DownloadState.Downloaded else DownloadState.NotDownloaded

/**
 * Maps raw byte counts from a download into a 0..100 percentage for the progress ring, or null when
 * the total size is unknown (the server sent no content length) so the UI shows an indeterminate
 * spinner instead of a misleading number.
 */
internal fun downloadPercent(downloaded: Long, total: Long): Int? =
    if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else null

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
    private val audiobookDownloadRepository: com.riffle.core.domain.AudiobookDownloadRepository,
    private val readaloudOfflineDownloader: com.riffle.app.feature.reader.readaloud.ReadaloudOfflineDownloader,
    private val connectivityObserver: ConnectivityObserver,
    private val downloadManager: DownloadManager,
    private val crossEpubIndexBuildTrigger: com.riffle.core.data.CrossEpubIndexBuildTrigger,
    private val sidecarPrefetcher: com.riffle.core.data.ReadaloudSidecarPrefetcher,
    private val extractEpubTocUseCase: ExtractEpubTocUseCase,
    private val fetchAudiobookChaptersUseCase: FetchAudiobookChaptersUseCase,
) : ViewModel() {

    private val itemId: String = savedStateHandle.get<String>("itemId") ?: ""

    private val _uiState = MutableStateFlow<LibraryItemDetailUiState>(LibraryItemDetailUiState.Loading)
    val uiState: StateFlow<LibraryItemDetailUiState> = _uiState

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.NotDownloaded)
    val downloadState: StateFlow<DownloadState> = _downloadState

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarEvents: SharedFlow<String> = _snackbarEvents.asSharedFlow()

    // The loaded item, retained so the DownloadManager observer can compute this screen's download
    // keys without going through the (possibly still-Loading) uiState.
    private var loadedItem: LibraryItem? = null
    private var readaloudLink: com.riffle.core.domain.ReadaloudLink? = null
    private val _readaloudDownloadState = MutableStateFlow<DownloadState?>(null)
    val readaloudDownloadState: StateFlow<DownloadState?> = _readaloudDownloadState

    // Null for a non-listenable item; otherwise the offline-download state for the ABS audiobook (ADR 0029).
    private val _audiobookDownloadState = MutableStateFlow<DownloadState?>(null)
    val audiobookDownloadState: StateFlow<DownloadState?> = _audiobookDownloadState

    private val _tocState = MutableStateFlow<TocState>(TocState.Loading)
    val tocState: StateFlow<TocState> = _tocState.asStateFlow()

    private val _chaptersState = MutableStateFlow<ChaptersState>(ChaptersState.Loading)
    val chaptersState: StateFlow<ChaptersState> = _chaptersState.asStateFlow()

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
                    loadedItem = item
                    _downloadState.value = deriveDownloadState(item)
                    val link = if (server?.id != null) {
                        readaloudLinkRepository.findByAbsItem(server.id, item.id)
                    } else {
                        null
                    }
                    readaloudLink = link
                    val readaloudBundlePresent = link?.let {
                        readaloudAudioRepository.isAudioAvailable(it.storytellerServerId, it.storytellerBookId)
                    } ?: false
                    _readaloudDownloadState.value = link?.let { readaloudDownloadStateFor(readaloudBundlePresent) }
                    // Streaming prep (ADR 0028): a matched book opened in details starts fetching its sidecar
                    // now, so it's cached by the time the user opens the reader and taps Play — unless a full
                    // bundle is already downloaded (that supersedes streaming).
                    if (link != null && !readaloudBundlePresent) {
                        sidecarPrefetcher.prepare(link.storytellerServerId, link.storytellerBookId)
                    }
                    _audiobookDownloadState.value = if (item.isListenable) {
                        if (audiobookDownloadRepository.isDownloaded(item.serverId, item.id)) DownloadState.Downloaded
                        else DownloadState.NotDownloaded
                    } else null
                    val isCachedOrDownloaded = epubRepository.isCached(item.serverId, item.id) || epubRepository.isDownloaded(item.serverId, item.id)
                    // Render from the locally-cached To Read state immediately. The server refresh
                    // below runs off the critical path so a slow/unreachable ABS server can't keep
                    // the detail screen stuck in Loading for the network timeout (~10s).
                    val isInToRead = toReadRepository.isInToRead(item.id, item.libraryId)
                    val seriesId = item.seriesName?.let { repository.getSeriesIdForItem(item.serverId, item.id) }
                    LibraryItemDetailUiState.Ready(
                        item = item,
                        seriesId = seriesId,
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

            // Fire one-time TOC/chapters extraction now that the item is known. Placed here (not in
            // observeItem) so it runs exactly once per screen open — observeItem fires on every DB
            // update which would re-extract unnecessarily.
            val initialReady = _uiState.value
            if (initialReady is LibraryItemDetailUiState.Ready) {
                val item = initialReady.item
                if (item.ebookFormat == EbookFormat.Epub) {
                    launch {
                        val entries = extractEpubTocUseCase(item)
                        _tocState.value = TocState.Ready(entries)
                    }
                }
                if (item.isListenable) {
                    launch {
                        val chapters = fetchAudiobookChaptersUseCase(item)
                        _chaptersState.value = ChaptersState.Ready(chapters)
                    }
                }
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

            // Reflect downloads owned by the app-scoped DownloadManager (so they survive navigating
            // away from this screen). Launched after [loadedItem] is set so the StateFlow's initial
            // replay can already compute this screen's keys — letting a freshly recreated VM pick up a
            // download that was started earlier and is still running.
            downloadManager.states
                .onEach { states ->
                    val item = loadedItem ?: return@onEach
                    states[ebookKey(item)]?.let { state ->
                        if (_downloadState.value != state) {
                            _downloadState.value = state
                            if (state is DownloadState.Downloaded) refreshCacheState()
                        }
                    }
                    if (_audiobookDownloadState.value != null) {
                        states[audiobookKey(item)]?.let { _audiobookDownloadState.value = it }
                    }
                    readaloudLink?.let { link ->
                        if (_readaloudDownloadState.value != null) {
                            states[readaloudKey(link)]?.let { _readaloudDownloadState.value = it }
                        }
                    }
                }
                .launchIn(viewModelScope)
        }

        // Keep the rendered progress current: the reader persists new readingProgress to the DB on
        // close, but this screen — retained on the back stack while the user reads — would otherwise
        // keep showing the one-shot snapshot taken in init. Observing the item patches the live row
        // (e.g. readingProgress) into the existing Ready state so book details matches where the
        // reader left off. Only patches once Ready, so it never pre-empts the Error/enrichment path.
        repository.observeItem(itemId)
            .onEach { latest ->
                if (latest == null) return@onEach
                val current = _uiState.value
                if (current is LibraryItemDetailUiState.Ready && current.item != latest) {
                    _uiState.value = current.copy(item = latest)
                }
            }
            .launchIn(viewModelScope)

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
            setFinishedAcrossCoupledItems(finished = true)
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
            setFinishedAcrossCoupledItems(finished = false)
            val current = _uiState.value
            if (current is LibraryItemDetailUiState.Ready) {
                _uiState.value = current.copy(item = current.item.copy(readingProgress = 0.0f))
            }
        }
    }

    /**
     * Mark read/unread across every ABS item coupled by the same readaloud bundle — the ebook AND
     * its audiobook counterpart — so the two never disagree (a readaloud's ebook and audiobook are
     * separate ABS items that should track one finished state). Falls back to just this item when
     * there's no link or no active server.
     */
    private suspend fun setFinishedAcrossCoupledItems(finished: Boolean) {
        val progress = if (finished) 1.0f else 0.0f
        val serverId = serverRepository.getActive()?.id
        val ids = if (serverId != null) coupledAbsItemIds(serverId) else listOf(itemId)
        ids.forEach { id ->
            repository.updateReadingProgress(id, progress)
            sessionRepository.markFinished(id, finished)
        }
    }

    /**
     * The set of ABS item ids on the active server that share this item's readaloud bundle
     * (always includes [itemId]). Cross-server matches are excluded — `markFinished` operates on
     * the active server only.
     */
    private suspend fun coupledAbsItemIds(serverId: String): List<String> {
        val link = readaloudLinkRepository.findByAbsItem(serverId, itemId) ?: return listOf(itemId)
        val siblings = readaloudLinkRepository
            .findByStorytellerBook(link.storytellerServerId, link.storytellerBookId)
            .filter { it.absServerId == serverId }
            .map { it.absLibraryItemId }
        return (siblings + itemId).distinct()
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
        if (_downloadState.value is DownloadState.InProgress) return
        val item = (uiState.value as? LibraryItemDetailUiState.Ready)?.item ?: return
        // Runs on the app-scoped DownloadManager; the states observer above patches _downloadState.
        downloadManager.start(ebookKey(item)) { onProgress ->
            when (item.ebookFormat) {
                EbookFormat.Epub -> when (epubRepository.downloadEpub(item, onProgress)) {
                    EpubDownloadResult.Success, EpubDownloadResult.AlreadyDownloaded -> DownloadState.Downloaded
                    is EpubDownloadResult.NetworkError -> DownloadState.NotDownloaded
                }
                EbookFormat.Pdf -> when (pdfRepository.downloadPdf(item, onProgress)) {
                    PdfDownloadResult.Success, PdfDownloadResult.AlreadyDownloaded -> DownloadState.Downloaded
                    is PdfDownloadResult.NetworkError -> DownloadState.NotDownloaded
                }
                else -> DownloadState.NotDownloaded
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
            if (item != null) downloadManager.clear(ebookKey(item))
            _downloadState.value = DownloadState.NotDownloaded
            refreshCacheState()
        }
    }

    fun onDownloadReadaloud() {
        val link = readaloudLink ?: return
        if (_readaloudDownloadState.value is DownloadState.InProgress) return
        downloadManager.start(readaloudKey(link)) { onProgress ->
            // Streaming-eligible (ADR 0028): make offline by eager-fetching the audio (the sidecar is
            // already cached when the session is built). No 300 MB bundle. Null → not streamable → bundle.
            val streamed = readaloudOfflineDownloader.download(
                link.storytellerServerId, link.storytellerBookId,
            ) { p -> onProgress((p * 100).toLong(), 100L) }
            if (streamed != null) {
                if (!streamed) _snackbarEvents.tryEmit("Couldn't download readaloud audio")
                return@start readaloudDownloadStateFor(streamed)
            }
            // Bundle path: download the synced bundle.
            val result = readaloudAudioRepository.downloadAudio(
                link.storytellerServerId, link.storytellerBookId, onProgress,
            )
            if (result !is com.riffle.core.domain.AudioDownloadResult.Success) {
                _snackbarEvents.tryEmit("Couldn't download readaloud audio")
            } else {
                // The bundle is now present — the deterministic moment the cross-EPUB index's only
                // un-fetchable prerequisite arrives. Schedule the build (idempotent; ADR 0031).
                crossEpubIndexBuildTrigger.enqueueBuild(link)
            }
            readaloudDownloadStateFor(result is com.riffle.core.domain.AudioDownloadResult.Success)
        }
    }

    fun onRemoveReadaloud() {
        val link = readaloudLink ?: return
        viewModelScope.launch {
            readaloudAudioRepository.removeAudio(link.storytellerServerId, link.storytellerBookId)
            downloadManager.clear(readaloudKey(link))
            _readaloudDownloadState.value = DownloadState.NotDownloaded
        }
    }

    fun onDownloadAudiobook() {
        val item = (_uiState.value as? LibraryItemDetailUiState.Ready)?.item ?: return
        if (_audiobookDownloadState.value is DownloadState.InProgress) return
        downloadManager.start(audiobookKey(item)) { onProgress ->
            val result = audiobookDownloadRepository.download(item.serverId, item.id, onProgress)
            val ok = result is com.riffle.core.domain.AudiobookDownloadResult.Success
            if (!ok) _snackbarEvents.tryEmit("Couldn't download audiobook")
            if (ok) DownloadState.Downloaded else DownloadState.NotDownloaded
        }
    }

    fun onRemoveAudiobook() {
        val item = (_uiState.value as? LibraryItemDetailUiState.Ready)?.item ?: return
        viewModelScope.launch {
            audiobookDownloadRepository.remove(item.serverId, item.id)
            downloadManager.clear(audiobookKey(item))
            _audiobookDownloadState.value = DownloadState.NotDownloaded
        }
    }

    private fun refreshCacheState() {
        val current = _uiState.value as? LibraryItemDetailUiState.Ready ?: return
        val refreshed = epubRepository.isCached(current.item.serverId, itemId) || epubRepository.isDownloaded(current.item.serverId, itemId)
        _uiState.value = current.copy(isCachedOrDownloaded = refreshed)
    }

    // DownloadManager keys — stable per (server, item/book) so a recreated VM observes the same
    // in-flight download. Namespaced by kind because a single item can have both an ebook/audiobook
    // and a readaloud bundle downloading at once.
    private fun ebookKey(item: LibraryItem) = "ebook:${item.serverId}:${item.id}"
    private fun audiobookKey(item: LibraryItem) = "audiobook:${item.serverId}:${item.id}"
    private fun readaloudKey(link: com.riffle.core.domain.ReadaloudLink) =
        "readaloud:${link.storytellerServerId}:${link.storytellerBookId}"

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
