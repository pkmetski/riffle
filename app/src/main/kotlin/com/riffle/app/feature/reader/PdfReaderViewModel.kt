package com.riffle.app.feature.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.riffle.app.feature.reader.controllers.VolumeKeyDispatcher
import com.riffle.app.feature.reader.session.FormattingSession
import com.riffle.core.domain.Annotation
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.Clock
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.usecase.UpdateReadingProgress
import com.riffle.core.domain.PdfOpenResult
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.ProgressSyncController
import com.riffle.core.domain.ReadingSessionCoordinator
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.ReadingSpeedStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.SessionPayload
import com.riffle.core.domain.TocEntry
import com.riffle.core.domain.WakeLockPreferencesStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.streamer.PublicationOpener
import java.io.File
import javax.inject.Inject

@HiltViewModel
class PdfReaderViewModel @Inject constructor(
    application: Application,
    savedStateHandle: SavedStateHandle,
    private val libraryObserver: LibraryObserver,
    private val updateReadingProgressUseCase: UpdateReadingProgress,
    private val pdfRepository: PdfRepository,
    private val assetRetriever: AssetRetriever,
    private val publicationOpener: PublicationOpener,
    private val wakeLockPreferencesStore: WakeLockPreferencesStore,
    private val readingSessionRepository: ReadingSessionRepository,
    private val volumeNavigationController: VolumeNavigationController,
    private val readerStateHolder: ReaderStateHolder,
    private val annotationStore: AnnotationStore,
    private val sourceRepository: SourceRepository,
    private val formattingSessionFactory: FormattingSession.Factory,
    private val volumeKeyDispatcher: VolumeKeyDispatcher,
    clock: Clock,
    readingSpeedStore: ReadingSpeedStore,
    private val catalogRegistry: com.riffle.core.catalog.CatalogRegistry,
) : AndroidViewModel(application) {

    // See EpubReaderViewModel — mirrors the #439 gate for PDF's coordinator.
    private val readingSessionsEnabled = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        viewModelScope.launch {
            // getActive() is captured once at reader-open time. In practice the reader is opened
            // from a tap on the currently-active Source's library, so this matches the item's
            // Source. Raw `is` check in place of the inline has<T>() extension — see
            // LibraryItemsViewModel.tabVisibility for the JVM-target rationale.
            val catalog = sourceRepository.getActive()?.let { catalogRegistry.forSource(it) }
            readingSessionsEnabled.set(catalog is com.riffle.core.catalog.ReadingSessionsCapability)
        }
    }

    private val itemId: String = checkNotNull(savedStateHandle["itemId"])

    private val _state = MutableStateFlow<ReaderState>(ReaderState.Loading)
    val state: StateFlow<ReaderState> = _state

    // Formatting orchestrator — constructed with viewModelScope so teardown is deterministic
    // (mirrors EpubReaderViewModel; auto-scroll delegations are skipped, pdfium has no
    // equivalent surface for them).
    private val formatting: FormattingSession = formattingSessionFactory.create(viewModelScope).also {
        it.setDeviceDensity(application.resources.displayMetrics.density)
    }

    val keepScreenOn: StateFlow<Boolean> = wakeLockPreferencesStore.keepScreenOn
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val volumeNavEvents: SharedFlow<VolumeNavEvent> = volumeNavigationController.events

    // ---- FormattingSession delegations ---------------------------------------------------------

    // Raw user-picked prefs (theme = Auto stays as Auto) — feeds the FormattingPanel chip selection.
    val formattingPreferences: StateFlow<FormattingPreferences> = formatting.formattingPreferences

    // Resolved prefs (Auto theme replaced by concrete colour) — feeds the theme scrim + pdfium mapper.
    val effectiveFormattingPreferences: StateFlow<FormattingPreferences> =
        formatting.effectiveFormattingPreferences

    val hasBookOverrides: StateFlow<Boolean> = formatting.hasBookOverrides

    val formattingPreferencesReady: StateFlow<Boolean> = formatting.formattingPreferencesReady

    fun updateFormatting(prefs: FormattingPreferences) = formatting.updateFormatting(itemId, prefs)

    fun resetToGlobalDefaults() = formatting.resetToGlobalDefaults(itemId)

    fun setReaderViewportWidthPx(px: Int) = formatting.setViewportWidthPx(px)

    fun setKeepScreenOn(value: Boolean) {
        viewModelScope.launch { wakeLockPreferencesStore.setKeepScreenOn(value) }
    }

    // ---- VolumeKeyDispatcher delegations -----------------------------------------------------------

    val volumeKeyNavigationEnabled = volumeKeyDispatcher.volumeKeyNavigationEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val invertVolumeKeys = volumeKeyDispatcher.invertVolumeKeys
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setVolumeKeyNavigationEnabled(value: Boolean) {
        viewModelScope.launch { volumeKeyDispatcher.setVolumeKeyNavigationEnabled(value) }
    }

    fun setInvertVolumeKeys(value: Boolean) {
        viewModelScope.launch { volumeKeyDispatcher.setInvertVolumeKeys(value) }
    }

    private val _syncErrorEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val syncErrorEvents: SharedFlow<Unit> = _syncErrorEvents.asSharedFlow()

    // Shared sync seam — same construction pattern in every reader ViewModel (#528).
    private val syncSession = ProgressSyncController(
        itemId = itemId,
        repository = readingSessionRepository,
        scope = viewModelScope,
        onSyncError = { _syncErrorEvents.tryEmit(Unit) },
    )

    private val positionSaveCoordinator = PositionSaveCoordinator<String>(
        savePosition = { cfi -> pdfRepository.saveReadingPosition(itemId, cfi) },
        updateProgress = { progress -> updateReadingProgressUseCase(itemId, progress) },
    )

    private val _serverLocatorChannel = Channel<Locator>(Channel.CONFLATED)
    val serverLocatorEvents: Flow<Locator> = _serverLocatorChannel.receiveAsFlow()

    private var lastLocator: Locator? = null
    val latestLocator: Locator? get() = lastLocator
    private var closeSyncDone = false
    private var initialLocatorSeen = false

    // PDF heartbeat-sync only — speed-tracking stays opt-out (PDF "position" units don't share
    // semantics with EPUB's locator positions, so we leave the speed-store untouched by passing
    // null initialTotalProgression on resume; the coordinator suppresses its speed write).
    private val readingSessionCoordinator = ReadingSessionCoordinator(
        clock = clock,
        readingSpeedStore = readingSpeedStore,
        scope = viewModelScope,
        enabled = { readingSessionsEnabled.get() },
    )

    // Cached at book-open so navigateToEntry can resolve a TocEntry click back to
    // the original Readium Link without re-walking the publication. Index-aligned
    // with the flattened TOC tree we expose to the drawer.
    private var publication: Publication? = null
    private var tocLinks: List<Link> = emptyList()
    private var totalPages: Int = 0

    // Text-selection + highlight pipelines have been removed pending the MuPDF
    // renderer swap (see docs/superpowers/plans/2026-06-29-mupdf-renderer-swap.md).
    // Bookmarks and the annotations panel still work — they don't depend on
    // text resolution.

    // ---- TOC / chapter rail state ----------------------------------------

    private val _toc = MutableStateFlow<List<TocEntry>>(emptyList())
    val toc: StateFlow<List<TocEntry>> = _toc

    private val _railSegments = MutableStateFlow<List<RailSegment>>(emptyList())
    val railSegments: StateFlow<List<RailSegment>> = _railSegments

    private val _currentPage = MutableStateFlow<Int?>(null)
    val currentPage: StateFlow<Int?> = _currentPage

    /** 0-based page index, or 0 when no page reported yet. */
    private val currentPageIndex: Int get() = (_currentPage.value ?: 1).coerceAtLeast(1) - 1

    val activeRailSegmentIndex: StateFlow<Int> = combine(_railSegments, _currentPage) { segs, page ->
        if (segs.isEmpty()) 0
        else findActivePdfSegmentIndex(segs, ((page ?: 1) - 1).coerceAtLeast(0))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val railCursorPosition: StateFlow<Float> = combine(_railSegments, _currentPage) { segs, page ->
        if (segs.isEmpty() || totalPages <= 0) 0f
        else {
            val zeroPage = ((page ?: 1) - 1).coerceAtLeast(0)
            val active = findActivePdfSegmentIndex(segs, zeroPage)
            val withinSeg = pdfProgressionWithinActiveSegment(
                segments = segs,
                activeIndex = active,
                currentPageIndex = zeroPage,
                intraPageOffset = 0f,
                totalPages = totalPages,
            )
            // ChapterNavigationRail's cursorPosition is 0..1 across the whole rail, not within
            // the active segment. Map the within-segment fraction to its weighted slot on the rail
            // (same conversion EPUB uses) so early short chapters don't fill most of the bar.
            weightedRailCursorPosition(active, segs, withinSeg)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

    // ---- Drawer / panel visibility ---------------------------------------

    private val _tocVisible = MutableStateFlow(false)
    val tocVisible: StateFlow<Boolean> = _tocVisible

    private val _annotationsPanelVisible = MutableStateFlow(false)
    val annotationsPanelVisible: StateFlow<Boolean> = _annotationsPanelVisible

    fun openToc() { _tocVisible.value = true }
    fun closeToc() { _tocVisible.value = false }
    fun openAnnotationsPanel() { _annotationsPanelVisible.value = true }
    fun closeAnnotationsPanel() { _annotationsPanelVisible.value = false }

    // ---- Annotations -----------------------------------------------------

    private var annotationServerId: String? = null

    private val _annotations = MutableStateFlow<List<Annotation>>(emptyList())
    val annotations: StateFlow<List<Annotation>> = _annotations

    private val _bookmarks = MutableStateFlow<List<Annotation>>(emptyList())

    val currentPageBookmarked: StateFlow<Boolean> = combine(_bookmarks, _currentPage) { bookmarks, page ->
        val p = page ?: return@combine false
        bookmarks.any { pdfLocatorPosition(it.cfi) == p }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch {
            // Sequential: formatting prefs must be available before openBook() so the
            // navigator never sees the stateIn default on first paint (FormattingSession.bindToBook
            // waits for effectiveFormattingPreferences to reflect the loaded value).
            formatting.bindToBook(itemId)
            openBook()
        }
        viewModelScope.launch {
            syncSession.serverPositionEvents.collect { serverProgress ->
                val locator = serverLocationToLocator(serverProgress.ebookLocation) ?: return@collect
                // Adopt the server's locator into in-memory state alongside the channel emit.
                // Without this, `lastLocator` stayed at the stale initial (device-loaded)
                // position — a fast back-out before navigator.go landed would then save the
                // stale locator with a fresh stamp and the next sync pushed it back over the
                // server position. (#528)
                lastLocator = locator
                _serverLocatorChannel.trySend(locator)
            }
        }
    }

    private suspend fun openBook() {
        val item = libraryObserver.getItem(itemId)
        if (item == null) {
            _state.value = ReaderState.Error("Book not found")
            return
        }
        when (val result = pdfRepository.openPdf(item)) {
            is PdfOpenResult.Success -> {
                val publicationResult = openPublication(result.pdfFile)
                val publication = publicationResult.getOrElse {
                    _state.value = ReaderState.Error("Failed to open PDF: ${it.message}")
                    return
                }
                this.publication = publication
                buildTocAndRail(publication)
                annotationServerId = sourceRepository.getActive()?.id
                startObservingAnnotations()
                val locator = result.lastPosition
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { Locator.fromJSON(JSONObject(it)) }
                _state.value = ReaderState.Ready(
                    publication = publication,
                    title = item.title,
                    initialLocator = locator,
                )
                syncSession.sync(locator?.toPayload() ?: SessionPayload("", 0f))
                readingSessionCoordinator.onResumed(
                    initialTotalProgression = null,
                    onTick = { syncCurrentPosition() },
                )
            }
            is PdfOpenResult.NetworkError -> _state.value = ReaderState.Error(
                // Fallback to toString() when message is null so the user sees the exception class
                // ("java.io.IOException", "KomgaHttpException(400)…") instead of "null".
                "Network error: ${result.cause.message ?: result.cause.toString()}",
            )
            PdfOpenResult.Offline -> _state.value = ReaderState.Error("Book not available offline")
        }
    }

    private fun buildTocAndRail(publication: Publication) {
        // Readium's PDF adapter exposes the outline through Publication.tableOfContents
        // as a Link tree (title + href + children). For each Link, Publication
        // resolves a Locator whose position is the 1-based page number; we convert to
        // the 0-based pageIndex our adapters use.
        val flatLinks = mutableListOf<Link>()
        fun collect(links: List<Link>) {
            for (link in links) { flatLinks += link; collect(link.children) }
        }
        collect(publication.tableOfContents)
        tocLinks = flatLinks

        // Build the page-shaped outline by resolving each Link to its page index.
        // Pdfium's adapter encodes the page in two places, depending on PDF
        // outline shape: (a) the Locator's locations.position (1-based page),
        // (b) the Link.href as a `#page=N` fragment when the outline anchors
        // to a named-destination or page-fit. We try both; fall back to 0.
        fun pageOfLink(link: Link): Int {
            val locPos = publication.locatorFromLink(link)?.locations?.position
            if (locPos != null && locPos > 0) return locPos - 1
            // Parse `#page=N` fragment, 1-based per PDF Open Parameters / IETF
            // draft. Pdfium's adapter sets href to "<resource>#page=N" for
            // outline entries that map to a page-display destination.
            val href = link.href.toString()
            val frag = href.substringAfter('#', "")
            val pageParam = frag.split('&').firstOrNull { it.startsWith("page=") }
                ?.removePrefix("page=")
                ?.toIntOrNull()
            if (pageParam != null && pageParam > 0) return pageParam - 1
            return 0
        }
        fun mapTree(links: List<Link>): List<PdfOutlineNode> = links.map { link ->
            val pageIndex = pageOfLink(link)
            android.util.Log.v(
                "RifflePdfRail",
                "TOC link title=\"${link.title}\" href=${link.href} → pageIndex=$pageIndex",
            )
            PdfOutlineNode(
                title = link.title.orEmpty(),
                pageIndex = pageIndex,
                children = mapTree(link.children),
            )
        }
        val outline = mapTree(publication.tableOfContents)
        android.util.Log.i(
            "RifflePdfRail",
            "Built TOC outline: ${outline.size} top-level, " +
                "${pdfOutlineToFlatRailEntries(outline).size} flat",
        )

        totalPages = publication.metadata.numberOfPages ?: 0
        _toc.value = pdfOutlineToTocEntries(outline)
        _railSegments.value = buildPdfRailSegments(
            pdfTocEntries = pdfOutlineToFlatRailEntries(outline),
            totalPages = totalPages,
        )
    }

    private fun startObservingAnnotations() {
        val sourceId = annotationServerId ?: return
        viewModelScope.launch {
            annotationStore.observeAnnotations(sourceId, itemId).collect { rows ->
                _annotations.value = rows
            }
        }
        viewModelScope.launch {
            annotationStore.observeBookmarks(sourceId, itemId).collect { rows ->
                _bookmarks.value = rows
            }
        }
    }

    fun navigateToEntry(entry: TocEntry) {
        val locator = pageLocator(entry.href) ?: return
        _serverLocatorChannel.trySend(locator)
        closeToc()
    }

    fun navigateToSegment(segment: RailSegment) {
        val locator = pageLocator(segment.href) ?: return
        _serverLocatorChannel.trySend(locator)
    }

    /**
     * Build a Locator targeting the given synthetic `page=N` href (0-based page
     * index, as emitted by [pdfSegmentHref]). Synthesized directly from the
     * first reading-order resource + a 1-based position — we don't reverse-look
     * up the original TOC Link because some outline entries resolve their page
     * via an `#page=N` href fragment rather than a Locator position, which
     * would make the reverse lookup fail and the navigation silently no-op.
     */
    private fun pageLocator(href: String): Locator? {
        val pageIndex = href.removePrefix("page=").toIntOrNull() ?: return null
        val pub = publication ?: return null
        val resourceHref = pub.readingOrder.firstOrNull()?.url() ?: return null
        return Locator(
            href = resourceHref,
            mediaType = org.readium.r2.shared.util.mediatype.MediaType.PDF,
            locations = Locator.Locations(position = pageIndex + 1),
        )
    }

    fun navigateToAnnotation(id: String) {
        val annotation = _annotations.value.firstOrNull { it.id == id } ?: return
        val position = pdfLocatorPosition(annotation.cfi) ?: return
        val locator = Locator.fromJSON(JSONObject(annotation.cfi)) ?: run {
            // Fallback: synthesize a Locator from the page position alone if the
            // stored Locator JSON is malformed.
            val pub = publication ?: return
            val href = pub.metadata.title.orEmpty()
            Locator(
                href = pub.readingOrder.firstOrNull()?.url() ?: return,
                mediaType = org.readium.r2.shared.util.mediatype.MediaType.PDF,
                locations = Locator.Locations(position = position),
            ).also { /* href is best-effort */ }
        }
        _serverLocatorChannel.trySend(locator)
        closeAnnotationsPanel()
    }

    fun toggleBookmark() {
        val sourceId = annotationServerId ?: return
        val locator = lastLocator ?: return
        val position = locator.locations.position ?: return
        viewModelScope.launch {
            val existing = _bookmarks.value.firstOrNull { pdfLocatorPosition(it.cfi) == position }
            if (existing != null) {
                annotationStore.delete(existing.id)
            } else {
                val pageHref = locator.href.toString()
                val totalProg = locator.locations.totalProgression?.toDouble()
                    ?: if (totalPages > 0) (position - 1).toDouble() / totalPages.toDouble() else 0.0
                val locatorJson = buildPdfLocatorJson(pageHref, position, totalProg)
                annotationStore.createBookmark(
                    sourceId = sourceId,
                    itemId = itemId,
                    cfi = locatorJson,
                    textSnippet = "",
                    chapterHref = pageHref,
                    spineIndex = 0,
                    progression = totalProg,
                    bookmarkTitle = "Page $position",
                    // PDFs have no HTML DOM to sample a font from — the excerpt in the elided view
                    // shows "" for a PDF bookmark's textSnippet anyway (issue #484 render is a
                    // no-op on blank snippets). Plain serif placeholder satisfies the store's
                    // non-null contract.
                    originFontFamily = "serif",
                )
            }
        }
    }

    fun deleteAnnotation(id: String) {
        viewModelScope.launch { annotationStore.delete(id) }
    }

    fun renameBookmark(id: String, title: String) {
        viewModelScope.launch { annotationStore.renameBookmark(id, title) }
    }

    private fun buildPdfLocatorJson(href: String, position: Int, totalProgression: Double): String {
        val root = JSONObject()
            .put("href", href)
            .put("type", "application/pdf")
            .put(
                "locations", JSONObject()
                    .put("position", position)
                    .put("totalProgression", totalProgression),
            )
        return root.toString()
    }

    /** Best-effort: return the 1-based page position from a Riffle PDF Locator JSON, or null. */
    private fun pdfLocatorPosition(cfi: String): Int? {
        if (!cfi.startsWith("{")) return null
        return try {
            JSONObject(cfi).optJSONObject("locations")?.let { loc ->
                if (loc.has("position")) loc.getInt("position") else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun syncCurrentPosition() {
        val locator = lastLocator ?: return
        syncSession.sync(locator.toPayload())
    }

    fun onReaderResumed() {
        readerStateHolder.isReaderActive = true
        closeSyncDone = false
        initialLocatorSeen = false
        if (_state.value is ReaderState.Ready) {
            syncCurrentPosition()
            readingSessionCoordinator.onResumed(
                initialTotalProgression = null,
                onTick = { syncCurrentPosition() },
            )
        }
    }

    fun onReaderClosed() {
        readerStateHolder.isReaderActive = false
        readerStateHolder.isPanelOpen = false
        readingSessionCoordinator.onClosed(currentTotalProgression = null, totalPositions = 0f)
        if (closeSyncDone) return
        closeSyncDone = true
        val locator = lastLocator ?: return
        viewModelScope.launch {
            val payload = locator.toPayload()
            positionSaveCoordinator.onClose(payload.ebookProgress)
            syncSession.sync(payload)
        }
    }

    private suspend fun openPublication(file: File): Result<Publication> {
        val url = AbsoluteUrl("file://${file.absolutePath}")
            ?: return Result.failure(IllegalArgumentException("Invalid file path: ${file.absolutePath}"))
        val asset = when (val r = assetRetriever.retrieve(url)) {
            is Try.Success -> r.value
            is Try.Failure -> return Result.failure(Exception(r.value.message))
        }
        return when (val r = publicationOpener.open(asset, allowUserInteraction = false)) {
            is Try.Success -> Result.success(r.value)
            is Try.Failure -> Result.failure(Exception(r.value.message))
        }
    }

    fun onPageChanged(locator: Locator) {
        lastLocator = locator
        _currentPage.value = locator.locations.position
        if (!initialLocatorSeen) {
            initialLocatorSeen = true
            return
        }
        viewModelScope.launch {
            positionSaveCoordinator.onChanged(locator.toJSON().toString())
        }
    }

    private fun Locator.toPayload() = SessionPayload(
        ebookLocation = toJSON().toString(),
        ebookProgress = locations.progression?.toFloat() ?: 0f,
    )

    private fun serverLocationToLocator(location: String): Locator? =
        try { Locator.fromJSON(JSONObject(location)) } catch (_: Exception) { null }

    override fun onCleared() {
        super.onCleared()
        formatting.onBookClosed()
    }
}
