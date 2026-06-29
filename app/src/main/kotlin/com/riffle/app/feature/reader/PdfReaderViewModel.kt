package com.riffle.app.feature.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.Annotation
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.HighlightColor
import com.riffle.core.domain.LibraryRepository
import com.riffle.core.domain.PdfOpenResult
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.ProgressSyncController
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.SessionPayload
import com.riffle.core.domain.TocEntry
import com.riffle.core.domain.WakeLockPreferencesStore
import com.riffle.core.pdfium.text.PdfiumTextApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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

private const val PDF_SYNC_INTERVAL_MS = 30_000L

@HiltViewModel
class PdfReaderViewModel @Inject constructor(
    application: Application,
    savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
    private val pdfRepository: PdfRepository,
    private val assetRetriever: AssetRetriever,
    private val publicationOpener: PublicationOpener,
    private val wakeLockPreferencesStore: WakeLockPreferencesStore,
    private val readingSessionRepository: ReadingSessionRepository,
    private val volumeNavigationController: VolumeNavigationController,
    private val readerStateHolder: ReaderStateHolder,
    private val annotationStore: AnnotationStore,
    private val serverRepository: ServerRepository,
) : AndroidViewModel(application) {

    private val itemId: String = checkNotNull(savedStateHandle["itemId"])

    private val _state = MutableStateFlow<ReaderState>(ReaderState.Loading)
    val state: StateFlow<ReaderState> = _state

    val keepScreenOn: StateFlow<Boolean> = wakeLockPreferencesStore.keepScreenOn
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val volumeNavEvents: SharedFlow<VolumeNavEvent> = volumeNavigationController.events

    private val _syncErrorEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val syncErrorEvents: SharedFlow<Unit> = _syncErrorEvents.asSharedFlow()

    private val progressSyncController = ProgressSyncController(
        repository = readingSessionRepository,
        scope = viewModelScope,
        onSyncError = { _syncErrorEvents.tryEmit(Unit) },
    )

    private val positionSaveCoordinator = PositionSaveCoordinator<String>(
        savePosition = { cfi -> pdfRepository.saveReadingPosition(itemId, cfi) },
        updateProgress = { progress -> libraryRepository.updateReadingProgress(itemId, progress) },
    )

    private val _serverLocatorChannel = Channel<Locator>(Channel.CONFLATED)
    val serverLocatorEvents: Flow<Locator> = _serverLocatorChannel.receiveAsFlow()

    private var lastLocator: Locator? = null
    val latestLocator: Locator? get() = lastLocator
    private var syncJob: Job? = null
    private var closeSyncDone = false
    private var initialLocatorSeen = false

    // Cached at book-open so navigateToEntry can resolve a TocEntry click back to
    // the original Readium Link without re-walking the publication. Index-aligned
    // with the flattened TOC tree we expose to the drawer.
    private var publication: Publication? = null
    private var tocLinks: List<Link> = emptyList()
    private var totalPages: Int = 0

    // ---- Parallel PdfiumTextApi document for text selection -------------
    // We open a SECOND, independent FPDF_DOCUMENT against the same file
    // (separate from whatever handle Readium/barteksc holds for rendering).
    // Pdfium documents are isolated; the extra parse-tree memory is ~hundreds
    // of KB per book — negligible vs the rendered-page bitmap cache. Closed
    // on book teardown.
    private var pdfFilePath: String? = null
    private var pdfTextDocPtr: Long = 0L
    private val openedPagePtrs = mutableMapOf<Int, Long>()      // pageIndex → FPDF_PAGE
    private val openedTextPagePtrs = mutableMapOf<Int, Long>()  // pageIndex → FPDF_TEXTPAGE
    private val pdfPageDimensionsCache = mutableMapOf<Int, Pair<Double, Double>>()

    private fun ensurePdfTextDoc(): Long {
        if (pdfTextDocPtr != 0L) return pdfTextDocPtr
        val path = pdfFilePath ?: return 0L
        if (!PdfiumTextApi.ensureResolved()) return 0L
        pdfTextDocPtr = PdfiumTextApi.openDocument(path)
        return pdfTextDocPtr
    }

    private fun ensurePagePtrs(pageIndex: Int): Pair<Long, Long>? {
        val doc = ensurePdfTextDoc()
        if (doc == 0L) return null
        val page = openedPagePtrs.getOrPut(pageIndex) {
            PdfiumTextApi.openPage(doc, pageIndex)
        }
        if (page == 0L) return null
        val textPage = openedTextPagePtrs.getOrPut(pageIndex) {
            PdfiumTextApi.openTextPage(page)
        }
        if (textPage == 0L) return null
        return page to textPage
    }

    /** Returns (widthPoints, heightPoints) for the given page, or null if unavailable. */
    fun pdfPageDimensionsPoints(pageIndex: Int): Pair<Double, Double>? {
        pdfPageDimensionsCache[pageIndex]?.let { return it }
        val (page, _) = ensurePagePtrs(pageIndex) ?: return null
        val w = PdfiumTextApi.getPageWidth(page)
        val h = PdfiumTextApi.getPageHeight(page)
        if (w <= 0.0 || h <= 0.0) return null
        val dims = w to h
        pdfPageDimensionsCache[pageIndex] = dims
        return dims
    }

    /**
     * In-progress selection produced by a long-press but not yet committed
     * as a highlight. While non-null, [PdfSelectionOverlay] paints the quads
     * as a transient yellow overlay AND shows [HighlightActionsPopup] for
     * the user to pick a color / add a note. Choosing a color commits;
     * dismissing the popup discards.
     */
    data class PendingSelection(
        val pageIndex: Int,
        val charStart: Int,
        val charEnd: Int,
        val quads: List<android.graphics.RectF>,
        val snippet: PdfTextResolver.AnnotationSnippet,
        /** Screen-space rect bounding the first quad — anchor for the popup. */
        val anchorRect: androidx.compose.ui.unit.IntRect,
    )

    private val _pendingSelection = MutableStateFlow<PendingSelection?>(null)
    val pendingSelection: StateFlow<PendingSelection?> = _pendingSelection

    /**
     * Try to start a pending selection at the given screen-space tap.
     * Returns true if a word was resolved at the touch; false (and emits
     * nothing) if the touch missed text.
     *
     * Two-stage word resolution to balance hit-rate with placement accuracy:
     * 1. Try Pdfium's `FPDFText_GetCharIndexAtPos` with a generous tolerance
     *    (12 PDF points ≈ one line of body text). Misses are rare at this
     *    width even when the user taps in the space between letters.
     * 2. **Sanity-check the result.** If a char was found, verify that its
     *    bounding box is within ~20 PDF points of the touch. The 40-point
     *    fallback we removed earlier could pick a char anywhere on the
     *    page when the touch was on whitespace; this verification rejects
     *    such ghost results so a touch on a figure / equation / margin
     *    silently does nothing instead of highlighting a random word.
     *
     * Tolerance and proximity values are in PDF user-space points. Typical
     * 12pt body text ≈ 8pt tall × 3–7pt wide per char.
     */
    fun beginPendingSelection(
        pageIndex: Int,
        xPoints: Double,
        yPoints: Double,
        anchorOnScreen: androidx.compose.ui.unit.IntRect,
    ): Boolean {
        val (_, textPage) = ensurePagePtrs(pageIndex) ?: return false
        val resolver = PdfTextResolver(PdfiumTextApiSource)
        // Asymmetric tolerance: 12 PDF points horizontally (typical char is
        // 3–7 pt wide; we need slack for taps between letters), TIGHT 3
        // points vertically. A wider Y tolerance pulls characters from the
        // line above or below — exactly the "between the lines" bug — so
        // we never accept a char Pdfium found more than 3 pt vertically
        // away from the touch.
        val word = resolver.wordAtPoint(textPage, xPoints, yPoints, tolX = 12.0, tolY = 3.0)
            ?: return false
        // Proximity sanity check. Pdfium's xTolerance/yTolerance interprets
        // the SEARCH RADIUS, but it may still return a char whose box does
        // not strictly contain the search rect — particularly for sparse-
        // metric fonts. So after resolution we verify each quad explicitly:
        //  - X: touch must be within 10 pt of [left, right]
        //  - Y: touch must be within 2 pt of [bottom, top]  (very tight —
        //       any larger and inter-line whitespace passes for "on a line")
        val quads = resolver.quadsForRange(textPage, word)
        val xSlop = 10.0
        val ySlop = 2.0
        val withinProximity = quads.any { q ->
            val left = minOf(q.left.toDouble(), q.right.toDouble()) - xSlop
            val right = maxOf(q.left.toDouble(), q.right.toDouble()) + xSlop
            val top = maxOf(q.top.toDouble(), q.bottom.toDouble()) + ySlop   // PDF Y-up
            val bottom = minOf(q.top.toDouble(), q.bottom.toDouble()) - ySlop
            xPoints in left..right && yPoints in bottom..top
        }
        if (!withinProximity) {
            android.util.Log.i(
                "RifflePdfSel",
                "beginPending: rejecting word at " +
                    "[${word.start.value},${word.endExclusive.value}) — quads too far " +
                    "from touch (${xPoints},${yPoints})",
            )
            return false
        }
        val snippet = resolver.extractSnippet(textPage, word)
        android.util.Log.i(
            "RifflePdfSel",
            "beginPending: page=$pageIndex range=[${word.start.value},${word.endExclusive.value}) " +
                "snippet=\"${snippet.highlight}\"",
        )
        _pendingSelection.value = PendingSelection(
            pageIndex = pageIndex,
            charStart = word.start.value,
            charEnd = word.endExclusive.value,
            quads = quads,
            snippet = snippet,
            anchorRect = anchorOnScreen,
        )
        return true
    }

    /** Commit the pending selection as a persisted highlight with the chosen color. */
    fun commitPendingSelection(color: HighlightColor) {
        val pending = _pendingSelection.value ?: return
        val serverId = annotationServerId ?: return
        viewModelScope.launch {
            val (pdfW, pdfH) = pdfPageDimensionsPoints(pending.pageIndex) ?: return@launch
            val position = pending.pageIndex + 1
            val totalProg = if (totalPages > 0) pending.pageIndex.toDouble() / totalPages else 0.0
            val pageHref = lastLocator?.href?.toString() ?: pdfFilePath.orEmpty()
            val locatorJson = buildPdfHighlightLocatorJson(
                href = pageHref,
                position = position,
                totalProgression = totalProg,
                charStart = pending.charStart,
                charEnd = pending.charEnd,
                quads = pending.quads,
            )
            annotationStore.createHighlight(
                serverId = serverId,
                itemId = itemId,
                cfi = locatorJson,
                textSnippet = pending.snippet.highlight,
                textBefore = pending.snippet.before,
                textAfter = pending.snippet.after,
                chapterHref = pageHref,
                color = color.token,
                spineIndex = 0,
                progression = totalProg,
            )
            _pendingSelection.value = null
        }
    }

    /** Discard the pending selection without persisting. */
    fun discardPendingSelection() {
        _pendingSelection.value = null
    }

    private fun buildPdfHighlightLocatorJson(
        href: String,
        position: Int,
        totalProgression: Double,
        charStart: Int,
        charEnd: Int,
        quads: List<android.graphics.RectF>,
    ): String {
        val quadsArr = org.json.JSONArray().also { arr ->
            quads.forEach { q ->
                arr.put(JSONObject()
                    .put("x", q.left.toDouble())
                    .put("y", q.top.toDouble())
                    .put("w", (q.right - q.left).toDouble())
                    .put("h", (q.top - q.bottom).toDouble()))
            }
        }
        val other = JSONObject()
            .put("charStart", charStart)
            .put("charEnd", charEnd)
            .put("quads", quadsArr)
        return JSONObject()
            .put("href", href)
            .put("type", "application/pdf")
            .put("locations", JSONObject()
                .put("position", position)
                .put("totalProgression", totalProgression)
                .put("otherLocations", other))
            .toString()
    }

    /**
     * Returns persisted quads (PDF user-space points) for highlights anchored
     * to [pageIndex], paired with their annotation id (for tap-to-edit lookup).
     * Read-only lookup — drives the overlay's draw pass.
     */
    fun highlightsForPage(pageIndex: Int): List<Pair<String, List<android.graphics.RectF>>> {
        val position = pageIndex + 1
        return _annotations.value.mapNotNull { a ->
            if (a.cfi.startsWith("{") && pdfLocatorPosition(a.cfi) == position) {
                val quads = parseQuadsFromLocator(a.cfi)
                if (quads.isNotEmpty()) a.id to quads else null
            } else null
        }
    }

    private fun parseQuadsFromLocator(cfi: String): List<android.graphics.RectF> {
        return try {
            val arr = JSONObject(cfi)
                .optJSONObject("locations")
                ?.optJSONObject("otherLocations")
                ?.optJSONArray("quads") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val x = o.optDouble("x").toFloat()
                val y = o.optDouble("y").toFloat()
                val w = o.optDouble("w").toFloat()
                val h = o.optDouble("h").toFloat()
                android.graphics.RectF(x, y, x + w, y - h)  // PDF Y grows up, so y-h is the lower edge
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

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
            pdfProgressionWithinActiveSegment(
                segments = segs,
                activeIndex = active,
                currentPageIndex = zeroPage,
                intraPageOffset = 0f,
                totalPages = totalPages,
            )
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
        viewModelScope.launch { openBook() }
        viewModelScope.launch {
            progressSyncController.serverPositionEvents.collect { serverProgress ->
                serverLocationToLocator(serverProgress.ebookLocation)?.let { _serverLocatorChannel.trySend(it) }
            }
        }
    }

    private suspend fun openBook() {
        val item = libraryRepository.getItem(itemId)
        if (item == null) {
            _state.value = ReaderState.Error("Book not found")
            return
        }
        when (val result = pdfRepository.openPdf(item)) {
            is PdfOpenResult.Success -> {
                pdfFilePath = result.pdfFile.absolutePath
                val publicationResult = openPublication(result.pdfFile)
                val publication = publicationResult.getOrElse {
                    _state.value = ReaderState.Error("Failed to open PDF: ${it.message}")
                    return
                }
                this.publication = publication
                buildTocAndRail(publication)
                annotationServerId = serverRepository.getActive()?.id
                startObservingAnnotations()
                val locator = result.lastPosition
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { Locator.fromJSON(JSONObject(it)) }
                _state.value = ReaderState.Ready(
                    publication = publication,
                    title = item.title,
                    initialLocator = locator,
                )
                progressSyncController.sync(itemId, locator?.toPayload() ?: SessionPayload("", 0f))
                startPeriodicSync()
            }
            is PdfOpenResult.NetworkError -> _state.value = ReaderState.Error("Network error: ${result.cause.message}")
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
        val serverId = annotationServerId ?: return
        viewModelScope.launch {
            annotationStore.observeAnnotations(serverId, itemId).collect { rows ->
                _annotations.value = rows
            }
        }
        viewModelScope.launch {
            annotationStore.observeBookmarks(serverId, itemId).collect { rows ->
                _bookmarks.value = rows
            }
        }
    }

    fun navigateToEntry(entry: TocEntry) {
        // entry.href is the synthetic page=N token PdfTocAdapter emitted; map back
        // to the 1-based Readium position and hand a synthetic Locator to the
        // navigator, which will jump via PdfiumNavigatorFragment.go(locator).
        val pageIndex = entry.href.removePrefix("page=").toIntOrNull() ?: return
        val pub = publication ?: return
        // Find the matching Link so the navigator gets a proper href.
        val link = tocLinks.firstOrNull { l ->
            (pub.locatorFromLink(l)?.locations?.position ?: 1) - 1 == pageIndex
        } ?: return
        val locator = pub.locatorFromLink(link) ?: return
        _serverLocatorChannel.trySend(locator)
        closeToc()
    }

    fun navigateToSegment(segment: RailSegment) {
        val pageIndex = segment.href.removePrefix("page=").toIntOrNull() ?: return
        val pub = publication ?: return
        val link = tocLinks.firstOrNull { l ->
            (pub.locatorFromLink(l)?.locations?.position ?: 1) - 1 == pageIndex
        } ?: return
        val locator = pub.locatorFromLink(link) ?: return
        _serverLocatorChannel.trySend(locator)
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
        val serverId = annotationServerId ?: return
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
                    serverId = serverId,
                    itemId = itemId,
                    cfi = locatorJson,
                    textSnippet = "",
                    chapterHref = pageHref,
                    spineIndex = 0,
                    progression = totalProg,
                    bookmarkTitle = "Page $position",
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

    private fun startPeriodicSync() {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            while (true) {
                delay(PDF_SYNC_INTERVAL_MS)
                syncCurrentPosition()
            }
        }
    }

    private fun syncCurrentPosition() {
        val locator = lastLocator ?: return
        progressSyncController.sync(itemId, locator.toPayload())
    }

    fun onReaderResumed() {
        readerStateHolder.isReaderActive = true
        closeSyncDone = false
        initialLocatorSeen = false
        if (_state.value is ReaderState.Ready) {
            syncCurrentPosition()
            startPeriodicSync()
        }
    }

    fun onReaderClosed() {
        readerStateHolder.isReaderActive = false
        readerStateHolder.isPanelOpen = false
        syncJob?.cancel()
        closePdfTextHandles()
        if (closeSyncDone) return
        closeSyncDone = true
        val locator = lastLocator ?: return
        viewModelScope.launch {
            val payload = locator.toPayload()
            positionSaveCoordinator.onClose(locator.toJSON().toString(), payload.ebookProgress)
            progressSyncController.sync(itemId, payload)
        }
    }

    override fun onCleared() {
        super.onCleared()
        closePdfTextHandles()
    }

    private fun closePdfTextHandles() {
        openedTextPagePtrs.values.forEach { if (it != 0L) PdfiumTextApi.closeTextPage(it) }
        openedTextPagePtrs.clear()
        openedPagePtrs.values.forEach { if (it != 0L) PdfiumTextApi.closePage(it) }
        openedPagePtrs.clear()
        if (pdfTextDocPtr != 0L) {
            PdfiumTextApi.closeDocument(pdfTextDocPtr)
            pdfTextDocPtr = 0L
        }
        pdfPageDimensionsCache.clear()
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
}
