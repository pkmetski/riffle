package com.riffle.feature.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.catalog.AudiobookMediaCapability
import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.BookImportCapability
import com.riffle.core.catalog.CatalogImportChapter
import com.riffle.core.catalog.CatalogImportFile
import com.riffle.core.catalog.CatalogImportMetadata
import com.riffle.core.catalog.CatalogImportPhase
import com.riffle.core.catalog.CatalogImportProgress
import com.riffle.core.catalog.CatalogImportRequest
import com.riffle.core.catalog.CatalogImportResult
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogRoot
import com.riffle.core.catalog.EbookDetailsCapability
import com.riffle.core.models.CatalogPlaylist
import com.riffle.core.catalog.DownloadsCapability
import com.riffle.core.catalog.LiveStreamCapability
import com.riffle.core.catalog.OriginalCoverCapability
import com.riffle.core.catalog.PlaylistsCapability
import com.riffle.core.catalog.ReadaloudCapability
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.SeriesCapability
import com.riffle.core.catalog.doesDestinationItemExist
import com.riffle.core.domain.AudiobookCacheRepository
import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookDownloadRepository
import com.riffle.core.domain.AudiobookPositionStore
import com.riffle.core.domain.AudioDownloadResult
import com.riffle.core.domain.AudiobookDownloadResult
import com.riffle.core.domain.CbzDownloadResult
import com.riffle.core.domain.CbzRepository
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.CrossEpubIndexBuildTrigger
import com.riffle.core.domain.EbookCfiTranslatorFactory
import com.riffle.core.domain.EpubDownloadResult
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.LibraryRefresher
import com.riffle.core.domain.LocalAvailabilityEvents
import com.riffle.core.domain.PdfDownloadResult
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.PlaylistsRepository
import com.riffle.core.domain.RESERVED_PLAYLIST_NAMES
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.ReadingSpeedStore
import com.riffle.core.domain.ReservedPlaylistNameException
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.ReadaloudSidecarPrefetcher
import com.riffle.core.domain.ToReadRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.domain.usecase.MarkReadAcrossDimensions
import com.riffle.core.domain.usecase.RecordItemOpened
import com.riffle.core.domain.usecase.UpdateReadingProgress
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.ReadaloudLink
import com.riffle.core.models.SourceType
import com.riffle.core.models.TocEntry
import kotlinx.coroutines.CancellationException
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
import kotlin.math.floor

sealed interface LibraryItemDetailUiState {
    data object Loading : LibraryItemDetailUiState
    data class Ready(
        val item: LibraryItem,
        val seriesId: String? = null,
        val isInToRead: Boolean = false,
        val isCachedOrDownloaded: Boolean = false,
        val isOffline: Boolean = false,
        val capabilities: DetailCapabilities = DetailCapabilities.Empty,
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

private fun audiobookProgressFraction(positionSec: Double, durationSec: Double): Float =
    (positionSec / durationSec).toFloat().coerceIn(0f, 1f)

internal fun importEbookLocation(format: BookFormat, translatedCfi: String?): String? = when {
    format != BookFormat.Epub -> null
    translatedCfi != null -> translatedCfi
    else -> ""
}

data class DetailCapabilities(
    val hasSeries: Boolean,
    val hasPlaylists: Boolean,
    val hasAudiobookMedia: Boolean,
    val hasMarkRead: Boolean = true,
    val hasDownloads: Boolean = false,
    val hasReadaloud: Boolean = false,
    val hasAddToPlaylist: Boolean = false,
    val canEditMetadata: Boolean = false,
    val canUploadToConfiguredSource: Boolean = false,
) {
    companion object {
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
        val Empty = DetailCapabilities(
            hasSeries = false,
            hasPlaylists = false,
            hasAudiobookMedia = false,
            hasMarkRead = false,
            hasDownloads = false,
            hasReadaloud = false,
            hasAddToPlaylist = false,
            canEditMetadata = false,
            canUploadToConfiguredSource = false,
        )
    }
}

class LibraryItemDetailViewModel constructor(
    itemId: String,
    sourceId: String?,
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
    private val cbzRepository: CbzRepository,
    private val toReadRepository: ToReadRepository,
    private val playlistsRepository: PlaylistsRepository,
    private val readaloudLinkRepository: ReadaloudLinkRepository,
    private val readaloudAudioRepository: ReadaloudAudioRepository,
    private val audiobookDownloadRepository: AudiobookDownloadRepository,
    private val audiobookCacheRepository: AudiobookCacheRepository,
    private val localAvailabilityEvents: LocalAvailabilityEvents,
    private val readaloudOfflineDownloader: ReadaloudOfflineDownloader,
    private val connectivityObserver: ConnectivityObserver,
    private val downloadManager: DownloadManager,
    private val bookImportManager: BookImportManager,
    private val crossEpubIndexBuildTrigger: CrossEpubIndexBuildTrigger,
    private val sidecarPrefetcher: ReadaloudSidecarPrefetcher,
    private val epubTocExtractor: EpubTocExtractor,
    private val pdfPageCountExtractor: PdfPageCountExtractor,
    private val fetchAudiobookChaptersUseCase: FetchAudiobookChaptersUseCase,
    private val catalogRegistry: CatalogRegistry,
    private val libraryRefresher: LibraryRefresher,
    private val saveLocalFileMetadataOverride: LocalFileMetadataOverrideSaver,
    private val copyCoverImage: CoverImageCopier,
    private val readingSpeedStore: ReadingSpeedStore,
    private val webSourceLibraryItemUpserter: WebSourceLibraryItemUpserter,
) : ViewModel() {

    private val _itemId: String = itemId
    private val _sourceId: String? = sourceId

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

    private var loadedItem: LibraryItem? = null
    private var readaloudLink: ReadaloudLink? = null
    private val _readaloudDownloadState = MutableStateFlow<DownloadState?>(null)
    val readaloudDownloadState: StateFlow<DownloadState?> = _readaloudDownloadState

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

    fun checkUploadDestination(destination: UploadDestination, library: CatalogRoot) {
        viewModelScope.launch {
            _uploadPreflight.value = UploadPreflight.Checking
            val item = loadedItem ?: run {
                _uploadPreflight.value = UploadPreflight.Blocked("The item is not loaded yet")
                return@launch
            }
            val sourceItem = try {
                catalogRegistry.forSourceId(item.sourceId)?.getItem(item.id)
            } catch (_: Throwable) {
                null
            }
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
            val sourceItem = sourceCatalog?.getItem(item.id)?.let { fetched -> withFallbackAuthor(fetched, item.author) }
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
                val upsertId = result.destinationItemId ?: sourceItem.id
                webSourceLibraryItemUpserter.upsert(destination.sourceId, sourceItem.copy(id = upsertId))
            }
            result
        }
        _uploadPreflight.value = UploadPreflight.Idle
    }

    private suspend fun buildImportRequest(
        sourceId: String,
        sourceCatalog: Catalog,
        sourceItem: CatalogItem,
        library: CatalogRoot,
        readingProgress: Float?,
    ): CatalogImportRequest {
        val audio = sourceCatalog as? AudiobookMediaCapability
        val audioTracks = if (sourceItem.hasAudio && audio != null) {
            audio.getTracks(sourceItem.id).sortedBy { it.index }
        } else {
            emptyList()
        }
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
            ebookLocation = importEbookLocation(sourceItem.ebookFormat, ebookCfi),
            audioDurationSec = sourceItem.audioDurationSec,
        )
    }

    private fun safeFileName(value: String): String =
        value.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "book" }

    fun refreshItemProgress() {
        viewModelScope.launch {
            val resolvedSourceId = _sourceId ?: sourceRepository.getActive()?.id ?: return@launch
            libraryRefresher.refreshItemProgress(resolvedSourceId, _itemId)
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
                    seriesIndex != null -> "$seriesName #${if (seriesIndex == floor(seriesIndex) && !seriesIndex.isInfinite()) seriesIndex.toLong().toString() else seriesIndex.toString()}"
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
                val item = _sourceId
                    ?.let { libraryObserver.getItem(it, _itemId) }
                    ?: libraryObserver.getItem(_itemId)
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
                    if (link != null && !readaloudBundlePresent) {
                        sidecarPrefetcher.prepare(link.storytellerSourceId, link.storytellerBookId)
                    }
                    _audiobookDownloadState.value = if (item.isListenable) {
                        deriveAudiobookDownloadState(item)
                    } else null
                    val isCachedOrDownloaded = isCachedOrDownloadedForFormat(item)
                    val isInToRead = toReadRepository.isInToReadForSource(item.sourceId, item.id, item.libraryId)
                    val seriesId = item.seriesName?.let { libraryObserver.getSeriesIdForItem(item.sourceId, item.id) }
                    val catalog = catalogRegistry.forSourceId(item.sourceId)
                    val isLiveStream = (catalog as? LiveStreamCapability)?.isLiveStream(item.id) == true
                    val canUploadToConfiguredSource = !isLiveStream && canUploadWebSourceItem(
                        catalog?.sourceType,
                        sourceRepository.observeAll().first().any { destination ->
                            destination.id != item.sourceId &&
                                catalogRegistry.forSource(destination) is BookImportCapability
                        },
                    )
                    val capabilities = DetailCapabilities(
                        hasSeries = catalog is SeriesCapability,
                        hasPlaylists = true,
                        hasAudiobookMedia = catalog is AudiobookMediaCapability,
                        hasMarkRead = (catalog as? LiveStreamCapability)?.isLiveStream(item.id) != true,
                        hasDownloads = catalog is DownloadsCapability &&
                            (catalog as? LiveStreamCapability)?.isLiveStream(item.id) != true,
                        hasReadaloud = catalog is ReadaloudCapability,
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

            val initialReady = _uiState.value
            if (initialReady is LibraryItemDetailUiState.Ready) {
                val item = initialReady.item
                if (item.ebookFormat == EbookFormat.Epub) {
                    launch {
                        _currentPositionHref.value = epubRepository.loadLastPositionHref(item.sourceId, item.id)
                        // Prefer a Source that can supply TOC + estimate from metadata without opening
                        // the book (EbookDetailsCapability) — avoids downloading/synthesizing the whole
                        // book just to render the detail screen. Falls back to the open-the-EPUB path
                        // for every Source that doesn't implement it (unchanged behavior).
                        val cheap = runCatching {
                            (catalogRegistry.forSourceId(item.sourceId) as? EbookDetailsCapability)
                                ?.ebookDetails(item.id)
                        }.getOrNull()
                        if (cheap != null) {
                            _tocState.value = TocState.Ready(cheap.tocEntries)
                            _epubTotalPositions.value = cheap.totalPositions
                            _epubVersion.value = cheap.epubVersion?.ifEmpty { null }
                        } else {
                            val details = epubTocExtractor.extractDetails(item)
                            _tocState.value = TocState.Ready(details.tocEntries)
                            _epubTotalPositions.value = details.totalPositions
                            _epubVersion.value = details.epubVersion?.ifEmpty { null }
                        }
                    }
                }
                if (item.ebookFormat == EbookFormat.Pdf) {
                    launch {
                        _pdfPageCount.value = pdfPageCountExtractor.extract(item)
                    }
                }
                if (item.isListenable) {
                    launch {
                        _chaptersState.value = ChaptersState.Ready(fetchAudiobookChaptersUseCase(item))
                    }
                }
            }

            val ready = _uiState.value
            if (ready is LibraryItemDetailUiState.Ready) {
                launch {
                    if (toReadRepository.refreshForSource(ready.item.sourceId, ready.item.libraryId)) {
                        val refreshed = toReadRepository.isInToReadForSource(ready.item.sourceId, ready.item.id, ready.item.libraryId)
                        val latest = _uiState.value
                        if (latest is LibraryItemDetailUiState.Ready) {
                            _uiState.value = latest.copy(isInToRead = refreshed)
                        }
                    }
                }
            }

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

        val itemFlow = _sourceId
            ?.let { libraryObserver.observeItem(it, _itemId) }
            ?: libraryObserver.observeItem(_itemId)
        itemFlow
            .onEach { latest ->
                if (latest == null) return@onEach
                val current = _uiState.value
                if (current is LibraryItemDetailUiState.Ready && current.item != latest) {
                    _uiState.value = current.copy(item = latest)
                }
            }
            .launchIn(viewModelScope)

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
        viewModelScope.launch { recordItemOpened(_itemId) }
    }

    fun markAsRead() {
        viewModelScope.launch {
            markReadAcrossDimensions(_itemId, finished = true)
            val current = _uiState.value
            if (current is LibraryItemDetailUiState.Ready) {
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
            markReadAcrossDimensions(_itemId, finished = false)
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
            val sourceId = current.item.sourceId
            val ok = if (wasInToRead) {
                toReadRepository.removeFromToReadForSource(sourceId, itemId, libraryId)
            } else {
                toReadRepository.addToToReadForSource(sourceId, itemId, libraryId)
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
            val streamed = readaloudOfflineDownloader.download(
                link.storytellerSourceId, link.storytellerBookId,
            ) { p -> onProgress((p * 100).toLong(), 100L) }
            if (streamed != null) {
                if (!streamed) _snackbarEvents.tryEmit("Couldn't download readaloud audio")
                return@start readaloudDownloadStateFor(streamed)
            }
            val result = readaloudAudioRepository.downloadAudio(
                link.storytellerSourceId, link.storytellerBookId, onProgress,
            )
            if (result !is AudioDownloadResult.Success) {
                _snackbarEvents.tryEmit("Couldn't download readaloud audio")
            } else {
                crossEpubIndexBuildTrigger.enqueueBuild(link)
            }
            readaloudDownloadStateFor(result is AudioDownloadResult.Success)
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

    private fun ebookKey(item: LibraryItem) = "ebook:${item.sourceId}:${item.id}"
    private fun audiobookKey(item: LibraryItem) = "audiobook:${item.sourceId}:${item.id}"
    private fun importKey(item: LibraryItem, destination: UploadDestination, library: CatalogRoot) =
        "import:${item.sourceId}:${item.id}:${destination.sourceId}:${library.id}"
    private fun importKeyPrefix(item: LibraryItem) = "import:${item.sourceId}:${item.id}:"
    private fun readaloudKey(link: ReadaloudLink) =
        "readaloud:${link.storytellerSourceId}:${link.storytellerBookId}"

    private suspend fun downloadEbook(
        item: LibraryItem,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): DownloadState {
        val ok: Boolean = when (item.ebookFormat) {
            EbookFormat.Epub -> when (epubRepository.downloadEpub(item, onProgress)) {
                EpubDownloadResult.Success, EpubDownloadResult.AlreadyDownloaded -> true
                is EpubDownloadResult.NetworkError -> false
            }
            EbookFormat.Pdf -> when (pdfRepository.downloadPdf(item, onProgress)) {
                PdfDownloadResult.Success, PdfDownloadResult.AlreadyDownloaded -> true
                is PdfDownloadResult.NetworkError -> false
            }
            EbookFormat.Cbz -> when (cbzRepository.downloadCbz(item, onProgress)) {
                com.riffle.core.domain.CbzDownloadResult.Success,
                com.riffle.core.domain.CbzDownloadResult.AlreadyDownloaded -> true
                is com.riffle.core.domain.CbzDownloadResult.NetworkError -> false
            }
            else -> return DownloadState.NotDownloaded
        }
        // Surface failures the same way audiobook downloads do (previously ebook failures were
        // silent — nothing showed when a download couldn't complete).
        if (!ok) _snackbarEvents.tryEmit("Couldn't download book")
        return if (ok) DownloadState.Downloaded else DownloadState.NotDownloaded
    }

    private suspend fun downloadAudiobook(
        item: LibraryItem,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): DownloadState {
        val result = audiobookDownloadRepository.download(item.sourceId, item.id, onProgress)
        val ok = result is AudiobookDownloadResult.Success
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

/**
 * Preserve an author already known from the library listing when a source's [Catalog.getItem]
 * can't recover it. Some web-source detail endpoints (e.g. O'Reilly's book metadata) carry no
 * author at all — it only appears in the browse/search listing that seeded the stored library
 * item — so a re-fetch at upload time returns a blank author and would otherwise overwrite the
 * one we already have. Generic and harmless: sources that do populate the author are untouched,
 * and a genuinely-unknown author (both blank) stays blank.
 */
internal fun withFallbackAuthor(
    fetched: com.riffle.core.catalog.CatalogItem,
    storedAuthor: String,
): com.riffle.core.catalog.CatalogItem =
    if (fetched.author.isBlank() && storedAuthor.isNotBlank()) {
        fetched.copy(author = storedAuthor)
    } else {
        fetched
    }
