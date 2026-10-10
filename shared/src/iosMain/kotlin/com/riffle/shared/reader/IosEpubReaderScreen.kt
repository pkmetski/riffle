package com.riffle.shared.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitViewController
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.LazyPublicationCapability
import com.riffle.core.catalog.LazyPublicationShape
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.BookFormattingOverrides
import com.riffle.core.domain.BookFormattingPreferencesStore
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
import com.riffle.core.models.ScreenDimensionBucket
import com.riffle.core.models.ScreenDimensionBucket.SizeClass
import com.riffle.core.models.SessionPayload
import com.riffle.core.models.TocEntry
import com.riffle.feature.designsystem.RiffleIcons
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.reader.AutoScrollStall
import com.riffle.feature.reader.BoundaryAdvance
import com.riffle.feature.reader.ChapterMapUiState
import com.riffle.feature.reader.ContinuousBoundaryAdvancePolicy
import com.riffle.feature.reader.FigureTapMessageParser
import com.riffle.feature.reader.FigureZoomState
import com.riffle.feature.reader.FootnoteContent
import com.riffle.feature.reader.FootnotePopupState
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
import com.riffle.feature.reader.autoScrollStallAction
import com.riffle.feature.reader.autoscroll.AutoScrollController
import com.riffle.feature.reader.autoscroll.nudgeSpeedAndPersistableWpm
import com.riffle.feature.reader.bookmarkRailPosition
import com.riffle.feature.reader.cadence.CadenceController
import com.riffle.feature.reader.cadence.CadenceInjector
import com.riffle.feature.reader.cadence.CadenceSession
import com.riffle.feature.reader.chapterMapUiState
import com.riffle.feature.reader.chapterMapVisible
import com.riffle.feature.reader.highlights.ReaderSource
import com.riffle.feature.reader.highlights.buildChapterElisionsFromAnnotations
import com.riffle.feature.reader.readiumFontFamilyName
import com.riffle.feature.reader.spineIndexOfHref
import com.riffle.feature.reader.toReadiumTextStyling
import com.riffle.feature.reader.ui.AnnotationActionsSheet
import com.riffle.feature.reader.ui.AutoScrollHudPill
import com.riffle.feature.reader.ui.AutoScrollToggleIcon
import com.riffle.feature.reader.ui.CadenceHudPill
import com.riffle.feature.reader.ui.CadenceToggleIcon
import com.riffle.feature.reader.ui.ChapterMapOverlay
import com.riffle.feature.reader.ui.FigureZoomOverlay
import com.riffle.feature.reader.ui.FootnotePopup
import com.riffle.feature.reader.ui.NoteEditorSheet
import com.riffle.feature.reader.ui.ReaderTopBar
import com.riffle.feature.reader.ui.ReturnToPositionCard
import com.riffle.feature.reader.ui.SharedAnnotationsPanel
import com.riffle.feature.reader.ui.SharedSearchTopBar
import com.riffle.feature.reader.ui.annotationSheetLabels
import com.riffle.feature.reader.ui.cadenceHudLabels
import com.riffle.feature.reader.ui.chapterMapProgressLabelTemplates
import com.riffle.feature.reader.ui.readerSwatchBackdropColor
import com.riffle.feature.reader.ui.speedHudLabels
import com.riffle.feature.player.PlaybackSpeed
import com.riffle.feature.settings.ui.readersettings.TocPanel
import com.riffle.feature.source.ui.CornerBookmarkIndicator
import com.riffle.shared.generated.resources.Res
import com.riffle.shared.generated.resources.ui_cancel
import com.riffle.shared.generated.resources.ui_close_readaloud
import com.riffle.shared.generated.resources.ui_could_not_download_book
import com.riffle.shared.generated.resources.ui_download
import com.riffle.shared.generated.resources.ui_download_readaloud_audio
import com.riffle.shared.generated.resources.ui_error
import com.riffle.shared.generated.resources.ui_forward
import com.riffle.shared.generated.resources.ui_next_chapter
import com.riffle.shared.generated.resources.ui_no_highlights_to_show
import com.riffle.shared.generated.resources.ui_previous_chapter
import com.riffle.shared.generated.resources.ui_readaloud
import com.riffle.shared.generated.resources.ui_rewind
import com.riffle.shared.readaloud.IosReadaloudSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
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
fun EpubReaderScreen(
    item: LibraryItem,
    onBack: () -> Unit,
    source: ReaderSource = ReaderSource.FullBook,
) {
    KeepReaderScreenOn()
    val bridgeFactory = koinInject<IosEpubNavigatorBridgeFactory>()
    val downloader = koinInject<IosEpubDownloader>()
    val annotationStore = koinInject<AnnotationStore>()
    val catalogRegistry = koinInject<CatalogRegistry>()
    val positionStore = koinInject<ReadingPositionStore>()
    val sessionRepository = koinInject<ReadingSessionRepository>()
    val updateReadingProgress = koinInject<UpdateReadingProgress>()
    val formattingPreferencesStore = koinInject<FormattingPreferencesStore>()
    val bookFormattingPreferencesStore = koinInject<BookFormattingPreferencesStore>()
    val appearanceCoordinator = koinInject<AppearanceCoordinator>()
    val publicationInspector = koinInject<IosPublicationInspector>()
    val readingSpeedStore = koinInject<ReadingSpeedStore>()
    val dispatchers = koinInject<DispatcherProvider>()
    val logger = koinInject<Logger>()
    val readaloudSessionFactory = koinInject<IosReadaloudSession.Factory>()
    // Landscape flag — derived from the Compose container so it updates on rotation.
    val containerSize = LocalWindowInfo.current.containerSize
    val isLandscape = containerSize.width > containerSize.height
    // Rotation-invariant screen-size key for per-book formatting overrides (ADR 0031).
    val screenDimensionBucket = remember(containerSize) {
        ScreenDimensionBucket.of(
            a = containerSize.width.dpToSizeClass(),
            b = containerSize.height.dpToSizeClass(),
        )
    }
    var bookOverrides by remember { mutableStateOf(BookFormattingOverrides()) }
    val noHighlightsText = stringResource(Res.string.ui_no_highlights_to_show)
    val couldNotDownloadBook = stringResource(Res.string.ui_could_not_download_book)
    val errorText = stringResource(Res.string.ui_error)
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
    val readaloudSession = remember(item.sourceId, item.id) {
        readaloudSessionFactory.create(item.sourceId, item.id)
    }
    DisposableEffect(item.sourceId, item.id) {
        onDispose { readaloudSession.onDestroy() }
    }
    val readaloudOpen by readaloudSession.readaloudOpen.collectAsState()
    val readaloudPlayback by readaloudSession.playbackState.collectAsState()
    val readaloudAvailable by readaloudSession.readaloudAvailable.collectAsState()
    val readaloudDownloadPromptBytes by readaloudSession.downloadPromptBytes.collectAsState()
    val readaloudDownloadProgress by readaloudSession.downloadProgress.collectAsState()
    val readaloudBarMessage by readaloudSession.barMessage.collectAsState()
    // The annotation whose actions sheet is open, or null. Set by a decoration tap and by a
    // fresh create so the sheet switches from "annotate this selection" to "edit this
    // annotation" without the user having to tap the new highlight.
    var editTargetId by remember { mutableStateOf<String?>(null) }
    // Non-null while the note editor is up; the value is the annotation id, or "" for a note
    // being written on a selection that has not been persisted yet.
    var noteEditorFor by remember { mutableStateOf<String?>(null) }
    var annotationsPanelOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    var searchResultIndex by remember { mutableStateOf(0) }
    var footnotePopupState by remember { mutableStateOf<com.riffle.feature.reader.FootnotePopupState?>(null) }
    var returnToPositionTarget by remember { mutableStateOf<String?>(null) }
    var currentBookmark by remember { mutableStateOf<Annotation?>(null) }
    // Styles picked on a selection before it is persisted. Applied by `createHighlight`.
    var pendingStyles by remember { mutableStateOf(emptySet<EmphasisStyle>()) }
    // Retained so DisposableEffect can cancel in-flight fetches on close.
    var lazyFetcher by remember { mutableStateOf<IosLazyChapterFetcher?>(null) }
    // Cached publication shape for prefetch index lookups — avoids re-fetching on every position.
    var lazyShape by remember { mutableStateOf<LazyPublicationShape?>(null) }

    // Load per-book formatting overrides on open. When the dimension bucket changes (fold/rotate),
    // reload the row for the new bucket — each screen-size class has its own settings (ADR 0031).
    LaunchedEffect(item.id, screenDimensionBucket) {
        bookOverrides = bookFormattingPreferencesStore.load(item.sourceId, item.id, screenDimensionBucket)
            ?: BookFormattingOverrides()
    }

    LaunchedEffect(item.id) {
        if (source == ReaderSource.Highlights) {
            // Elided Annotations View: build a synthetic EPUB from the book's highlights and
            // open it from a temp directory. No position restore — always opens at the start.
            val annotations = annotationStore.observeAnnotations(item.sourceId, item.id).first()
            val chapters = buildChapterElisionsFromAnnotations(annotations)
            val dirPath = IosElidedEpubAssembler.assemble(item, chapters)
            if (dirPath == null) {
                loadError = noHighlightsText
                return@LaunchedEffect
            }
            navigator.openSyntheticEpub(dirPath, null)
            coordinator.start()
            localPath = dirPath
            return@LaunchedEffect
        }

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
            loadError = couldNotDownloadBook
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
    LaunchedEffect(item.id, isLandscape) {
        combine(
            formattingPreferencesStore.preferences,
            appearanceCoordinator.resolved,
            snapshotFlow { bookOverrides },
        ) { prefs, appearance, overrides ->
            Triple(prefs, prefs.withResolvedTheme(appearance), overrides)
        }
            .collect { (stored, prefs, overrides) ->
                val effective = overrides.applyTo(prefs)
                storedPrefs = stored
                resolvedPrefs = effective
                orientationRef.value = effective.orientation
                val styling = effective.toReadiumTextStyling(isLandscape = isLandscape)
                navigator.applyReaderPreferences(
                    fontSizePercent = effective.fontSize,
                    scrollMode = epubScrollMode(effective.orientation),
                    theme = styling.theme.value,
                    // The bridge takes "" for "leave the publisher's font alone"; the shared
                    // mapping expresses that as null.
                    fontFamilyCss = effective.fontFamily.readiumFontFamilyName() ?: "",
                    lineHeightMultiplier = effective.lineSpacing,
                    pageMargins = effective.margins.toDouble(),
                    justifyText = effective.justifyText,
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

    // A tap on the body dismisses the actions sheet and toggles chrome. Readium's decorator
    // consumes a tap that landed on a decoration before it ever becomes a body tap, so this
    // cannot race with the collector above and close the sheet it just opened.
    LaunchedEffect(navigator) {
        navigator.eventFlow.collect { event ->
            when (event) {
                is NavigatorEvent.BodyTap -> {
                    editTargetId = null
                    pendingStyles = emptySet()
                    chromeVisible = !chromeVisible
                }
                is NavigatorEvent.Footnote -> {
                    footnotePopupState = FootnotePopupState(FootnoteContent(event.contentHtml))
                }
                else -> Unit
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
    // even though the Swift bridge already knew the type. `searchResultIndex` tracks which
    // result is current so the page highlights one match at a time (same as Android).
    LaunchedEffect(searchResults, searchOpen, searchResultIndex) {
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
                    isCurrent = index == searchResultIndex,
                )
            },
        )
        // Navigate to the current result each time the index changes.
        searchResults.getOrNull(searchResultIndex)?.let { match ->
            goTo(NavigatorNavigationTarget.ToLocatorJson(match.locatorJson))
        }
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
        if (source == ReaderSource.Highlights) return@LaunchedEffect
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

    // Pause Auto-Scroll and Cadence while any reader panel is open (TOC / Formatting / Search /
    // Annotations); resume on close. Mirrors Android's setAutoScrollPaused(paused, cause) logic
    // (ADR 0053). Scoped resume: only dispatch Resume when the current pause cause is PanelOpen
    // so a user-initiated UserPausedPill stop is never un-parked by a panel close.
    LaunchedEffect(annotationsPanelOpen, settingsOpen, searchOpen, tocOpen) {
        val anyOpen = annotationsPanelOpen || settingsOpen || searchOpen || tocOpen
        if (anyOpen) {
            autoScroll.dispatch(AutoScrollEvent.Pause(PauseCause.PanelOpen))
            cadence.pauseFor(CadencePauseCause.PanelOpen)
        } else {
            val s = autoScroll.state.value
            if (s is AutoScrollState.Paused && s.cause == PauseCause.PanelOpen) {
                autoScroll.dispatch(AutoScrollEvent.Resume)
            }
            cadence.resumeIfPaused()
        }
    }

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
    // Table fit: force publisher fixed-pixel-width tables to reflow within the page so they
    // don't overflow horizontally. Applies in all three reading modes (paginated, vertical,
    // continuous). Mirrors Android's TableFit RendererCapability.
    LaunchedEffect(navigator, localPath) {
        if (localPath == null) return@LaunchedEffect
        navigator.pageLoadEvents.onStart { emit(NavigatorPageLoad(0)) }.collect {
            navigator.injectTableFitScript()
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
    // Colour-change re-paint: when the user changes cadenceHighlightColor in Settings and
    // returns to the reader, re-apply the current sentence decoration with the new colour.
    // This is a separate effect (not keyed into the main follow LaunchedEffect above) so that
    // a colour change does NOT reset followedRef — resetting it would cause a spurious goTo
    // call for the already-visible sentence the next time combine emits.
    LaunchedEffect(cadence, navigator, resolvedPrefs?.cadenceHighlightColor) {
        val ref = cadence.currentFragment.value ?: return@LaunchedEffect
        val quotes = cadence.quotes.value
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
            if (position != null && source != ReaderSource.Highlights) {
                // rememberCoroutineScope is cancelled during composition teardown; use an
                // independent scope so the DB write and sync survive past onDispose.
                CoroutineScope(SupervisorJob()).launch {
                    runCatching {
                        // The locator was already written by the last onChanged; on close we
                        // persist only the progress float, per PositionSaveCoordinator's contract.
                        // finishAwareEbookProgress clamps to 1.0 when on the last page: Readium
                        // never emits totalProgression=1 in paginated/vertical mode because
                        // positions mark page starts ((N-1)/N for the last of N pages), so without
                        // the clamp a completed book syncs as 99% rather than 100%.
                        val closeProgress = com.riffle.core.domain.finishAwareEbookProgress(
                            totalProgression = position.totalProgression,
                            progression = position.progression,
                            positionCounts = spineRef.value.positionCounts,
                        )
                        positionSaver.onClose(closeProgress)
                        val payload = SessionPayload(
                            ebookLocation = position.locatorJson,
                            ebookProgress = closeProgress,
                        )
                        sessionRepository.runSyncCycle(item.id, payload, item.sourceId)
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
                BasicText(loadError ?: errorText)
            }
            localPath == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> UIKitViewController(
                factory = { bridge.viewController() },
                modifier = Modifier.fillMaxSize(),
                update = {},
            )
        }

        // Figure zoom overlay — fullscreen, above all reader layers including chrome. Shared
        // gesture logic lives in FigureZoomOverlay (reader-ui); iOS-specific image/SVG rendering
        // is injected via the imageContent/svgContent lambdas.
        FigureZoomOverlay(
            state = figureZoomState,
            onDismiss = { figureZoomState = null },
            modifier = Modifier.fillMaxSize(),
            imageContent = { href, imgModifier -> IosEpubFigureImage(href, navigator, imgModifier) },
            svgContent = { svgMarkup, imgModifier -> IosEpubSvgView(svgMarkup, imgModifier) },
        )

        // Shared M3 top bar (slides in/out with chromeVisible).
        // Auto-scroll and cadence toggles are extra actions injected by the host.
        val prefsForChrome = resolvedPrefs
        ReaderTopBar(
            visible = chromeVisible && !searchOpen,
            title = item.title,
            isReady = localPath != null,
            onBack = onBack,
            onSearch = { searchOpen = true; tocOpen = false; annotationsPanelOpen = false },
            onToc = { tocOpen = !tocOpen; searchOpen = false; annotationsPanelOpen = false },
            onAnnotations = { annotationsPanelOpen = !annotationsPanelOpen; tocOpen = false; searchOpen = false },
            onFormat = { settingsOpen = true },
            extraActions = {
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
                                runArbiter(
                                    currentRunning = currentRunningFeature(
                                        cadenceRunning = cadenceState is CadenceState.Running,
                                        autoScrollRunning = false,
                                        readaloudPlaying = readaloudPlayback.isPlaying,
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
                                        readaloudPlaying = readaloudPlayback.isPlaying,
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
                if (source != ReaderSource.Highlights) {
                    IconButton(
                        onClick = {
                            if (readaloudOpen) {
                                readaloudSession.closeReadaloud()
                            } else {
                                scope.launch { readaloudSession.openReadaloud() }
                            }
                        },
                        enabled = readaloudAvailable,
                        modifier = Modifier.testTag(TestTags.READER_READALOUD),
                    ) {
                        Icon(
                            imageVector = RiffleIcons.PlayArrow,
                            contentDescription = stringResource(Res.string.ui_readaloud),
                        )
                    }
                }
            },
        )

        // Search bar (replaces the BasicTextField list approach — results nav via prev/next).
        if (searchOpen) {
            SharedSearchTopBar(
                query = searchQuery,
                resultCount = searchResults.size,
                currentIndex = searchResultIndex,
                onQueryChange = { q ->
                    searchQuery = q
                    searchResults = emptyList()
                    searchResultIndex = 0
                    if (q.length >= 2) {
                        scope.launch {
                            navigator.search(q).collect { batch ->
                                searchResults = searchResults + batch
                            }
                        }
                    }
                },
                onPrev = {
                    if (searchResults.isNotEmpty()) {
                        searchResultIndex = (searchResultIndex - 1 + searchResults.size) % searchResults.size
                    }
                },
                onNext = {
                    if (searchResults.isNotEmpty()) {
                        searchResultIndex = (searchResultIndex + 1) % searchResults.size
                    }
                },
                onClose = { searchOpen = false; searchQuery = ""; searchResults = emptyList(); searchResultIndex = 0 },
                onNavigateBack = { searchOpen = false; searchQuery = ""; searchResults = emptyList(); searchResultIndex = 0 },
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            )
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
                labels = annotationSheetLabels(),
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
                labels = annotationSheetLabels(),
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

        // Shared annotations panel — every highlight, note, and bookmark, tap to navigate.
        if (annotationsPanelOpen) {
            SharedAnnotationsPanel(
                annotations = annotations.filter { it.type != AnnotationEntity.TYPE_EMPHASIS },
                onNavigate = { id ->
                    annotationsPanelOpen = false
                    val target = annotations.firstOrNull { it.id == id }
                    if (target != null) {
                        scope.launch {
                            goTo(NavigatorNavigationTarget.ToLocatorJson(annotationDecorationLocator(target)))
                            editTargetId = id
                        }
                    }
                },
                onDelete = { id -> scope.launch { editor.delete(id) } },
                onRename = { id, title -> scope.launch { editor.renameBookmark(id, title) } },
                onDismiss = { annotationsPanelOpen = false },
            )
        }

        // Shared TOC panel (same ModalBottomSheet Android's reader uses).
        if (tocOpen && tocEntries.isNotEmpty()) {
            val tocActiveHref = activeTocHref(locatorHref, lastTocNavigatedHref)
            TocPanel(
                entries = tocEntries,
                activeHref = tocActiveHref,
                onEntryClick = { entry ->
                    lastTocNavigatedHref = entry.href
                    tocOpen = false
                    scope.launch {
                        goTo(
                            NavigatorNavigationTarget.ToHref(
                                href = entry.href.substringBefore("#"),
                                fragment = entry.href.substringAfter("#", "").ifEmpty { null },
                            ),
                        )
                    }
                },
                onDismiss = { tocOpen = false },
            )
        }

        // Auto-scroll HUD pill — pause/resume and live WPM nudges, over everything else.
        // A nudge is persisted so it survives the reader, matching Android's
        // FormattingSession.nudgeAutoScroll.
        AutoScrollHudPill(
            state = autoScrollState,
            labels = speedHudLabels(),
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
            labels = cadenceHudLabels(),
            onPause = { cadence.pauseFor(CadencePauseCause.PanelOpen) },
            onResume = { cadence.resumeIfPaused() },
            onSlower = { cadence.nudge(-AutoScrollSpeed.STEP_WPM, storedPrefs?.cadenceWpm ?: 0) },
            onFaster = { cadence.nudge(AutoScrollSpeed.STEP_WPM, storedPrefs?.cadenceWpm ?: 0) },
        )

        // Readaloud mini-player — shown when the session is open, docked to the bottom.
        if (readaloudOpen && source != ReaderSource.Highlights) {
            IosReadaloudMiniPlayer(
                isPlaying = readaloudPlayback.isPlaying,
                speed = readaloudPlayback.speed,
                canPreviousChapter = readaloudPlayback.currentChapterIndex > 0,
                canNextChapter = readaloudPlayback.currentChapterIndex < readaloudPlayback.chapterCount - 1,
                barMessage = readaloudBarMessage,
                downloadProgress = readaloudDownloadProgress,
                onPlayPause = readaloudSession::togglePlayPause,
                onRewind = readaloudSession::skipBackward,
                onForward = readaloudSession::skipForward,
                onPreviousChapter = readaloudSession::previousChapter,
                onNextChapter = readaloudSession::nextChapter,
                onSpeedChange = readaloudSession::setSpeed,
                onClose = readaloudSession::closeReadaloud,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            )
        }

        // Readaloud download confirmation dialog.
        readaloudDownloadPromptBytes?.let { bytes ->
            IosReadaloudDownloadDialog(
                sizeBytes = bytes,
                onConfirm = { readaloudSession.startDownload() },
                onDismiss = readaloudSession::dismissDownloadPrompt,
            )
        }

        // On-screen info: the chapter map and the reading-progress labels, gated by the same five
        // FormattingPreferences flags Android's EpubReaderScreen gates them with. The composable
        // itself is the shared one in :feature:reader-ui, so the two platforms cannot drift.
        val prefs = resolvedPrefs
        val bookmarkPositions = remember(annotations, chapterMap.segments, spine.hrefs) {
            annotations
                .filter { it.type == AnnotationEntity.TYPE_BOOKMARK }
                .mapNotNull { bookmark ->
                    bookmarkRailPosition(
                        segments = chapterMap.segments,
                        chapterHref = bookmark.chapterHref,
                        progression = bookmark.progression,
                        spineHrefs = spine.hrefs,
                    )
                }
        }
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
                templates = chapterMapProgressLabelTemplates(),
                bookmarkPositions = bookmarkPositions,
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

        // Reader settings sheet ("Aa" button).
        if (settingsOpen) {
            val storedForSettings = storedPrefs
            if (storedForSettings != null) {
                val effectiveForSettings = bookOverrides.applyTo(storedForSettings)
                IosReaderSettingsSheet(
                    prefs = effectiveForSettings,
                    hasBookOverrides = !bookOverrides.isEmpty,
                    onPrefsChange = { updated ->
                        scope.launch {
                            // Save the delta as per-book overrides.
                            val updatedOverrides = bookOverrides.withChanges(effectiveForSettings, updated)
                            bookOverrides = updatedOverrides
                            bookFormattingPreferencesStore.save(
                                item.sourceId,
                                item.id,
                                screenDimensionBucket,
                                updatedOverrides,
                            )
                            // Global prefs are not touched — book overrides layer on top.
                        }
                    },
                    onReset = {
                        scope.launch {
                            bookFormattingPreferencesStore.clear(item.sourceId, item.id, screenDimensionBucket)
                            bookOverrides = BookFormattingOverrides()
                        }
                    },
                    onDismiss = { settingsOpen = false },
                )
            }
        }

        // Footnote popup.
        footnotePopupState?.let { state ->
            FootnotePopup(
                state = state,
                onDismiss = { footnotePopupState = null },
            )
        }

        // Return-to-position card (shown after internal-link navigation).
        returnToPositionTarget?.let {
            ReturnToPositionCard(
                onReturn = {
                    scope.launch {
                        goTo(NavigatorNavigationTarget.ToLocatorJson(it))
                        returnToPositionTarget = null
                    }
                },
                onDismiss = { returnToPositionTarget = null },
            )
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

// Maps a Compose logical-pixel dimension to a Material3-style window size class, using the same
// breakpoints Android's WindowSizeClass uses (600 dp = Compact/Medium, 840 dp = Medium/Expanded).
// LocalWindowInfo.containerSize is already in logical pixels on iOS (UIKit points ≈ dp).
private fun Int.dpToSizeClass(): SizeClass = when {
    this < 600 -> SizeClass.Compact
    this < 840 -> SizeClass.Medium
    else -> SizeClass.Expanded
}

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

@Suppress("ktlint:standard:function-naming")
@Composable
private fun IosReadaloudMiniPlayer(
    isPlaying: Boolean,
    speed: Float,
    canPreviousChapter: Boolean,
    canNextChapter: Boolean,
    barMessage: String?,
    downloadProgress: Float?,
    onPlayPause: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        modifier = modifier.testTag(TestTags.READALOUD_MINI_PLAYER),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            when {
                barMessage != null -> Text(
                    text = barMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .testTag(TestTags.READALOUD_OFFLINE_MESSAGE),
                )
                downloadProgress != null -> Text(
                    text = "Downloading… ${(downloadProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .testTag(TestTags.READALOUD_DOWNLOADING),
                )
                else -> {
                    val nextSpeed = speeds.firstOrNull { it > speed } ?: speeds.first()
                    TextButton(
                        onClick = { onSpeedChange(nextSpeed) },
                        modifier = Modifier.testTag(TestTags.READALOUD_SPEED),
                    ) { Text(PlaybackSpeed.label(speed)) }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onRewind, modifier = Modifier.testTag(TestTags.READALOUD_REWIND)) {
                        Icon(RiffleIcons.FastRewind, contentDescription = stringResource(Res.string.ui_rewind))
                    }
                    IconButton(
                        onClick = onPreviousChapter,
                        enabled = canPreviousChapter,
                        modifier = Modifier.testTag(TestTags.READALOUD_PREV_CHAPTER),
                    ) { Icon(RiffleIcons.SkipPrevious, contentDescription = stringResource(Res.string.ui_previous_chapter)) }
                    IconButton(onClick = onPlayPause, modifier = Modifier.testTag(TestTags.READALOUD_PLAY_PAUSE)) {
                        Icon(
                            imageVector = if (isPlaying) RiffleIcons.Pause else RiffleIcons.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                        )
                    }
                    IconButton(
                        onClick = onNextChapter,
                        enabled = canNextChapter,
                        modifier = Modifier.testTag(TestTags.READALOUD_NEXT_CHAPTER),
                    ) { Icon(RiffleIcons.SkipNext, contentDescription = stringResource(Res.string.ui_next_chapter)) }
                    IconButton(onClick = onForward, modifier = Modifier.testTag(TestTags.READALOUD_FORWARD)) {
                        Icon(RiffleIcons.FastForward, contentDescription = stringResource(Res.string.ui_forward))
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
            IconButton(onClick = onClose, modifier = Modifier.testTag(TestTags.READALOUD_CLOSE)) {
                Icon(RiffleIcons.Close, contentDescription = stringResource(Res.string.ui_close_readaloud))
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun IosReadaloudDownloadDialog(
    sizeBytes: Long,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sizeLabel = when {
        sizeBytes >= 1_000_000_000L -> "${sizeBytes / 1_000_000_000} GB"
        sizeBytes >= 1_000_000L -> "${sizeBytes / 1_000_000} MB"
        else -> "${sizeBytes / 1_000} KB"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(TestTags.READALOUD_DOWNLOAD_DIALOG),
        title = { Text(stringResource(Res.string.ui_download_readaloud_audio, sizeLabel)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(Res.string.ui_download)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.ui_cancel)) } },
    )
}
