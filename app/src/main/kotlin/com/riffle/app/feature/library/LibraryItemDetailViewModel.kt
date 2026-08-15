package com.riffle.app.feature.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookCacheRepository
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.models.EbookFormat
import com.riffle.core.domain.EpubDownloadResult
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.AudiobookPositionStore
import com.riffle.core.domain.EbookCfiTranslatorFactory
import com.riffle.core.models.LibraryItem
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.LibraryRefresher
import com.riffle.core.domain.LocalAvailabilityEvents
import com.riffle.core.domain.PdfDownloadResult
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.ReadingSpeedStore
import com.riffle.core.domain.usecase.MarkReadAcrossDimensions
import com.riffle.core.domain.usecase.RecordItemOpened
import com.riffle.core.domain.usecase.UpdateReadingProgress
import com.riffle.core.catalog.CatalogPlaylist
import com.riffle.core.data.PlaylistsRepository
import com.riffle.core.data.RESERVED_PLAYLIST_NAMES
import com.riffle.core.data.ReservedPlaylistNameException
import com.riffle.core.data.ToReadRepository
import com.riffle.core.catalog.AudiobookMediaCapability
import com.riffle.core.catalog.BookImportCapability
import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogImportFile
import com.riffle.core.catalog.CatalogImportChapter
import com.riffle.core.catalog.CatalogImportMetadata
import com.riffle.core.catalog.CatalogImportRequest
import com.riffle.core.catalog.CatalogImportResult
import com.riffle.core.catalog.CatalogRoot
import com.riffle.core.catalog.CatalogImportPhase
import com.riffle.core.catalog.CatalogImportProgress
import com.riffle.core.catalog.doesDestinationItemExist
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.app.feature.audiobook.audiobookProgressFraction
import com.riffle.core.data.localfiles.CopyCoverImageUseCase
import com.riffle.core.data.localfiles.SaveLocalFileMetadataOverrideUseCase
import com.riffle.core.data.websource.WebSourceLibraryItemUpserter
import com.riffle.core.models.SourceType
import com.riffle.core.catalog.DownloadsCapability
import com.riffle.core.catalog.OriginalCoverCapability
import com.riffle.core.catalog.PlaylistsCapability
import com.riffle.core.catalog.ReadaloudCapability
import com.riffle.core.catalog.SeriesCapability
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.TocEntry
import com.riffle.core.domain.TokenStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
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
        // True when the readable file is available locally (cached or downloaded). Used by the UI
        // to decide whether to disable the Read button when offline (#35).
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
        // Scanner-extracted values before any user overrides — non-null only for LocalFiles items.
        // Used by EditLocalFileMetadataDialog to let the user restore original embedded metadata.
        val originalItem: LibraryItem? = null,
    ) : LibraryItemDetailUiState
    data object Error : LibraryItemDetailUiState
}

data class UploadDestination(
    val sourceId: String,
    val label: String,
    val username: String,
    val libraries: List<CatalogRoot>,
)

sealed interface UploadPreflight {
    data object Idle : UploadPreflight
    data object Checking : UploadPreflight
    data class ExistingItem(
        val destination: UploadDestination,
        val library: CatalogRoot,
        val itemId: String,
        /** Audiobooks have no EPUB annotations that a replacement can invalidate. */
        val canOverwrite: Boolean,
    ) : UploadPreflight
    data class Blocked(val reason: String) : UploadPreflight
}

internal fun canUploadWebSourceItem(sourceType: SourceType?, hasImportDestination: Boolean): Boolean =
    sourceType?.isWebSource == true && hasImportDestination

internal fun importAudioProgress(
    positionSec: Double?,
    durationSec: Double,
    fallback: Float?,
): Float? = positionSec
    ?.takeIf { durationSec > 0.0 }
    ?.let { audiobookProgressFraction(it, durationSec) }
    ?: fallback

internal fun importEbookLocation(format: BookFormat, translatedCfi: String?): String? = when {
    format != BookFormat.Epub -> null
    translatedCfi != null -> translatedCfi
    else -> ""
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
    /** True when the item is from a LocalFiles Source — gates the "Edit metadata" and
     *  "Reset title to filename" overflow actions. */
    val canEditMetadata: Boolean = false,
    /** True when a Web Source item has at least one configured import-capable destination. */
    val canUploadToConfiguredSource: Boolean = false,
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
            canEditMetadata = false,
            canUploadToConfiguredSource = false,
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
            canEditMetadata = false,
            canUploadToConfiguredSource = false,
        )
    }
}

sealed interface DownloadState {
    data object NotDownloaded : DownloadState
    data object Cached : DownloadState
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

internal fun estimatedReadingTimeSec(totalPositions: Int, secPerPosition: Double): Long? {
    if (totalPositions <= 0 || !secPerPosition.isFinite() || secPerPosition <= 0.0) return null
    return (totalPositions * secPerPosition).toLong().coerceAtLeast(0L)
}

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
    private val ebookCfiTranslatorFactory: EbookCfiTranslatorFactory,
    private val audiobookPositionStore: AudiobookPositionStore,
    private val pdfRepository: PdfRepository,
    private val cbzRepository: com.riffle.core.domain.CbzRepository,
    private val toReadRepository: ToReadRepository,
    private val playlistsRepository: PlaylistsRepository,
    private val readaloudLinkRepository: com.riffle.core.domain.ReadaloudLinkRepository,
    private val readaloudAudioRepository: com.riffle.core.domain.ReadaloudAudioRepository,
    private val audiobookDownloadRepository: com.riffle.core.domain.AudiobookDownloadRepository,
    private val audiobookCacheRepository: AudiobookCacheRepository,
    private val localAvailabilityEvents: LocalAvailabilityEvents,
    private val readaloudOfflineDownloader: com.riffle.app.feature.reader.readaloud.ReadaloudOfflineDownloader,
    private val connectivityObserver: ConnectivityObserver,
    private val downloadManager: DownloadManager,
    private val bookImportManager: BookImportManager,
    private val crossEpubIndexBuildTrigger: com.riffle.core.data.CrossEpubIndexBuildTrigger,
    private val sidecarPrefetcher: com.riffle.core.data.ReadaloudSidecarPrefetcher,
    private val extractEpubTocUseCase: ExtractEpubTocUseCase,
    private val extractPdfPageCountUseCase: ExtractPdfPageCountUseCase,
    private val fetchAudiobookChaptersUseCase: FetchAudiobookChaptersUseCase,
    private val catalogRegistry: CatalogRegistry,
    private val libraryRefresher: LibraryRefresher,
    private val saveLocalFileMetadataOverride: SaveLocalFileMetadataOverrideUseCase,
    private val copyCoverImage: CopyCoverImageUseCase,
    private val readingSpeedStore: ReadingSpeedStore,
    private val webSourceLibraryItemUpserter: WebSourceLibraryItemUpserter,
) : ViewModel() {

    private val itemId: String = savedStateHandle.get<String>("itemId") ?: ""
    private val sourceId: String? = savedStateHandle.get<String>("sourceId")?.takeIf { it.isNotBlank() }

    private val _uiState = MutableStateFlow<LibraryItemDetailUiState>(LibraryItemDetailUiState.Loading)
    val uiState: StateFlow<LibraryItemDetailUiState> = _uiState

    private val _uploadDestinations = MutableStateFlow<List<UploadDestination>>(emptyList())
    val uploadDestinations: StateFlow<List<UploadDestination>> = _uploadDestinations.asStateFlow()

    private val _uploadPreflight = MutableStateFlow<UploadPreflight>(UploadPreflight.Idle)
    val uploadPreflight: StateFlow<UploadPreflight> = _uploadPreflight.asStateFlow()

    private val _bookImportState = MutableStateFlow<BookImportState>(BookImportState.Idle)
    val bookImportState: StateFlow<BookImportState> = _bookImportState.asStateFlow()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.NotDownloaded)
    val downloadState: StateFlow<DownloadState> = _downloadState

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarEvents: SharedFlow<String> = _snackbarEvents.asSharedFlow()

    // The loaded item, retained so the DownloadManager observer can compute this screen's download
    // keys without going through the (possibly still-Loading) uiState.
    private var loadedItem: LibraryItem? = null
    private var readaloudLink: com.riffle.core.models.ReadaloudLink? = null
    private val _readaloudDownloadState = MutableStateFlow<DownloadState?>(null)
    val readaloudDownloadState: StateFlow<DownloadState?> = _readaloudDownloadState

    // Null for a non-listenable item; otherwise the offline-download state for the ABS audiobook (ADR 0035).
    private val _audiobookDownloadState = MutableStateFlow<DownloadState?>(null)
    val audiobookDownloadState: StateFlow<DownloadState?> = _audiobookDownloadState

    private val _tocState = MutableStateFlow<TocState>(TocState.Loading)
    val tocState: StateFlow<TocState> = _tocState.asStateFlow()

    private val _chaptersState = MutableStateFlow<ChaptersState>(ChaptersState.Loading)
    val chaptersState: StateFlow<ChaptersState> = _chaptersState.asStateFlow()

    private val _currentPositionHref = MutableStateFlow<String?>(null)
    val currentPositionHref: StateFlow<String?> = _currentPositionHref.asStateFlow()

    private val _epubTotalPositions = MutableStateFlow<Int?>(null)
    val estimatedTotalReadingTimeSec: StateFlow<Long?> = combine(
        _epubTotalPositions,
        readingSpeedStore.speedSecPerPosition,
    ) { totalPositions, secPerPosition ->
        totalPositions?.let { estimatedReadingTimeSec(it, secPerPosition) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _pdfPageCount = MutableStateFlow<Int?>(null)
    val pdfPageCount: StateFlow<Int?> = _pdfPageCount.asStateFlow()

    private val _epubVersion = MutableStateFlow<String?>(null)
    val epubVersion: StateFlow<String?> = _epubVersion.asStateFlow()

    fun reloadCurrentPositionHref() {
        val ready = _uiState.value as? LibraryItemDetailUiState.Ready ?: return
        val item = ready.item
        if (item.ebookFormat != EbookFormat.Epub) return
        viewModelScope.launch {
            _currentPositionHref.value = epubRepository.loadLastPositionHref(item.sourceId, item.id)
        }
    }

    fun refreshLocalAvailability() {
        val current = _uiState.value as? LibraryItemDetailUiState.Ready ?: return
        val item = current.item
        _uiState.value = current.copy(isCachedOrDownloaded = isCachedOrDownloadedForFormat(item))
        if (_downloadState.value !is DownloadState.InProgress) {
            _downloadState.value = deriveDownloadState(item)
        }
        if (_audiobookDownloadState.value !is DownloadState.InProgress) {
            _audiobookDownloadState.value = if (item.isListenable) deriveAudiobookDownloadState(item) else null
        }
    }

    /** Loads configured destinations that explicitly opt in to book uploads. */
    fun refreshUploadDestinations() {
        viewModelScope.launch {
            val destinations = sourceRepository.observeAll().first().mapNotNull { source ->
                val catalog = runCatching { catalogRegistry.forSource(source) }.getOrNull()
                if (catalog !is BookImportCapability) return@mapNotNull null
                UploadDestination(
                    sourceId = source.id,
                    label = source.url.value,
                    username = source.username,
                    libraries = runCatching { catalog.listRoots() }.getOrDefault(emptyList()),
                )
            }
            _uploadDestinations.value = destinations
        }
    }

    /** Checks the destination before uploading. A missing item proceeds directly to upload. */
    fun checkUploadDestination(destination: UploadDestination, library: CatalogRoot) {
        viewModelScope.launch {
            _uploadPreflight.value = UploadPreflight.Checking
            val item = loadedItem ?: run {
                _uploadPreflight.value = UploadPreflight.Blocked("The item is not loaded yet")
                return@launch
            }
            val sourceItem = catalogRegistry.forSourceId(item.sourceId)?.getItem(item.id)
            val destinationCatalog = catalogRegistry.forSourceId(destination.sourceId)
            if (sourceItem == null || destinationCatalog !is BookImportCapability) {
                _uploadPreflight.value = UploadPreflight.Blocked("This item cannot be prepared for upload")
                return@launch
            }
            val matches = try {
                destinationCatalog.search(library.id, sourceItem.title, pageSize = 50)
            } catch (cause: CancellationException) {
                throw cause
            } catch (_: Throwable) {
                _uploadPreflight.value = UploadPreflight.Blocked("Could not check the destination library")
                return@launch
            }
            val existing = matches.firstOrNull { candidate ->
                doesDestinationItemExist(sourceItem, listOf(candidate))
            }
            if (existing != null) {
                _uploadPreflight.value = UploadPreflight.ExistingItem(
                    destination = destination,
                    library = library,
                    itemId = existing.id,
                    canOverwrite = sourceItem.hasAudio && sourceItem.ebookFormat != BookFormat.Epub,
                )
            } else {
                _uploadPreflight.value = UploadPreflight.Idle
                importToDestination(destination, library)
            }
        }
    }

    fun dismissUploadPreflight() {
        _uploadPreflight.value = UploadPreflight.Idle
    }

    fun importToDestination(destination: UploadDestination, library: CatalogRoot) {
        val item = loadedItem ?: return
        val key = importKey(item, destination, library)
        bookImportManager.start(key) { onProgress, claimItem ->
            val sourceCatalog = catalogRegistry.forSourceId(item.sourceId)
            val sourceItem = sourceCatalog?.getItem(item.id)
            val destinationCatalog = catalogRegistry.forSourceId(destination.sourceId) as? BookImportCapability
            if (sourceCatalog == null || sourceItem == null || destinationCatalog == null) {
                return@start CatalogImportResult.Failed(IllegalStateException("This item cannot be uploaded"))
            }
            if (library.importFolderId == null) {
                return@start CatalogImportResult.Failed(
                    IllegalArgumentException("This library has no upload folder configured"),
                )
            }
            val request = buildImportRequest(item.sourceId, sourceCatalog, sourceItem, library, item.readingProgress)
                .copy(onProgress = onProgress, claimDestinationItem = claimItem)
            val result = destinationCatalog.importBook(request)
            if (result is CatalogImportResult.Uploaded) {
                // Mirror the uploaded item into Room under the destination source so the
                // "Unowned" web-source filter sees it immediately — without waiting for the
                // next full ABS library sync (which only runs against the active source).
                val upsertId = result.destinationItemId ?: sourceItem.id
                webSourceLibraryItemUpserter.upsert(destination.sourceId, sourceItem.copy(id = upsertId))
            }
            result
        }
        _uploadPreflight.value = UploadPreflight.Idle
    }

    private suspend fun buildImportRequest(
        sourceId: String,
        sourceCatalog: com.riffle.core.catalog.Catalog,
        sourceItem: com.riffle.core.catalog.CatalogItem,
        library: CatalogRoot,
        readingProgress: Float?,
    ): CatalogImportRequest {
        val audio = sourceCatalog as? AudiobookMediaCapability
        val audioTracks = if (sourceItem.hasAudio && audio != null) {
            audio.getTracks(sourceItem.id).sortedBy { it.index }
        } else {
            emptyList()
        }
        // Chapter retrieval is supplemental: a source may expose its tracks but not chapter
        // markers. The files still transfer in their source-UI order in that case.
        val audioChapters = if (sourceItem.hasAudio && audio != null) {
            try {
                audio.getAudiobookChapters(sourceItem.id)
            } catch (_: Throwable) {
                emptyList()
            }
        } else {
            emptyList()
        }
        val files = buildList {
            when (sourceItem.ebookFormat) {
                BookFormat.Epub -> add(
                    CatalogImportFile(
                        fileName = "${safeFileName(sourceItem.title)}.epub",
                        mimeType = "application/epub+zip",
                        withStream = { block ->
                            sourceCatalog.withFileStream(
                                itemId = sourceItem.id,
                                format = BookFormat.Epub,
                                handleHint = sourceItem.ebookFileIno,
                                block = block,
                            )
                        },
                    ),
                )
                BookFormat.Pdf -> add(
                    CatalogImportFile(
                        fileName = "${safeFileName(sourceItem.title)}.pdf",
                        mimeType = "application/pdf",
                        withStream = { block ->
                            sourceCatalog.withFileStream(sourceItem.id, BookFormat.Pdf, sourceItem.ebookFileIno, block)
                        },
                    ),
                )
                BookFormat.Cbz -> add(
                    CatalogImportFile(
                        fileName = "${safeFileName(sourceItem.title)}.cbz",
                        mimeType = "application/vnd.comicbook+zip",
                        withStream = { block ->
                            sourceCatalog.withFileStream(sourceItem.id, BookFormat.Cbz, sourceItem.ebookFileIno, block)
                        },
                    ),
                )
                BookFormat.Audiobook, BookFormat.Unsupported -> Unit
            }
            if (sourceItem.hasAudio && audio != null) {
                audioTracks.forEach { track ->
                    val chapterTitle = audioChapters.firstOrNull { it.index == track.index }?.title
                    val trackName = chapterTitle?.takeIf { it.isNotBlank() }
                        ?: "${sourceItem.title}-${track.index + 1}"
                    add(
                        CatalogImportFile(
                            fileName = "${safeFileName(trackName)}.mp3",
                            mimeType = track.mimeType ?: "audio/mpeg",
                            withStream = { block -> audio.withTrackStream(sourceItem.id, track.ino, block) },
                        ),
                    )
                }
            }
        }
        val audioPositionSec = if (sourceItem.hasAudio) {
            audiobookPositionStore.load(sourceId, sourceItem.id)
        } else {
            null
        }
        val audioProgress = importAudioProgress(
            positionSec = audioPositionSec,
            durationSec = sourceItem.audioDurationSec,
            fallback = readingProgress ?: sourceItem.readingProgress,
        )
        val locatorJson = if (sourceItem.ebookFormat == BookFormat.Epub) {
            epubRepository.loadLastPosition(sourceId, sourceItem.id)
        } else {
            null
        }
        val ebookCfi = locatorJson?.let { locator ->
            ebookCfiTranslatorFactory
                .forItem(sourceId, sourceItem.id)
                ?.locatorJsonToCfi(locator)
        }
        return CatalogImportRequest(
            libraryId = library.id,
            folderId = library.importFolderId,
            metadata = CatalogImportMetadata(
                title = sourceItem.title,
                author = sourceItem.author,
                series = sourceItem.seriesName,
                description = sourceItem.description,
                publisher = sourceItem.publisher,
                language = sourceItem.language,
                publishedYear = sourceItem.publishedYear,
                genres = sourceItem.genres,
                isbn = sourceItem.isbn,
                asin = sourceItem.asin,
                coverUrl = sourceItem.coverUrl,
                seriesSequence = sourceItem.seriesSequence,
            ),
            files = files,
            chapters = audioChapters.map { chapter ->
                CatalogImportChapter(
                    id = chapter.index,
                    startSec = chapter.startSec,
                    endSec = chapter.endSec,
                    title = chapter.title,
                )
            },
            readingProgress = audioProgress,
            // ABS requires an epubcfi(...) locator to reopen at the saved position. If the EPUB
            // is not available locally for translation, keep the numeric progress but do not
            // invent a location.
            ebookLocation = importEbookLocation(sourceItem.ebookFormat, ebookCfi),
            audioDurationSec = sourceItem.audioDurationSec,
        )
    }

    private fun safeFileName(value: String): String =
        value.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "book" }

    /**
     * Called from the details screen's ON_RESUME. Pulls the item's server progress and mirrors
     * it into `library_items.readingProgress` / `finishedAt` so the blue bar and % / remaining
     * refresh when the user visits the details page — including the case of returning to details
     * without going through the library grid (deep link, back from reader, app foreground).
     */
    fun refreshItemProgress() {
        // Resolve source/item from the sources of truth (savedStateHandle + active source), NOT
        // from _uiState.value. ON_RESUME can fire before init's async item load completes
        // (cold-open / deep-link into details) — a Ready-only gate would silently drop the very
        // case the refresh was added for. The refresher's own precondition checks handle a null
        // active source or a mismatched sourceId.
        viewModelScope.launch {
            val resolvedSourceId = sourceId ?: sourceRepository.getActive()?.id ?: return@launch
            libraryRefresher.refreshItemProgress(resolvedSourceId, itemId)
        }
    }

    fun saveMetadataOverride(
        title: String,
        author: String,
        seriesName: String,
        seriesIndex: Double?,
        coverContentUri: String? = null,
        clearCoverOverride: Boolean = false,
    ) {
        val item = loadedItem ?: return
        viewModelScope.launch {
            val current = _uiState.value as? LibraryItemDetailUiState.Ready ?: return@launch
            val (savedCoverUrl, displayCoverUrl) = when {
                coverContentUri != null -> {
                    val path = copyCoverImage(item.sourceId, item.id, coverContentUri)
                    path to path
                }
                clearCoverOverride -> null to current.originalItem?.coverUrl
                else -> current.item.coverUrl to current.item.coverUrl
            }
            saveLocalFileMetadataOverride(
                item.sourceId, item.id, title, author, seriesName, seriesIndex,
                coverUrl = savedCoverUrl,
            )
            val patched = current.item.copy(
                title = title.ifBlank { current.item.title },
                author = author.ifBlank { current.item.author },
                seriesName = when {
                    seriesName.isBlank() -> null
                    seriesIndex != null -> "$seriesName #${if (seriesIndex == kotlin.math.floor(seriesIndex) && !seriesIndex.isInfinite()) seriesIndex.toLong().toString() else seriesIndex.toString()}"
                    else -> seriesName
                },
                coverUrl = displayCoverUrl,
            )
            _uiState.value = current.copy(item = patched)
            loadedItem = patched
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
                val item = sourceId
                    ?.let { libraryObserver.getItem(it, itemId) }
                    ?: libraryObserver.getItem(itemId)
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
                    // Streaming prep (ADR 0040): a matched book opened in details starts fetching its sidecar
                    // now, so it's cached by the time the user opens the reader and taps Play — unless a full
                    // bundle is already downloaded (that supersedes streaming).
                    if (link != null && !readaloudBundlePresent) {
                        sidecarPrefetcher.prepare(link.storytellerSourceId, link.storytellerBookId)
                    }
                    _audiobookDownloadState.value = if (item.isListenable) {
                        deriveAudiobookDownloadState(item)
                    } else null
                    val isCachedOrDownloaded = isCachedOrDownloadedForFormat(item)
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
                    val canUploadToConfiguredSource = canUploadWebSourceItem(
                        catalog?.sourceType,
                        sourceRepository.observeAll().first().any { destination ->
                            destination.id != item.sourceId &&
                                catalogRegistry.forSource(destination) is BookImportCapability
                        },
                    )
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
                        canEditMetadata = catalog?.sourceType == SourceType.LOCAL_FILES,
                        canUploadToConfiguredSource = canUploadToConfiguredSource,
                    )
                    val originalItem = if (capabilities.canEditMetadata) {
                        val originalCoverUrl = (catalog as? OriginalCoverCapability)
                            ?.originalCoverUrl(item.id)
                        item.copy(coverUrl = originalCoverUrl)
                    } else {
                        null
                    }
                    LibraryItemDetailUiState.Ready(
                        item = item,
                        seriesId = seriesId,
                        isInToRead = isInToRead,
                        isCachedOrDownloaded = isCachedOrDownloaded,
                        isOffline = !connectivityObserver.isOnline.value,
                        capabilities = capabilities,
                        originalItem = originalItem,
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
                        val details = extractEpubTocUseCase.extractDetails(item)
                        _tocState.value = TocState.Ready(details.tocEntries)
                        _epubTotalPositions.value = details.totalPositions
                        _epubVersion.value = details.epubVersion?.ifEmpty { null }
                    }
                }
                if (item.ebookFormat == EbookFormat.Pdf) {
                    launch {
                        _pdfPageCount.value = extractPdfPageCountUseCase(item)
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
                            if (state is DownloadState.Downloaded) refreshLocalAvailability()
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

            localAvailabilityEvents.changes
                .onEach { changed ->
                    val item = loadedItem ?: return@onEach
                    if (changed.sourceId != item.sourceId || changed.itemId != item.id) return@onEach
                    refreshLocalAvailability()
                }
                .launchIn(viewModelScope)

            bookImportManager.states
                .onEach { states ->
                    val item = loadedItem ?: return@onEach
                    val prefix = importKeyPrefix(item)
                    states.entries
                        .firstOrNull { it.key.startsWith(prefix) }
                        ?.value
                        ?.let { _bookImportState.value = it }
                        ?: run { _bookImportState.value = BookImportState.Idle }
                }
                .launchIn(viewModelScope)
        }

        // Keep the rendered progress current: the reader persists new readingProgress to the DB on
        // close, but this screen — retained on the back stack while the user reads — would otherwise
        // keep showing the one-shot snapshot taken in init. Observing the item patches the live row
        // (e.g. readingProgress) into the existing Ready state so book details matches where the
        // reader left off. Only patches once Ready, so it never pre-empts the Error/enrichment path.
        val itemFlow = sourceId
            ?.let { libraryObserver.observeItem(it, itemId) }
            ?: libraryObserver.observeItem(itemId)
        itemFlow
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
            } else {
                _snackbarEvents.emit(
                    if (wasInToRead) "Removed from To Read" else "Added to To Read"
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
        if (deriveDownloadState(item) == DownloadState.Cached) {
            downloadManager.startWithoutProgress(ebookKey(item), DownloadState.Cached) {
                downloadEbook(item) { _, _ -> }
            }
            return
        }
        // Runs on the app-scoped DownloadManager; the states observer above patches _downloadState.
        downloadManager.start(ebookKey(item)) { onProgress ->
            downloadEbook(item, onProgress)
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
            if (item != null) {
                _downloadState.value = deriveDownloadState(item)
            } else {
                _downloadState.value = DownloadState.NotDownloaded
            }
            refreshLocalAvailability()
            _snackbarEvents.tryEmit("Download removed")
        }
    }

    fun onDownloadReadaloud() {
        val link = readaloudLink ?: return
        if (_readaloudDownloadState.value is DownloadState.InProgress) return
        downloadManager.start(readaloudKey(link)) { onProgress ->
            // Streaming-eligible (ADR 0040): make offline by eager-fetching the audio (the sidecar is
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
                // un-fetchable prerequisite arrives. Schedule the build (idempotent; ADR 0037).
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
            _snackbarEvents.tryEmit("Download removed")
        }
    }

    fun onDownloadAudiobook() {
        val item = (_uiState.value as? LibraryItemDetailUiState.Ready)?.item ?: return
        if (_audiobookDownloadState.value is DownloadState.InProgress) return
        if (deriveAudiobookDownloadState(item) == DownloadState.Cached) {
            downloadManager.startWithoutProgress(audiobookKey(item), DownloadState.Cached) {
                downloadAudiobook(item) { _, _ -> }
            }
            return
        }
        downloadManager.start(audiobookKey(item)) { onProgress ->
            downloadAudiobook(item, onProgress)
        }
    }

    fun onRemoveAudiobook() {
        val item = (_uiState.value as? LibraryItemDetailUiState.Ready)?.item ?: return
        viewModelScope.launch {
            audiobookDownloadRepository.remove(item.sourceId, item.id)
            audiobookCacheRepository.remove(item.sourceId, item.id)
            downloadManager.clear(audiobookKey(item))
            _audiobookDownloadState.value = deriveAudiobookDownloadState(item)
            _snackbarEvents.tryEmit("Download removed")
        }
    }

    // DownloadManager keys — stable per (server, item/book) so a recreated VM observes the same
    // in-flight download. Namespaced by kind because a single item can have both an ebook/audiobook
    // and a readaloud bundle downloading at once.
    private fun ebookKey(item: LibraryItem) = "ebook:${item.sourceId}:${item.id}"
    private fun audiobookKey(item: LibraryItem) = "audiobook:${item.sourceId}:${item.id}"
    private fun importKey(item: LibraryItem, destination: UploadDestination, library: CatalogRoot) =
        "import:${item.sourceId}:${item.id}:${destination.sourceId}:${library.id}"
    private fun importKeyPrefix(item: LibraryItem) = "import:${item.sourceId}:${item.id}:"
    private fun readaloudKey(link: com.riffle.core.models.ReadaloudLink) =
        "readaloud:${link.storytellerSourceId}:${link.storytellerBookId}"

    private suspend fun downloadEbook(
        item: LibraryItem,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): DownloadState = when (item.ebookFormat) {
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

    private suspend fun downloadAudiobook(
        item: LibraryItem,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): DownloadState {
        val result = audiobookDownloadRepository.download(item.sourceId, item.id, onProgress)
        val ok = result is com.riffle.core.domain.AudiobookDownloadResult.Success
        if (!ok) _snackbarEvents.tryEmit("Couldn't download audiobook")
        return if (ok) DownloadState.Downloaded else DownloadState.NotDownloaded
    }

    private fun deriveDownloadState(item: LibraryItem): DownloadState {
        return when {
            isDownloadedForFormat(item) -> DownloadState.Downloaded
            isCachedForFormat(item) -> DownloadState.Cached
            else -> DownloadState.NotDownloaded
        }
    }

    private fun isDownloadedForFormat(item: LibraryItem): Boolean = when (item.ebookFormat) {
        EbookFormat.Epub -> epubRepository.isDownloaded(item.sourceId, item.id)
        EbookFormat.Pdf -> pdfRepository.isDownloaded(item.sourceId, item.id)
        EbookFormat.Cbz -> cbzRepository.isDownloaded(item.sourceId, item.id)
        else -> false
    }

    private fun isCachedForFormat(item: LibraryItem): Boolean = when (item.ebookFormat) {
        EbookFormat.Epub -> epubRepository.isCached(item.sourceId, item.id)
        EbookFormat.Pdf -> pdfRepository.isCached(item.sourceId, item.id)
        EbookFormat.Cbz -> cbzRepository.isCached(item.sourceId, item.id)
        else -> false
    }

    private fun deriveAudiobookDownloadState(item: LibraryItem): DownloadState = when {
        audiobookDownloadRepository.isDownloaded(item.sourceId, item.id) -> DownloadState.Downloaded
        audiobookCacheRepository.isCached(item.sourceId, item.id) -> DownloadState.Cached
        else -> DownloadState.NotDownloaded
    }

    private fun isCachedOrDownloadedForFormat(item: LibraryItem): Boolean =
        isCachedForFormat(item) || isDownloadedForFormat(item)
}
