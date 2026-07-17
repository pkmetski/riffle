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
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.PdfDownloadResult
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.usecase.MarkReadAcrossDimensions
import com.riffle.core.domain.usecase.RecordItemOpened
import com.riffle.core.domain.usecase.UpdateReadingProgress
import com.riffle.core.catalog.CatalogPlaylist
import com.riffle.core.data.PlaylistsRepository
import com.riffle.core.data.RESERVED_PLAYLIST_NAMES
import com.riffle.core.data.ReservedPlaylistNameException
import com.riffle.core.data.ToReadRepository
import com.riffle.core.catalog.AudiobookMediaCapability
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.DownloadsCapability
import com.riffle.core.catalog.PlaylistsCapability
import com.riffle.core.catalog.ReadaloudCapability
import com.riffle.core.catalog.SeriesCapability
import com.riffle.core.domain.SourceRepository
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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
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
        // Capability snapshot for the item's Source. Composables gate the Series chip / Listen
        // button / To Read icon on these instead of the item shape alone (issue #439). A Source
        // that lacks the capability hides the surface even when the item nominally supports it
        // (e.g. an audiobook file dropped into a LocalFiles Source: `isListenable` is true, but
        // `hasAudiobookMedia` is false, so no Listen button appears — LocalFiles has no player yet).
        val capabilities: DetailCapabilities = DetailCapabilities.Empty,
    ) : LibraryItemDetailUiState
    data object Error : LibraryItemDetailUiState
}

/** Per-Source capability flags consumed by the item-detail screen. */
data class DetailCapabilities(
    val hasSeries: Boolean,
    val hasPlaylists: Boolean,
    val hasAudiobookMedia: Boolean,
    /** True when the Source's Catalog declares [DownloadsCapability] — gates the ebook and
     *  audiobook Download buttons. LocalFiles omits the capability (nothing to fetch). */
    val hasDownloads: Boolean = false,
    /** True when the Source's Catalog declares [ReadaloudCapability] — gates the readaloud
     *  bundle Download button. ABS-only today. */
    val hasReadaloud: Boolean = false,
    /**
     * True when the "Add to playlist…" affordance should appear on the item detail sheet.
     * Gate: Source's Catalog implements [PlaylistsCapability] AND the item is an audiobook (per
     * the audiobook-playlists design — the Playlists tab lives on the ABS audiobook root only,
     * so the picker follows the same gate to stay consistent).
     */
    val hasAddToPlaylist: Boolean = false,
) {
    companion object {
        /** Every capability present — matches the ABS shape used by the majority of items. */
        val All = DetailCapabilities(
            hasSeries = true,
            hasPlaylists = true,
            hasAudiobookMedia = true,
            hasDownloads = true,
            hasReadaloud = true,
            hasAddToPlaylist = true,
        )

        /** No capability present — safe default when the active Source's Catalog can't be resolved.
         *  Enumerated exhaustively (mirroring [All]) so a future field addition can't silently
         *  inherit the wrong Kotlin data-class default without touching this line. */
        val Empty = DetailCapabilities(
            hasSeries = false,
            hasPlaylists = false,
            hasAudiobookMedia = false,
            hasDownloads = false,
            hasReadaloud = false,
            hasAddToPlaylist = false,
        )
    }
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
    private val libraryObserver: LibraryObserver,
    private val recordItemOpened: RecordItemOpened,
    private val updateReadingProgressUseCase: UpdateReadingProgress,
    private val markReadAcrossDimensions: MarkReadAcrossDimensions,
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
    private val epubRepository: EpubRepository,
    private val pdfRepository: PdfRepository,
    private val cbzRepository: com.riffle.core.domain.CbzRepository,
    private val toReadRepository: ToReadRepository,
    private val playlistsRepository: PlaylistsRepository,
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
    private val catalogRegistry: CatalogRegistry,
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

    private val _currentPositionHref = MutableStateFlow<String?>(null)
    val currentPositionHref: StateFlow<String?> = _currentPositionHref.asStateFlow()

    fun reloadCurrentPositionHref() {
        val ready = _uiState.value as? LibraryItemDetailUiState.Ready ?: return
        val item = ready.item
        if (item.ebookFormat != EbookFormat.Epub) return
        viewModelScope.launch {
            _currentPositionHref.value = epubRepository.loadLastPositionHref(item.sourceId, item.id)
        }
    }

    var authToken: String by mutableStateOf("")
        private set

    init {
        viewModelScope.launch {
            val server = sourceRepository.getActive()
            if (server != null) {
                authToken = tokenStorage.getToken(server.id) ?: ""
            }
            _uiState.value = try {
                val item = libraryObserver.getItem(itemId)
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
                        readaloudAudioRepository.isAudioAvailable(it.storytellerSourceId, it.storytellerBookId)
                    } ?: false
                    _readaloudDownloadState.value = link?.let { readaloudDownloadStateFor(readaloudBundlePresent) }
                    // Streaming prep (ADR 0028): a matched book opened in details starts fetching its sidecar
                    // now, so it's cached by the time the user opens the reader and taps Play — unless a full
                    // bundle is already downloaded (that supersedes streaming).
                    if (link != null && !readaloudBundlePresent) {
                        sidecarPrefetcher.prepare(link.storytellerSourceId, link.storytellerBookId)
                    }
                    _audiobookDownloadState.value = if (item.isListenable) {
                        if (audiobookDownloadRepository.isDownloaded(item.sourceId, item.id)) DownloadState.Downloaded
                        else DownloadState.NotDownloaded
                    } else null
                    val isCachedOrDownloaded = epubRepository.isCached(item.sourceId, item.id) || epubRepository.isDownloaded(item.sourceId, item.id)
                    // Render from the locally-cached To Read state immediately. The server refresh
                    // below runs off the critical path so a slow/unreachable ABS server can't keep
                    // the detail screen stuck in Loading for the network timeout (~10s).
                    val isInToRead = toReadRepository.isInToRead(item.id, item.libraryId)
                    val seriesId = item.seriesName?.let { libraryObserver.getSeriesIdForItem(item.sourceId, item.id) }
                    // Key capabilities off the item's OWN Source, not the currently-active one.
                    // Details screens outlive Source switches (deep-links from Annotations across
                    // Sources, an item pinned to a specific Source while another is active) — using
                    // getActive() would surface ABS-shape controls for a LocalFiles item, or vice
                    // versa. Raw `is` checks (see LibraryItemsViewModel.tabVisibility for the
                    // JVM-target rationale).
                    val catalog = catalogRegistry.forSourceId(item.sourceId)
                    val capabilities = DetailCapabilities(
                        hasSeries = catalog is SeriesCapability,
                        // To Read is available on every Source: [ToReadRepositoryImpl] falls back to
                        // a local Preferences DataStore ([LocalToReadStore]) when the Catalog has no
                        // server-side [PlaylistsCapability], so the toggle works uniformly for ABS,
                        // Local Files, Chitanka, and any future backend-less Source.
                        hasPlaylists = true,
                        hasAudiobookMedia = catalog is AudiobookMediaCapability,
                        hasDownloads = catalog is DownloadsCapability,
                        hasReadaloud = catalog is ReadaloudCapability,
                        // Audiobook-only items on a Source with server-side playlists get the
                        // "Add to playlist…" affordance. Mirrors the tab gate — ebook items on the
                        // same Source stay out of the Playlists surface.
                        hasAddToPlaylist = catalog is PlaylistsCapability && item.isListenable && !item.isReadable,
                    )
                    LibraryItemDetailUiState.Ready(
                        item = item,
                        seriesId = seriesId,
                        isInToRead = isInToRead,
                        isCachedOrDownloaded = isCachedOrDownloaded,
                        isOffline = !connectivityObserver.isOnline.value,
                        capabilities = capabilities,
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
                        _currentPositionHref.value = epubRepository.loadLastPositionHref(item.sourceId, item.id)
                        _tocState.value = TocState.Ready(extractEpubTocUseCase(item))
                    }
                }
                if (item.isListenable) {
                    launch {
                        _chaptersState.value = ChaptersState.Ready(fetchAudiobookChaptersUseCase(item))
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
        libraryObserver.observeItem(itemId)
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
        viewModelScope.launch { recordItemOpened(itemId) }
    }

    fun markAsRead() {
        viewModelScope.launch {
            markReadAcrossDimensions(itemId, finished = true)
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
            markReadAcrossDimensions(itemId, finished = false)
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

    // ── Add-to-playlist sheet ─────────────────────────────────────────────────
    // These helpers back [com.riffle.app.feature.library.playlists.AddToPlaylistSheet]. The sheet is
    // launched from the item-detail action row when [DetailCapabilities.hasAddToPlaylist] is true,
    // which the ViewModel gates on Source's PlaylistsCapability + item is audiobook-only.

    /** Flow of playlists for the currently-loaded item's library. "To Read" is filtered out by
     *  [PlaylistsRepository]. Empty until the item resolves. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val playlistsForCurrentItem: kotlinx.coroutines.flow.Flow<List<CatalogPlaylist>> =
        kotlinx.coroutines.flow.flow {
            val libraryId = _uiState
                .filterIsInstance<LibraryItemDetailUiState.Ready>()
                .first()
                .item.libraryId
            emitAll(playlistsRepository.observePlaylists(libraryId))
        }

    fun refreshPlaylists() {
        val ready = _uiState.value as? LibraryItemDetailUiState.Ready ?: return
        viewModelScope.launch { playlistsRepository.refresh(ready.item.libraryId) }
    }

    /** Toggles the item's membership in [playlist]. Emits a snackbar on failure. */
    fun toggleItemInPlaylist(playlist: CatalogPlaylist) {
        val ready = _uiState.value as? LibraryItemDetailUiState.Ready ?: return
        val item = ready.item
        viewModelScope.launch {
            val ok = if (item.id in playlist.itemIds) {
                playlistsRepository.removeItemFromPlaylist(item.libraryId, playlist.id, item.id)
            } else {
                playlistsRepository.addItemToPlaylist(item.libraryId, playlist.id, item.id)
            }
            if (!ok) _snackbarEvents.emit("Couldn't update playlist")
        }
    }

    /** Create a new playlist with the current item seeded. Returns "" on success or an error string. */
    suspend fun createPlaylistWithCurrentItem(name: String): String {
        val ready = _uiState.value as? LibraryItemDetailUiState.Ready ?: return "No item"
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "Name can't be empty"
        val reservedHit = RESERVED_PLAYLIST_NAMES.firstOrNull { it.equals(trimmed, ignoreCase = true) }
        if (reservedHit != null) return "'$reservedHit' is reserved"
        return try {
            playlistsRepository.createPlaylist(ready.item.libraryId, trimmed, initialItemId = ready.item.id)
            ""
        } catch (e: ReservedPlaylistNameException) {
            "'${e.name}' is reserved"
        } catch (_: Exception) {
            "Couldn't create playlist"
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
                EbookFormat.Cbz -> when (cbzRepository.downloadCbz(item, onProgress)) {
                    com.riffle.core.domain.CbzDownloadResult.Success,
                    com.riffle.core.domain.CbzDownloadResult.AlreadyDownloaded -> DownloadState.Downloaded
                    is com.riffle.core.domain.CbzDownloadResult.NetworkError -> DownloadState.NotDownloaded
                }
                else -> DownloadState.NotDownloaded
            }
        }
    }

    fun removeDownload() {
        viewModelScope.launch {
            val item = (uiState.value as? LibraryItemDetailUiState.Ready)?.item
            when (item?.ebookFormat) {
                EbookFormat.Epub -> epubRepository.removeDownload(item.sourceId, item.id)
                EbookFormat.Pdf -> pdfRepository.removeDownload(item.sourceId, item.id)
                EbookFormat.Cbz -> cbzRepository.removeDownload(item.sourceId, item.id)
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
                link.storytellerSourceId, link.storytellerBookId,
            ) { p -> onProgress((p * 100).toLong(), 100L) }
            if (streamed != null) {
                if (!streamed) _snackbarEvents.tryEmit("Couldn't download readaloud audio")
                return@start readaloudDownloadStateFor(streamed)
            }
            // Bundle path: download the synced bundle.
            val result = readaloudAudioRepository.downloadAudio(
                link.storytellerSourceId, link.storytellerBookId, onProgress,
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
            readaloudAudioRepository.removeAudio(link.storytellerSourceId, link.storytellerBookId)
            downloadManager.clear(readaloudKey(link))
            _readaloudDownloadState.value = DownloadState.NotDownloaded
        }
    }

    fun onDownloadAudiobook() {
        val item = (_uiState.value as? LibraryItemDetailUiState.Ready)?.item ?: return
        if (_audiobookDownloadState.value is DownloadState.InProgress) return
        downloadManager.start(audiobookKey(item)) { onProgress ->
            val result = audiobookDownloadRepository.download(item.sourceId, item.id, onProgress)
            val ok = result is com.riffle.core.domain.AudiobookDownloadResult.Success
            if (!ok) _snackbarEvents.tryEmit("Couldn't download audiobook")
            if (ok) DownloadState.Downloaded else DownloadState.NotDownloaded
        }
    }

    fun onRemoveAudiobook() {
        val item = (_uiState.value as? LibraryItemDetailUiState.Ready)?.item ?: return
        viewModelScope.launch {
            audiobookDownloadRepository.remove(item.sourceId, item.id)
            downloadManager.clear(audiobookKey(item))
            _audiobookDownloadState.value = DownloadState.NotDownloaded
        }
    }

    private fun refreshCacheState() {
        val current = _uiState.value as? LibraryItemDetailUiState.Ready ?: return
        val refreshed = epubRepository.isCached(current.item.sourceId, itemId) || epubRepository.isDownloaded(current.item.sourceId, itemId)
        _uiState.value = current.copy(isCachedOrDownloaded = refreshed)
    }

    // DownloadManager keys — stable per (server, item/book) so a recreated VM observes the same
    // in-flight download. Namespaced by kind because a single item can have both an ebook/audiobook
    // and a readaloud bundle downloading at once.
    private fun ebookKey(item: LibraryItem) = "ebook:${item.sourceId}:${item.id}"
    private fun audiobookKey(item: LibraryItem) = "audiobook:${item.sourceId}:${item.id}"
    private fun readaloudKey(link: com.riffle.core.domain.ReadaloudLink) =
        "readaloud:${link.storytellerSourceId}:${link.storytellerBookId}"

    private fun deriveDownloadState(item: LibraryItem): DownloadState {
        return when {
            isDownloadedForFormat(item) -> DownloadState.Downloaded
            else -> DownloadState.NotDownloaded
        }
    }

    private fun isDownloadedForFormat(item: LibraryItem): Boolean = when (item.ebookFormat) {
        EbookFormat.Epub -> epubRepository.isDownloaded(item.sourceId, item.id)
        EbookFormat.Pdf -> pdfRepository.isDownloaded(item.sourceId, item.id)
        EbookFormat.Cbz -> cbzRepository.isDownloaded(item.sourceId, item.id)
        else -> false
    }
}
