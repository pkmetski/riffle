package com.riffle.shared.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitViewController
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.LazyPublicationCapability
import com.riffle.core.catalog.LazyPublicationShape
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.ReadingSpeedStore
import com.riffle.core.domain.appearance.AppearanceCoordinator
import com.riffle.core.domain.appearance.withResolvedTheme
import com.riffle.core.domain.autoscroll.AutoScrollEvent
import com.riffle.core.domain.autoscroll.AutoScrollSpeed
import com.riffle.core.domain.autoscroll.AutoScrollState
import com.riffle.core.domain.autoscroll.PauseCause
import com.riffle.core.domain.autoscroll.layoutContextFor
import com.riffle.core.domain.cadence.CadenceState
import com.riffle.core.domain.cadence.Feature
import com.riffle.core.domain.cadence.currentRunningFeature
import com.riffle.core.domain.cadence.runArbiter
import com.riffle.core.domain.normalizeEpubHref
import com.riffle.core.domain.usecase.UpdateReadingProgress
import com.riffle.core.logging.Logger
import com.riffle.core.models.Annotation
import com.riffle.core.models.EmphasisStyle
import com.riffle.core.models.HighlightColor
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.SessionPayload
import com.riffle.core.models.TocEntry
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.reader.AutoScrollStall
import com.riffle.feature.reader.BoundaryAdvance
import com.riffle.feature.reader.ChapterMapUiState
import com.riffle.feature.reader.ContinuousBoundaryAdvancePolicy
import com.riffle.feature.reader.FigureTapMessageParser
import com.riffle.feature.reader.FigureZoomState
import com.riffle.feature.reader.NarratedColumnProgression
import com.riffle.feature.reader.NavigatorDecoration
import com.riffle.feature.reader.NavigatorEvent
import com.riffle.feature.reader.NavigatorFollowResult
import com.riffle.feature.reader.NavigatorNavigationTarget
import com.riffle.feature.reader.NavigatorPageDirection
import com.riffle.feature.reader.NavigatorPageLoad
import com.riffle.feature.reader.NavigatorPosition
import com.riffle.feature.reader.NavigatorSearchMatch
import com.riffle.feature.reader.PositionSaveCoordinator
import com.riffle.feature.reader.activeTocHref
import com.riffle.feature.reader.annotationListLabel
import com.riffle.feature.reader.autoScrollStallAction
import com.riffle.feature.reader.autoscroll.AutoScrollController
import com.riffle.feature.reader.autoscroll.nudgeSpeedAndPersistableWpm
import com.riffle.feature.reader.cadence.CadenceController
import com.riffle.feature.reader.cadence.CadenceInjector
import com.riffle.feature.reader.cadence.CadenceSession
import com.riffle.feature.reader.chapterMapUiState
import com.riffle.feature.reader.chapterMapVisible
import com.riffle.feature.reader.findActiveEntry
import com.riffle.feature.reader.flattenToc
import com.riffle.feature.reader.readiumFontFamilyName
import com.riffle.feature.reader.spineIndexOfHref
import com.riffle.feature.reader.toReadiumTextStyling
import com.riffle.feature.reader.ui.AnnotationActionsSheet
import com.riffle.feature.reader.ui.AnnotationSheetLabels
import com.riffle.feature.reader.ui.AutoScrollHudPill
import com.riffle.feature.reader.ui.AutoScrollToggleIcon
import com.riffle.feature.reader.ui.CadenceHudPill
import com.riffle.feature.reader.ui.CadenceToggleIcon
import com.riffle.feature.reader.ui.ChapterMapOverlay
import com.riffle.feature.reader.ui.ChapterMapProgressLabelTemplates
import com.riffle.feature.reader.ui.NoteEditorSheet
import com.riffle.feature.reader.ui.SpeedHudLabels
import com.riffle.feature.reader.ui.readerSwatchBackdropColor
import com.riffle.feature.source.ui.CornerBookmarkIndicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.time.TimeSource
import com.riffle.core.domain.cadence.PauseCause as CadencePauseCause

/**
 * iOS EPUB reader composable. For O'Reilly lazy publications, opens directly via
 * [IosLazyChapterFetcherImpl] without downloading the full EPUB. For all other sources,
 * downloads the EPUB and uses the file-based open path. Persists the reading position via
 * [ReadingPositionStore] and syncs progress to the server via [ReadingSessionRepository].
 */
@Suppress("ktlint:standard:function-naming")
@Composable
actual fun EpubReaderScreen(item: LibraryItem, onBack: () -> Unit) {
    KeepReaderScreenOn()
    val bridgeFactory = koinInject<IosEpubNavigatorBridgeFactory>()
    val downloader = koinInject<IosEpubDownloader>()
    val annotationStore = koinInject<AnnotationStore>()
    val catalogRegistry = koinInject<CatalogRegistry>()
    val positionStore = koinInject<ReadingPositionStore>()
    val sessionRepository = koinInject<ReadingSessionRepository>()
    val updateReadingProgress = koinInject<UpdateReadingProgress>()
    val formattingPreferencesStore = koinInject<FormattingPreferencesStore>()
    val appearanceCoordinator = koinInject<AppearanceCoordinator>()
    val publicationInspector = koinInject<IosPublicationInspector>()
    val readingSpeedStore = koinInject<ReadingSpeedStore>()
    val dispatchers = koinInject<DispatcherProvider>()
    val logger = koinInject<Logger>()
    var localPath by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var isLazyPublication by remember { mutableStateOf(false) }
    var tocOpen by remember { mutableStateOf(false) }
    var tocEntries by remember { mutableStateOf<List<TocEntry>>(emptyList()) }
    // Current locator href (no fragment) — updated from the navigator position flow.
    var locatorHref by remember { mutableStateOf<String?>(null) }
    // Full href of the last TOC entry the user explicitly tapped (may include #fragment).
    var lastTocNavigatedHref by remember { mutableStateOf<String?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<NavigatorSearchMatch>>(emptyList()) }
    var chapterMap by remember { mutableStateOf(ChapterMapUiState.Empty) }
    // The resolved (Auto already collapsed) preferences the chapter map paints itself with.
    var resolvedPrefs by remember { mutableStateOf<FormattingPreferences?>(null) }
    // Reader viewport width in device pixels — auto-scroll's pace depends on how many words fit
    // on a line, so it has to be measured, not assumed.
    var viewportWidthPx by remember { mutableStateOf(0) }
    // The *stored* preferences, before Auto is resolved. Anything written back must start from
    // these: persisting the resolved copy would silently collapse ReaderTheme.Auto into whatever
    // it happened to resolve to at that moment.
    var storedPrefs by remember { mutableStateOf<FormattingPreferences?>(null) }
    var spine by remember { mutableStateOf(SpinePositions.Empty) }
    var figureZoomState by remember { mutableStateOf<FigureZoomState?>(null) }
    val scope = rememberCoroutineScope()
    val bridge = remember { bridgeFactory.create() }
    val navigator = remember(bridge) { ReadiumSwiftNavigator(bridge) }
    val coordinator = remember(navigator) {
        AnnotationDecorationCoordinator(
            sourceId = item.sourceId,
            itemId = item.id,
            annotationStore = annotationStore,
            navigator = navigator,
        )
    }
    val annotations by coordinator.annotations.collectAsState()
    // Kept in a ref the editor's suppliers read, so the editor is constructed once and still
    // sees the current spine / orientation rather than the values at first composition.
    val spineRef = remember { mutableStateOf(SpinePositions.Empty) }
    val orientationRef = remember { mutableStateOf(ReaderOrientation.Horizontal) }
    // Live `viewportSize / chapterSize` per normalised href. Kept in a ref for the same reason
    // the spine is: the editor is constructed once and must see the newest measurement.
    val viewportFractionRef = remember { mutableStateOf(emptyMap<String, Double>()) }
    val editor = remember(navigator) {
        ReaderAnnotationEditor(
            sourceId = item.sourceId,
            itemId = item.id,
            annotationStore = annotationStore,
            navigator = navigator,
            annotations = { coordinator.annotations.value },
            spineHrefs = { spineRef.value.hrefs },
            spinePositionCounts = { spineRef.value.positionCounts },
            orientation = { orientationRef.value },
            viewportFractionByHref = { viewportFractionRef.value },
        )
    }
    val selection by navigator.selectionFlow.collectAsState()
    // The annotation whose actions sheet is open, or null. Set by a decoration tap and by a
    // fresh create so the sheet switches from "annotate this selection" to "edit this
    // annotation" without the user having to tap the new highlight.
    var editTargetId by remember { mutableStateOf<String?>(null) }
    // Non-null while the note editor is up; the value is the annotation id, or "" for a note
    // being written on a selection that has not been persisted yet.
    var noteEditorFor by remember { mutableStateOf<String?>(null) }
    var annotationsPanelOpen by remember { mutableStateOf(false) }
    var currentBookmark by remember { mutableStateOf<Annotation?>(null) }
    // Styles picked on a selection before it is persisted. Applied by `createHighlight`.
    var pendingStyles by remember { mutableStateOf(emptySet<EmphasisStyle>()) }
    // Retained so DisposableEffect can cancel in-flight fetches on close.
    var lazyFetcher by remember { mutableStateOf<IosLazyChapterFetcher?>(null) }
    // Cached publication shape for prefetch index lookups — avoids re-fetching on every position.
    var lazyShape by remember { mutableStateOf<LazyPublicationShape?>(null) }

    LaunchedEffect(item.id) {
        val savedLocator = positionStore.load(item.sourceId, item.id)

        val cap = catalogRegistry.forSourceId(item.sourceId) as? LazyPublicationCapability
        if (cap != null) {
            val shape = cap.lazyPublication(item.id)
            if (shape != null) {
                isLazyPublication = true
                lazyShape = shape
                val fetcher = IosLazyChapterFetcherImpl(cap, shape, item.id)
                lazyFetcher = fetcher
                val shapeJson = IosLazyChapterFetcherImpl.serializeShape(shape)
                navigator.openLazy(shapeJson, savedLocator, fetcher)
                coordinator.start()
                localPath = "lazy" // sentinel: triggers the reader UI without a real file path
                return@LaunchedEffect
            }
        }

        // Normal file-based path.
        val path = downloader.localPath(item)
        if (path == null) {
            loadError = "Could not download book"
            return@LaunchedEffect
        }
        localPath = path
        // Fallback inbound-sync path (ADR-0013): with no locally-saved locator, a server position
        // that arrived as a bare `readingProgress` float is all we have. Resolve it through
        // Readium's locate(progression:) so the book opens where the other device left off
        // instead of at page one. The primary CFI path, when present, still wins.
        val openAt = savedLocator
            ?: locatorForProgression(publicationInspector, path, item.readingProgress.toDouble())
        navigator.open(path, openAt)
        coordinator.start()
    }

    // Apply formatting preferences (font, theme, scroll mode) to the Readium navigator.
    // Called before open so the initial render respects user settings, and whenever prefs change.
    //
    // The theme and font mapping come from `feature:reader`'s ReadiumFormattingMapping, the same
    // derivation Android's FormattingPreferencesMapper uses. The private copy that used to live
    // here had drifted on every branch: DarkDim collapsed to plain dark and lost its muted body
    // colour, and Auto was hardcoded to light instead of being resolved.
    //
    // Auto resolution comes from AppearanceCoordinator — the one place that combines the reader
    // theme, the Auto schedule and the live system-dark flag, and re-emits at each day/night
    // crossing so a book left open across the threshold repaints. `readerTheme` is already
    // concrete, so `toReadiumThemeName` never sees Auto here.
    LaunchedEffect(item.id) {
        combine(
            formattingPreferencesStore.preferences,
            appearanceCoordinator.resolved,
        ) { prefs, appearance -> prefs to prefs.withResolvedTheme(appearance) }
            .collect { (stored, prefs) ->
                storedPrefs = stored
                resolvedPrefs = prefs
                orientationRef.value = prefs.orientation
                val styling = prefs.toReadiumTextStyling()
                navigator.applyReaderPreferences(
                    fontSizePercent = prefs.fontSize,
                    scrollMode = epubScrollMode(prefs.orientation),
                    theme = styling.theme.value,
                    // The bridge takes "" for "leave the publisher's font alone"; the shared
                    // mapping expresses that as null.
                    fontFamilyCss = prefs.fontFamily.readiumFontFamilyName() ?: "",
                    lineHeightMultiplier = prefs.lineSpacing,
                    pageMargins = prefs.margins.toDouble(),
                    justifyText = prefs.justifyText,
                    // The shared mapping computes these; the bridge carries them so iOS renders
                    // what Android renders — DarkDim's muted body colour, Riffle's typography
                    // winning over the publisher stylesheet, and the single-column pin that
                    // Readium 3.3.0 needs or decorations land in the wrong place.
                    textColorArgb = styling.textColorArgb ?: 0L,
                    publisherStyles = styling.publisherStyles,
                    columnCount = styling.columnCount ?: 0,
                )
            }
    }

    // Load the TOC and the spine once the book is open (localPath becomes non-null). Readium
    // computes both asynchronously after the publication opens, so re-read on every page-load
    // event until each has arrived rather than sampling once and drawing a permanently empty
    // (or unweighted) rail.
    LaunchedEffect(localPath) {
        if (localPath == null) return@LaunchedEffect
        navigator.getToc().takeIf { it.isNotEmpty() }?.let { tocEntries = it }
        navigator.getSpine().takeIf { it.isUsable }?.let { spine = it; spineRef.value = it }
        if (tocEntries.isNotEmpty() && spine.isUsable) return@LaunchedEffect
        navigator.pageLoadEvents.collect {
            if (tocEntries.isEmpty()) {
                navigator.getToc().takeIf { toc -> toc.isNotEmpty() }?.let { toc -> tocEntries = toc }
            }
            if (!spine.isUsable) {
                navigator.getSpine().takeIf { it.isUsable }?.let { spine = it; spineRef.value = it }
            }
        }
    }

    // ---- Scroll-state probes -------------------------------------------------------------------
    //
    // `viewportSize / chapterSize` for the resource on screen. Two consumers: the bookmark
    // epsilon (`bookmarkEpsFor`, which is what decides whether the corner ribbon is lit and
    // therefore whether tapping it deletes or creates) and, indirectly, the honesty of every
    // bookmark round trip. iOS published none of it, so the epsilon always fell through to the
    // position-count proxy.
    //
    // Measured on page load and after a typography change — the two moments Readium re-lays the
    // document out — and never on scroll, which is the rule Android's `publishViewportFraction`
    // follows for the same reason (the value does not change with scroll position, and
    // re-emitting per frame is what flaked issue #399).
    LaunchedEffect(navigator) {
        navigator.viewportFractionEvents.collect { (href, fraction) ->
            val current = viewportFractionRef.value
            if (current[href] == fraction) return@collect
            viewportFractionRef.value = current + (href to fraction)
        }
    }
    LaunchedEffect(navigator, localPath, resolvedPrefs?.fontSize, resolvedPrefs?.margins, resolvedPrefs?.lineSpacing, resolvedPrefs?.orientation) {
        if (localPath == null) return@LaunchedEffect
        navigator.pageLoadEvents.onStart { emit(NavigatorPageLoad(0)) }.collect {
            val href = navigator.snapshotPosition()?.href ?: return@collect
            navigator.publishViewportFraction(normalizeEpubHref(href))
        }
    }

    // ---- Continuous mode ------------------------------------------------------------------------
    //
    // What makes Continuous continuous on a renderer that paginates per resource.
    //
    // Readium-Swift's EPUBNavigatorViewController has one scrolling mode and it renders a single
    // resource, so `epubScrollMode` mapping both Vertical and Continuous to `scroll = true` gave
    // iOS two identical modes: in each of them the reader hit the end of a chapter and had to
    // page across. Android does not have this problem because its Continuous mode is a different
    // view entirely (`ContinuousReaderView` stacks several chapters' WebViews), so a chapter
    // boundary is not an event there at all.
    //
    // This closes the gap from the other side: probe the scroll boundary, and when the reader
    // crosses it, cross the resource for them. The decision is the shared, edge-triggered
    // [ContinuousBoundaryAdvancePolicy] — reading the last paragraph at rest must not advance,
    // a chapter shorter than the viewport must not cascade, and the landing must not re-trigger.
    //
    // Backward crossings land at the *bottom* of the previous resource, which is the half that
    // makes it read as one document — and Readium-Swift already does that for free:
    // `go(to: .left)` resolves to `PageLocation.end`, and `EPUBReflowableSpreadView.scroll(
    // toProgression: 1)` sets `contentOffset.y` to the bottom natively in scroll mode (it
    // deliberately does NOT go through JS, because the JS layer cannot see the scroll view's
    // content inset). So `pageBy(Backward)` is the whole backward crossing; adding a JS
    // scroll-to-bottom on top would fight that and land short by the inset.
    //
    // Vertical deliberately keeps the page-across; the policy answers `None` for it.
    val boundaryPolicy = remember(item.id) { ContinuousBoundaryAdvancePolicy() }
    // A monotonic origin, not a wall clock: the policy's cooldown is a duration, and a wall clock
    // can jump backwards (NTP, a timezone change) and disarm it for the rest of the session.
    val clockOrigin = remember(item.id) { TimeSource.Monotonic.markNow() }

    // Every navigation the reader did not make by scrolling goes through here. A TOC tap, a
    // bookmark jump, a chapter-map segment and a search hit all land at a position the reader did
    // not scroll to — usually the top of a resource, which is a backward boundary. Left
    // unsuppressed, Continuous would read that landing as a crossing and bounce them into the
    // chapter before the one they asked for.
    val goTo: suspend (NavigatorNavigationTarget) -> Unit = { target ->
        boundaryPolicy.suppressUntilTheReaderLeavesTheBoundary()
        navigator.navigateTo(target)
    }
    LaunchedEffect(navigator, localPath, resolvedPrefs?.orientation, spine) {
        val orientation = resolvedPrefs?.orientation ?: return@LaunchedEffect
        if (localPath == null || orientation != ReaderOrientation.Continuous) return@LaunchedEffect
        // The reader lands on whatever resource was open when the mode became Continuous, very
        // possibly at its top or bottom. That landing is not a crossing.
        boundaryPolicy.suppressUntilTheReaderLeavesTheBoundary()
        while (true) {
            val position = navigator.snapshotPosition()
            if (position != null && spine.hrefs.isNotEmpty()) {
                val index = spineIndexOfHref(spine.hrefs, position.href)
                val advance = boundaryPolicy.decide(
                    orientation = orientation,
                    boundary = navigator.scrollBoundary(),
                    canGoForward = index in 0 until spine.hrefs.size - 1,
                    canGoBackward = index > 0,
                    nowMs = clockOrigin.elapsedNow().inWholeMilliseconds,
                )
                when (advance) {
                    BoundaryAdvance.Forward -> navigator.pageBy(NavigatorPageDirection.Forward)
                    BoundaryAdvance.Backward -> navigator.pageBy(NavigatorPageDirection.Backward)
                    BoundaryAdvance.None -> Unit
                }
            }
            delay(BOUNDARY_POLL_INTERVAL_MS)
        }
    }

    // ---- Annotations -------------------------------------------------------------------------
    //
    // Works in all three reading modes. Paginated and the two scroll modes (Vertical and
    // Continuous both map to Readium's `scroll` via [epubScrollMode]) differ in exactly two
    // places, and both degrade the way Android's do:
    //  - the bookmark's `fragmentAnchor`: `CAPTURE_PAGE_FRAGMENT_ANCHOR_JS` deliberately
    //    answers null for a scrolling document, which is the legacy "no anchor" shape every
    //    consumer already handles, so a scroll-mode bookmark falls back to its progression;
    //  - the note glyph's column clamp, which is a no-op where there are no columns.
    // Everything else — the selection callback, the decoration groups, the text-quote anchor,
    // the merge policy — is mode-independent because Readium resolves a decoration the same way
    // in both layouts.
    // A tap on a rendered decoration opens the actions sheet on that annotation. Readium reports
    // the decoration id, which is the annotation id — except for an emphasis layer, whose id
    // carries a `#<style>` suffix because one row can paint two decorations. Android strips a
    // `#segN` suffix at the same seam (`annotationIdOf`) for the same reason.
    LaunchedEffect(navigator) {
        navigator.decorationActivations.collect { activation ->
            editTargetId = activation.id.substringBefore('#')
            annotationsPanelOpen = false
        }
    }

    // A tap on the body dismisses the actions sheet. Readium's decorator consumes a tap that
    // landed on a decoration before it ever becomes a body tap, so this cannot race with the
    // collector above and close the sheet it just opened.
    LaunchedEffect(navigator) {
        navigator.eventFlow.collect { event ->
            if (event is NavigatorEvent.BodyTap) {
                editTargetId = null
                pendingStyles = emptySet()
            }
        }
    }

    // Track the current spine-item href so the TOC can highlight the active entry.
    LaunchedEffect(navigator) {
        navigator.positionFlow.collect { position -> locatorHref = position.href }
    }

    // Keep the corner ribbon in step with the page. Recomputed on every position change and
    // whenever the annotation set changes, because both can flip the answer.
    LaunchedEffect(navigator, annotations) {
        currentBookmark = editor.bookmarkOnCurrentPage()
        navigator.positionFlow.collect { currentBookmark = editor.bookmarkOnCurrentPage() }
    }

    // Search results painted in the page, not just listed. `searchMark` had no producer on iOS
    // even though the Swift bridge already knew the type.
    LaunchedEffect(searchResults, searchOpen) {
        if (!searchOpen || searchResults.isEmpty()) {
            navigator.applyDecorations(ReaderDecorationGroups.search, emptyList())
            return@LaunchedEffect
        }
        navigator.applyDecorations(
            ReaderDecorationGroups.search,
            searchResults.mapIndexed { index, match ->
                NavigatorDecoration.SearchMark(
                    id = "search_$index",
                    locatorJson = match.locatorJson,
                    isCurrent = index == 0,
                )
            },
        )
    }

    // The chapter map. Android derives these six values from six StateFlows on its reader
    // ViewModel; iOS has none, so it assembles them with the same shared arithmetic
    // (`chapterMapUiState`) off the navigator's position flow. Recomputed on every position
    // because that is the only thing that moves the cursor.
    LaunchedEffect(tocEntries, spine, item.id) {
        if (tocEntries.isEmpty()) return@LaunchedEffect
        combine(
            navigator.positionFlow,
            readingSpeedStore.speedSecPerPosition,
        ) { position, speed -> position to speed }.collect { (position, speed) ->
            chapterMap = chapterMapUiState(
                tocEntries = tocEntries,
                bookTitle = item.title,
                spineHrefs = spine.hrefs,
                positionCounts = spine.positionCounts,
                currentHref = position.href,
                chapterProgression = position.progression,
                totalProgression = position.totalProgression,
                speedSecPerPosition = speed,
            )
        }
    }

    // Local persistence policy is the shared PositionSaveCoordinator, the same one Android's
    // PositionOrchestrator drives: the locator on every change (hot path), the readingProgress
    // float once on close (cold path). iOS previously wrote both in onDispose only, so a
    // force-quit or a crash mid-book lost the whole session — and writing the locator on close
    // risks clobbering a freshly adopted server position (#528).
    val positionSaver = remember(item.id) {
        PositionSaveCoordinator<NavigatorPosition>(
            updateProgress = { progress -> updateReadingProgress(item.sourceId, item.id, progress) },
            savePosition = { position -> positionStore.save(item.sourceId, item.id, position.locatorJson) },
        )
    }

    LaunchedEffect(item.id) {
        observeReaderPositions(
            positions = navigator.positionFlow,
            positionSaver = positionSaver,
            lazyShape = { if (lazyFetcher != null) lazyShape else null },
            prefetchNext = { index -> lazyFetcher?.prefetchNext(index) },
        )
    }

    // ---- Auto-scroll -------------------------------------------------------------------------
    // One controller per open book (Android's is a process singleton bound in Koin, which is why
    // its FormattingSession has to defensively Stop on bind; scoping it to the composition makes
    // that unnecessary here). The ticker, the WPM→px/s conversion and the state machine are all
    // the shared ones — only the thing that consumes the pixel deltas is platform-specific.
    val autoScroll = remember(item.id) { AutoScrollController(dispatchers) }
    val autoScrollState by autoScroll.state.collectAsState()
    val density = LocalDensity.current.density

    LaunchedEffect(autoScroll, resolvedPrefs?.autoScrollWpm) {
        resolvedPrefs?.let { autoScroll.setDefaultSpeed(AutoScrollSpeed.of(it.autoScrollWpm)) }
    }
    LaunchedEffect(autoScroll) {
        // A supplier rather than a value: the pace has to follow a font-size change or a rotation
        // without restarting the session, exactly as Android's FormattingSession wires it.
        autoScroll.setLayoutContext {
            val prefs = resolvedPrefs ?: FormattingPreferences()
            layoutContextFor(prefs, viewportWidthPx, density)
        }
    }
    LaunchedEffect(autoScroll, item.id) {
        autoScroll.scrollDeltas.collect { px ->
            // `false` means the document did not move — the bottom of the resource.
            //
            // What that means depends on the mode, which is the shared [autoScrollStallAction]
            // decision. Vertical stops, because the chapter end is a wall there — the same thing
            // Android's Vertical does. Continuous crosses into the next resource and keeps
            // scrolling, which Android gets for free because its Continuous view has no resource
            // boundary at all. Collapsing the two (which is what this did) stopped hands-free
            // reading dead at every chapter end.
            if (navigator.scrollByPx(px)) return@collect
            val position = navigator.snapshotPosition()
            val index = position?.let { spineIndexOfHref(spineRef.value.hrefs, it.href) } ?: -1
            val action = autoScrollStallAction(
                orientation = orientationRef.value,
                canGoForward = index in 0 until spineRef.value.hrefs.size - 1,
            )
            when (action) {
                AutoScrollStall.AdvanceResource -> navigator.pageBy(NavigatorPageDirection.Forward)
                AutoScrollStall.EndOfBook -> autoScroll.dispatch(AutoScrollEvent.ReachedEndOfBook)
            }
        }
    }
    DisposableEffect(autoScroll) {
        onDispose { autoScroll.release() }
    }

    // ---- Cadence -----------------------------------------------------------------------------
    // Sentence-at-a-time hands-free reading (issue #403 / ADR 0047). Everything above the
    // JavaScript seam is shared: `CadenceSession` accumulates the tokenised sentences, rebinds
    // the `DomSentenceSource` and resolves the start position; `CadenceDomScript` tokenises the
    // DOM and probes the page top; `ColumnSnap` does the paginated column arithmetic;
    // `NarratedColumnProgression` decides when a sentence that wraps a column needs a page turn.
    // Only running the scripts and painting the decoration is iOS's.
    //
    // Works in all three reading modes. Paginated gets the full treatment — start-of-sentence
    // column snap plus the intra-sentence turn when a sentence wraps. Vertical and Continuous
    // both map to Readium's scroll mode (`epubScrollMode`), where `ColumnSnap`'s JS answers
    // "scroll" and the measure returns an empty list: there is no column grid, the decoration
    // scroll-into-view Readium performs is the whole follow, and that is exactly what Android's
    // Vertical/Continuous do too.
    val cadenceController = remember(item.id) { CadenceController(dispatchers) }
    // `persistWpm` is why a HUD nudge survives the reader, the same contract Android's
    // FormattingSession gives Auto-Scroll. `storedPrefs`, not `resolvedPrefs`: writing back the
    // resolved copy would silently collapse ReaderTheme.Auto.
    val cadence = remember(cadenceController) {
        CadenceSession(
            controller = cadenceController,
            scope = scope,
            logger = logger,
            persistWpm = { wpm ->
                storedPrefs?.let { stored ->
                    scope.launch { formattingPreferencesStore.update(stored.copy(cadenceWpm = wpm)) }
                }
            },
        )
    }
    val cadenceState by cadence.state.collectAsState()
    // The WebView `Intl.Segmenter` gate. Cadence has no fallback tokeniser, so a false answer
    // hides the toggle here AND is persisted so the Settings drill-in (reachable with no book
    // open) hides its row too.
    var cadenceSupported by remember(item.id) { mutableStateOf(true) }

    LaunchedEffect(cadence) {
        // Defect fixed here and on Android in the same change: `cadenceWpm` never reached a
        // running session, so Cadence always ticked at AutoScrollSpeed.Default.
        cadence.bindDefaultSpeed(formattingPreferencesStore.preferences.map { it.cadenceWpm })
    }

    // Per-chapter DOM tokenisation. Re-runs on every page-load event because Readium reports one
    // per resource and again after a reflow; the script is idempotent (it re-reads the spans it
    // already injected) so a repeat is cheap and keeps the merged map correct after a backward
    // turn. Seeded with onStart so the chapter the book opened on is tokenised without waiting
    // for the reader to turn a page.
    LaunchedEffect(cadence, localPath, resolvedPrefs?.showCadence) {
        if (localPath == null || resolvedPrefs?.showCadence != true) return@LaunchedEffect
        navigator.pageLoadEvents.onStart { emit(NavigatorPageLoad(0)) }.collect {
            navigator.cadenceFeatureDetect()?.let { supported ->
                cadenceSupported = supported
                formattingPreferencesStore.setCadencePlatformSupported(supported)
                if (!supported) return@collect
            }
            val href = navigator.snapshotPosition()?.href ?: return@collect
            // null locale: Readium-Swift's metadata language is not surfaced at the navigator
            // bridge, and the shared script falls back to the rendered document's own
            // xml:lang/lang, which is where an EPUB declares it anyway.
            when (val parsed = navigator.cadenceTokeniseChapter(href, localeTag = null)) {
                is CadenceInjector.Result.Ready ->
                    cadence.onChapterTokenised(parsed.quotes, parsed.chapterHrefs)
                CadenceInjector.Result.Unsupported -> Unit
            }
        }
    }

    // Figure-tap: inject the tap-interceptor script on every page load and collect the resulting
    // payloads to show the zoom overlay. The shim that wires `window.RiffleFigureBridge` to
    // `window.webkit.messageHandlers.*` is already injected by the Readium setupUserScripts
    // delegate; this installs the per-page click listener on top of it.
    LaunchedEffect(navigator, localPath) {
        if (localPath == null) return@LaunchedEffect
        navigator.pageLoadEvents.onStart { emit(NavigatorPageLoad(0)) }.collect {
            navigator.injectFigureTapScript()
        }
    }

    // Paginated layout lock: prevent hostile publisher CSS (e.g. O'Reilly EPUB3 `height: auto
    // !important`) from collapsing Readium's column grid and allowing vertical scrolling. The
    // injected CSS is gated on `:root:not([style*="readium-scroll-on"])` so it is a no-op in
    // scroll/continuous mode. Mirrors Android's PaginatedLayoutLock RendererCapability.
    LaunchedEffect(navigator, localPath) {
        if (localPath == null) return@LaunchedEffect
        navigator.pageLoadEvents.onStart { emit(NavigatorPageLoad(0)) }.collect {
            navigator.injectPaginatedLayoutLockScript()
        }
    }
    LaunchedEffect(navigator) {
        navigator.figureTapPayloads.collect { payload ->
            figureZoomState = FigureTapMessageParser.parse(payload)
        }
    }

    // Paint the current sentence and keep it on screen. One decoration group of its own so it
    // replaces atomically and never fights the annotation highlights.
    LaunchedEffect(cadence, navigator) {
        var followedRef: String? = null
        combine(cadence.currentFragment, cadence.quotes) { ref, quotes -> ref to quotes }
            .collect { (ref, quotes) ->
                if (ref == null) {
                    navigator.applyDecorations(DECORATION_GROUP_CADENCE, emptyList())
                    followedRef = null
                    return@collect
                }
                navigator.applyDecorations(
                    DECORATION_GROUP_CADENCE,
                    listOf(
                        cadenceDecoration(
                            fragmentRef = ref,
                            quote = quotes[ref],
                            color = (resolvedPrefs ?: FormattingPreferences()).cadenceHighlightColor,
                        ),
                    ),
                )
                // Follow only when the SENTENCE changes. This flow also re-emits when a new
                // chapter is tokenised (the quote map grows), and re-snapping then would yank a
                // reader who had just paged ahead back to the highlight.
                if (ref == followedRef) return@collect
                followedRef = ref
                // "absent" means the sentence is in another resource — the ticker crossed a
                // chapter boundary before the navigator did — so navigate to its chapter.
                val spanId = ref.substringAfter('#', "")
                if (spanId.isNotEmpty() &&
                    navigator.followCadenceSpan(spanId) == NavigatorFollowResult.OffPage
                ) {
                    goTo(NavigatorNavigationTarget.ToHref(ref.substringBefore('#')))
                }
            }
    }

    // Intra-sentence page follow (paginated only): a sentence that wraps a column boundary leaves
    // its tail on the next page while the highlight is still on it. The ticker's per-sentence
    // dwell fraction maps onto the sentence's measured column layout, and the page turns at the
    // estimated crossing — the same `NarratedColumnProgression` Android drives from audio timing.
    val cadenceColumns = remember(item.id) { NarratedColumnProgression() }
    LaunchedEffect(cadence, navigator) {
        var measuredRef: String? = null
        combine(cadence.currentFragment, cadence.currentProgress) { ref, p -> ref to p }
            .collect { (ref, progress) ->
                if (ref == null || progress == null) {
                    cadenceColumns.reset()
                    measuredRef = null
                    return@collect
                }
                val spanId = ref.substringAfter('#', "")
                if (spanId.isEmpty()) return@collect
                if (ref != measuredRef) {
                    // Empty in scroll mode (Vertical/Continuous) — the progression then never
                    // advances, which is the right answer for a document with no column grid.
                    cadenceColumns.onSentence(navigator.measureCadenceColumns(spanId))
                    measuredRef = ref
                }
                cadenceColumns.advance(progress)?.let { navigator.snapCadenceColumn(spanId, it) }
            }
    }

    // End-of-chapter auto-advance. The session's state stays Running across the turn, so the next
    // chapter's tokenisation rebinds the source and the ticker carries on with no user tap.
    LaunchedEffect(cadence, navigator) {
        cadence.endOfChapterEvents.collect { navigator.pageBy(NavigatorPageDirection.Forward) }
    }

    DisposableEffect(cadenceController) {
        onDispose {
            cadence.reset()
            cadenceController.release()
        }
    }

    DisposableEffect(item.id) {
        onDispose {
            coordinator.stop()
            val position = navigator.snapshotPosition()
            if (position != null) {
                // rememberCoroutineScope is cancelled during composition teardown; use an
                // independent scope so the DB write and sync survive past onDispose.
                CoroutineScope(SupervisorJob()).launch {
                    runCatching {
                        // The locator was already written by the last onChanged; on close we
                        // persist only the progress float, per PositionSaveCoordinator's contract.
                        positionSaver.onClose(position.totalProgression ?: position.progression)
                        val payload = SessionPayload(
                            ebookLocation = position.locatorJson,
                            ebookProgress = position.totalProgression ?: position.progression,
                        )
                        sessionRepository.runSyncCycle(item.id, payload)
                    }
                }
            }
            navigator.close()
            lazyFetcher?.dispose()
            lazyFetcher = null
        }
    }

    Box(Modifier.fillMaxSize().onSizeChanged { viewportWidthPx = it.width }) {
        when {
            loadError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText(loadError ?: "Error")
            }
            localPath == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText("Opening book…")
            }
            else -> UIKitViewController(
                factory = { bridge.viewController() },
                modifier = Modifier.fillMaxSize(),
                update = {},
            )
        }

        // Figure zoom overlay — fullscreen, above all reader layers including chrome. Mirrors the
        // Android placement at the top of EpubReaderScreen's outer Box.
        IosEpubFigureZoomOverlay(
            state = figureZoomState,
            navigator = navigator,
            onDismiss = { figureZoomState = null },
            modifier = Modifier.fillMaxSize(),
        )

        // Top chrome row
        Row(
            modifier = Modifier
                .systemBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth()
                .align(Alignment.TopStart),
        ) {
            BasicText(text = "← Back", modifier = Modifier.testTag(TestTags.IOS_READER_BACK).clickable(onClick = onBack))
            Spacer(modifier = Modifier.weight(1f))
            if (localPath != null) {
                // Auto-scroll only makes sense where the document scrolls. Android gates its
                // toggle on Vertical || Continuous for the same reason; on iOS both map to
                // Readium's scroll mode via `epubScrollMode`, and paginated has nothing to scroll.
                val prefsForChrome = resolvedPrefs
                if (prefsForChrome != null &&
                    prefsForChrome.showAutoScroll &&
                    prefsForChrome.orientation != ReaderOrientation.Horizontal
                ) {
                    AutoScrollToggleIcon(
                        isRunning = autoScrollState is AutoScrollState.Running,
                        modifier = Modifier.testTag(TestTags.IOS_READER_AUTOSCROLL),
                        onClick = {
                            if (autoScrollState is AutoScrollState.Running) {
                                autoScroll.dispatch(AutoScrollEvent.Stop)
                            } else {
                                // Mutual exclusion (ADR 0047): whichever hands-free feature the
                                // user starts parks the other. Pause, not Stop, so the parked
                                // Cadence session keeps its position and its speed.
                                runArbiter(
                                    currentRunning = currentRunningFeature(
                                        cadenceRunning = cadenceState is CadenceState.Running,
                                        autoScrollRunning = false,
                                        readaloudPlaying = false,
                                    ),
                                    starting = Feature.AutoScroll,
                                    stopAutoScroll = { autoScroll.dispatch(AutoScrollEvent.Stop) },
                                    pauseCadence = cadence::pauseFor,
                                )
                                autoScroll.dispatch(AutoScrollEvent.Start)
                            }
                        },
                    )
                }
                // Cadence works in every reading mode — paginated snaps columns, the two scroll
                // modes let Readium bring the decoration into view — so unlike auto-scroll its
                // toggle is not gated on orientation. It IS gated on the WebView's
                // `Intl.Segmenter` probe, because there is no fallback tokeniser.
                if (prefsForChrome != null && prefsForChrome.showCadence && cadenceSupported) {
                    val cadenceRunning = cadenceState is CadenceState.Running
                    CadenceToggleIcon(
                        isRunning = cadenceRunning,
                        modifier = Modifier.testTag(TestTags.IOS_READER_CADENCE),
                        onClick = {
                            if (cadenceRunning) {
                                cadence.stop()
                            } else {
                                runArbiter(
                                    currentRunning = currentRunningFeature(
                                        cadenceRunning = false,
                                        autoScrollRunning = autoScrollState is AutoScrollState.Running,
                                        readaloudPlaying = false,
                                    ),
                                    starting = Feature.Cadence,
                                    stopAutoScroll = { autoScroll.dispatch(AutoScrollEvent.Stop) },
                                    pauseCadence = cadence::pauseFor,
                                )
                                scope.launch { startCadenceFromCurrentPage(navigator, cadence) }
                            }
                        },
                    )
                }
                BasicText(
                    text = "✎",
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .testTag(TestTags.IOS_READER_ANNOTATIONS)
                        .clickable {
                            annotationsPanelOpen = !annotationsPanelOpen
                            tocOpen = false
                            searchOpen = false
                        },
                )
                if (tocEntries.isNotEmpty()) {
                    BasicText(
                        text = "TOC",
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .testTag(TestTags.IOS_READER_TOC)
                            .clickable { tocOpen = !tocOpen; searchOpen = false; annotationsPanelOpen = false },
                    )
                }
                BasicText(
                    text = if (searchOpen) "✕" else "⌕",
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .testTag(TestTags.IOS_READER_SEARCH)
                        .clickable { searchOpen = !searchOpen; tocOpen = false; searchQuery = ""; searchResults = emptyList() },
                )
            }
        }

        // The corner bookmark ribbon — the shared composable Android's three readers and its
        // audiobook player render, not the blue in-page wash iOS used to paint. Tapping it
        // creates or removes the bookmark on this page, using the same shared epsilon that
        // decides whether it is lit (see `bookmarkEpsFor`), so the two can never disagree.
        if (localPath != null) {
            CornerBookmarkIndicator(
                isBookmarked = currentBookmark != null,
                isVisible = true,
                onToggle = { scope.launch { editor.toggleBookmark() } },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .systemBarsPadding()
                    .padding(top = 40.dp, end = 8.dp)
                    .testTag(TestTags.IOS_READER_BOOKMARK),
            )
        }

        // The annotate sheet. Shown for a live selection, or for the annotation the reader just
        // tapped. One sheet for both because the actions are the same — which is how Android's
        // `HighlightActionsPopup` works too; only the anchoring differs (a Popup next to the
        // tapped rect there, docked to the bottom here).
        val editTarget = annotations.firstOrNull { it.id == editTargetId }
        val liveSelection = selection
        if (noteEditorFor == null && (editTarget != null || liveSelection != null)) {
            // The reader's own paper colour, not the app surface: an alpha-0x80 swatch composited
            // over the wrong backdrop previews a colour the book will never show. Same shared
            // derivation Android's popup reads.
            val readerBackground = (resolvedPrefs ?: FormattingPreferences()).readerSwatchBackdropColor
            AnnotationActionsSheet(
                selectedColor = editTarget?.let {
                    if (it.color.isEmpty()) null else HighlightColor.fromToken(it.color)
                },
                emphasisStyles = editTarget?.let { editor.emphasisStylesFor(it) } ?: pendingStyles,
                note = editTarget?.note,
                readerBackground = readerBackground,
                labels = AnnotationSheetLabels.English,
                onPickColor = { color ->
                    scope.launch {
                        if (editTarget != null) {
                            editor.recolor(editTarget.id, color)
                        } else if (liveSelection != null) {
                            editor.createHighlight(liveSelection, color, pendingStyles, null)
                                ?.let { editTargetId = it.id }
                            pendingStyles = emptySet()
                        }
                    }
                },
                onRemoveColor = {
                    scope.launch {
                        if (editTarget != null) {
                            editor.recolor(editTarget.id, null)
                        } else if (liveSelection != null) {
                            editor.createHighlight(liveSelection, null, pendingStyles, null)
                                ?.let { editTargetId = it.id }
                            pendingStyles = emptySet()
                        }
                    }
                },
                onToggleEmphasis = { style ->
                    scope.launch {
                        if (editTarget != null) {
                            editor.toggleEmphasis(editTarget, style)
                        } else {
                            // ADR 0056 §4: a chip tapped on a bare selection persists the
                            // highlight (with no colour) plus its emphasis sibling, so the
                            // formatting has something to anchor to.
                            val next = if (style in pendingStyles) {
                                pendingStyles - style
                            } else {
                                pendingStyles + style
                            }
                            pendingStyles = next
                            if (liveSelection != null && next.isNotEmpty()) {
                                editor.createHighlight(liveSelection, null, next, null)
                                    ?.let { editTargetId = it.id }
                                pendingStyles = emptySet()
                            }
                        }
                    }
                },
                onOpenNoteEditor = { noteEditorFor = editTarget?.id ?: "" },
                onDelete = editTarget?.let { target ->
                    {
                        scope.launch { editor.delete(target.id) }
                        editTargetId = null
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
            )
        }

        // The note editor. `""` means the note is being written on a selection that has not been
        // persisted yet — confirming it creates the highlight and the note in one go, which is
        // what Android's `commitDraftFromNoteEditor` does.
        noteEditorFor?.let { target ->
            val existing = annotations.firstOrNull { it.id == target }
            NoteEditorSheet(
                initialNote = existing?.note.orEmpty(),
                labels = AnnotationSheetLabels.English,
                onConfirm = { text ->
                    scope.launch {
                        if (existing != null) {
                            editor.setNote(existing.id, text)
                        } else {
                            selection?.let { sel ->
                                editor.createHighlight(sel, null, pendingStyles, text)
                                    ?.let { editTargetId = it.id }
                            }
                            pendingStyles = emptySet()
                        }
                    }
                    noteEditorFor = null
                },
                onDismiss = { noteEditorFor = null },
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(16.dp),
            )
        }

        // The annotations panel — every highlight, note and bookmark on the book, tap to go
        // there. This is what makes "navigate to an annotation" reachable at all on iOS.
        if (annotationsPanelOpen) {
            Box(modifier = Modifier.fillMaxSize().padding(top = 56.dp)) {
                LazyColumn(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .fillMaxWidth(0.8f)
                        .padding(8.dp),
                ) {
                    items(annotations.filter { it.type != AnnotationEntity.TYPE_EMPHASIS }) { a ->
                        BasicText(
                            text = annotationListLabel(a),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .clickable {
                                    annotationsPanelOpen = false
                                    scope.launch {
                                        goTo(
                                            NavigatorNavigationTarget.ToLocatorJson(
                                                annotationDecorationLocator(a),
                                            ),
                                        )
                                        editTargetId = a.id
                                    }
                                },
                        )
                    }
                }
            }
        }

        // TOC sheet
        if (tocOpen && tocEntries.isNotEmpty()) {
            val tocActiveHref = activeTocHref(locatorHref, lastTocNavigatedHref)
            val activeEntry = remember(tocEntries, tocActiveHref) {
                tocActiveHref?.let { findActiveEntry(tocEntries, it) }
            }
            val flatTocRows = remember(tocEntries) { flattenToc(tocEntries) }
            val activePrimary = MaterialTheme.colorScheme.primary
            val defaultColor = MaterialTheme.colorScheme.onSurface
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 56.dp),
            ) {
                LazyColumn(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .fillMaxWidth(0.75f)
                        .padding(8.dp),
                ) {
                    items(flatTocRows) { row ->
                        val isActive = row.entry === activeEntry
                        BasicText(
                            text = "  ".repeat(row.depth) + row.entry.title,
                            style = TextStyle(color = if (isActive) activePrimary else defaultColor),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .clickable {
                                    lastTocNavigatedHref = row.entry.href
                                    tocOpen = false
                                    scope.launch {
                                        goTo(
                                            NavigatorNavigationTarget.ToHref(
                                                href = row.entry.href.substringBefore("#"),
                                                fragment = row.entry.href.substringAfter("#", "").ifEmpty { null },
                                            ),
                                        )
                                    }
                                },
                        )
                    }
                }
            }
        }

        // Auto-scroll HUD pill — pause/resume and live WPM nudges, over everything else.
        // A nudge is persisted so it survives the reader, matching Android's
        // FormattingSession.nudgeAutoScroll.
        AutoScrollHudPill(
            state = autoScrollState,
            labels = SpeedHudLabels.English,
            onPause = { autoScroll.dispatch(AutoScrollEvent.Pause(PauseCause.UserPausedPill)) },
            onResume = { autoScroll.dispatch(AutoScrollEvent.Resume) },
            onSlower = {
                val stored = storedPrefs
                val newWpm = autoScroll.nudgeSpeedAndPersistableWpm(-AutoScrollSpeed.STEP_WPM, stored?.autoScrollWpm ?: 0)
                if (stored != null && newWpm != null) {
                    scope.launch { formattingPreferencesStore.update(stored.copy(autoScrollWpm = newWpm)) }
                }
            },
            onFaster = {
                val stored = storedPrefs
                val newWpm = autoScroll.nudgeSpeedAndPersistableWpm(AutoScrollSpeed.STEP_WPM, stored?.autoScrollWpm ?: 0)
                if (stored != null && newWpm != null) {
                    scope.launch { formattingPreferencesStore.update(stored.copy(autoScrollWpm = newWpm)) }
                }
            },
        )

        // Cadence HUD pill — same shape and baseline as the auto-scroll pill; mutual exclusion
        // guarantees only one is ever on screen. A nudge persists, matching Android's
        // `EpubReaderViewModel.nudgeCadence` (which did not, until this change).
        CadenceHudPill(
            state = cadenceState,
            labels = SpeedHudLabels.EnglishCadence,
            onPause = { cadence.pauseFor(CadencePauseCause.PanelOpen) },
            onResume = { cadence.resumeIfPaused() },
            onSlower = { cadence.nudge(-AutoScrollSpeed.STEP_WPM, storedPrefs?.cadenceWpm ?: 0) },
            onFaster = { cadence.nudge(AutoScrollSpeed.STEP_WPM, storedPrefs?.cadenceWpm ?: 0) },
        )

        // On-screen info: the chapter map and the reading-progress labels, gated by the same five
        // FormattingPreferences flags Android's EpubReaderScreen gates them with. The composable
        // itself is the shared one in :feature:reader-ui, so the two platforms cannot drift.
        val prefs = resolvedPrefs
        if (prefs != null && chapterMap.segments.isNotEmpty() && chapterMapVisible(prefs)) {
            ChapterMapOverlay(
                segments = chapterMap.segments,
                activeIndex = chapterMap.activeIndex,
                cursorPosition = chapterMap.cursorPosition,
                totalProgress = chapterMap.labelProgress,
                readerTheme = prefs.theme,
                showRail = prefs.showChapterMap,
                coloredChapterMap = prefs.coloredChapterMap,
                showCurrentChapterLabel = prefs.showCurrentChapterLabel,
                showProgressLabels = prefs.showReadingProgressLabels,
                showReadingTimeEstimate = prefs.showReadingTimeEstimate,
                // iOS has no string-resource mechanism yet (#1072's i18n item), so the host hands
                // the shared overlay the English catalogue. Android hands it its own res/values*.
                templates = ChapterMapProgressLabelTemplates.English,
                chapterTimeRemaining = chapterMap.chapterTimeRemaining,
                bookTimeRemaining = chapterMap.bookTimeRemaining,
                onSegmentClick = { segment ->
                    scope.launch {
                        goTo(
                            NavigatorNavigationTarget.ToHref(
                                href = segment.href.substringBefore("#"),
                                fragment = segment.href.substringAfter("#", "").ifEmpty { null },
                            ),
                        )
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            )
        }

        KoFiNudgeOverlay(
            positionFlow = navigator.positionFlow,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        // Search bar + results
        if (searchOpen) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 56.dp)
                    .align(Alignment.TopCenter),
            ) {
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { q ->
                        searchQuery = q
                        searchResults = emptyList()
                        if (q.length >= 2) {
                            scope.launch {
                                navigator.search(q).collect { batch ->
                                    searchResults = searchResults + batch
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag(TestTags.IOS_READER_SEARCH_FIELD),
                )
                Spacer(modifier = Modifier.height(4.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    items(searchResults) { match ->
                        BasicText(
                            text = match.snippet.take(120),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    scope.launch {
                                        goTo(NavigatorNavigationTarget.ToLocatorJson(match.locatorJson))
                                    }
                                },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Start Cadence at the sentence the reader is currently looking at.
 *
 * The probe is the shared, section-aware [com.riffle.feature.reader.cadence.CadenceDomScript]
 * rule: a heading at the top of the page wins, otherwise the section the reader is inside,
 * otherwise the first visible sentence. Starting without it would drop the ticker on
 * `orderedFragments[0]` — the first sentence of whichever chapter was tokenised first this
 * session — and Readium would then scroll the reader back to it.
 *
 * `internal` and top-level rather than a lambda inside the Composable so the start decision is
 * reachable from a test.
 */
/**
 * How often Continuous re-reads the scroll boundary.
 *
 * The same 120 ms Android's Vertical boundary poll uses (`EpubReaderScreen.BOUNDARY_POLL_INTERVAL_MS`),
 * and for the same reason: Readium's locator progression is not a usable boundary signal — it
 * keeps re-emitting while a touch moves nothing, and it stops emitting exactly when the reader is
 * wedged against the end, which is the moment that matters. A direct read of the scroll state is
 * the only honest answer, and 120 ms is fast enough that the crossing feels like part of the
 * gesture rather than a delayed jump.
 */
private const val BOUNDARY_POLL_INTERVAL_MS = 120L

@Composable
private fun KoFiNudgeOverlay(
    positionFlow: kotlinx.coroutines.flow.Flow<com.riffle.feature.reader.NavigatorPosition>,
    modifier: Modifier = Modifier,
) {
    val shownFlow = remember { MutableStateFlow(false) }
    val shown by shownFlow.collectAsState()
    LaunchedEffect(Unit) {
        com.riffle.feature.designsystem.collectKoFiProgressionNudge(
            positionFlow.map { it.totalProgression },
        ) { shownFlow.value = true }
    }
    com.riffle.feature.designsystem.KoFiNudgeCard(
        visible = shown,
        onNotNow = { shownFlow.value = false },
        onSupport = { shownFlow.value = false },
        modifier = modifier,
    )
}

internal suspend fun startCadenceFromCurrentPage(
    navigator: ReadiumSwiftNavigator,
    cadence: CadenceSession,
) {
    val href = navigator.snapshotPosition()?.href
    if (href == null) {
        cadence.startWithoutProbe()
    } else {
        cadence.onPageTopResolved(href, navigator.cadenceStartSpanId())
    }
}
