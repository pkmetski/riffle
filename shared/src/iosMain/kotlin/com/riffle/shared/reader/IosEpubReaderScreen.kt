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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitViewController
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.LazyPublicationCapability
import com.riffle.core.catalog.LazyPublicationShape
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
import com.riffle.core.domain.usecase.UpdateReadingProgress
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.SessionPayload
import com.riffle.core.models.TocEntry
import com.riffle.feature.reader.ChapterMapUiState
import com.riffle.feature.reader.NavigatorNavigationTarget
import com.riffle.feature.reader.NavigatorPosition
import com.riffle.feature.reader.NavigatorSearchMatch
import com.riffle.feature.reader.PositionSaveCoordinator
import com.riffle.feature.reader.autoscroll.AutoScrollController
import com.riffle.feature.reader.autoscroll.nudgeSpeedAndPersistableWpm
import com.riffle.feature.reader.chapterMapUiState
import com.riffle.feature.reader.chapterMapVisible
import com.riffle.feature.reader.flattenToc
import com.riffle.feature.reader.readiumFontFamilyName
import com.riffle.feature.reader.toReadiumTextStyling
import com.riffle.feature.reader.ui.AutoScrollHudPill
import com.riffle.feature.reader.ui.AutoScrollToggleIcon
import com.riffle.feature.reader.ui.ChapterMapOverlay
import com.riffle.feature.reader.ui.ChapterMapProgressLabelTemplates
import com.riffle.feature.reader.ui.SpeedHudLabels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

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
    var localPath by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var isLazyPublication by remember { mutableStateOf(false) }
    var tocOpen by remember { mutableStateOf(false) }
    var tocEntries by remember { mutableStateOf<List<TocEntry>>(emptyList()) }
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
        navigator.getSpine().takeIf { it.isUsable }?.let { spine = it }
        if (tocEntries.isNotEmpty() && spine.isUsable) return@LaunchedEffect
        navigator.pageLoadEvents.collect {
            if (tocEntries.isEmpty()) {
                navigator.getToc().takeIf { toc -> toc.isNotEmpty() }?.let { toc -> tocEntries = toc }
            }
            if (!spine.isUsable) {
                navigator.getSpine().takeIf { it.isUsable }?.let { spine = it }
            }
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
            // `false` means the document did not move — the bottom of the resource. Android's
            // vertical mode stops there too rather than auto-advancing the chapter.
            if (!navigator.scrollByPx(px)) autoScroll.dispatch(AutoScrollEvent.ReachedEndOfBook)
        }
    }
    DisposableEffect(autoScroll) {
        onDispose { autoScroll.release() }
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

        // Top chrome row
        Row(
            modifier = Modifier
                .systemBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth()
                .align(Alignment.TopStart),
        ) {
            BasicText(text = "← Back", modifier = Modifier.clickable(onClick = onBack))
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
                        onClick = {
                            autoScroll.dispatch(
                                if (autoScrollState is AutoScrollState.Running) {
                                    AutoScrollEvent.Stop
                                } else {
                                    AutoScrollEvent.Start
                                },
                            )
                        },
                    )
                }
                if (tocEntries.isNotEmpty()) {
                    BasicText(
                        text = "TOC",
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .clickable { tocOpen = !tocOpen; searchOpen = false },
                    )
                }
                BasicText(
                    text = if (searchOpen) "✕" else "⌕",
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .clickable { searchOpen = !searchOpen; tocOpen = false; searchQuery = ""; searchResults = emptyList() },
                )
            }
        }

        // TOC sheet
        if (tocOpen && tocEntries.isNotEmpty()) {
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
                    items(flattenToc(tocEntries)) { row ->
                        BasicText(
                            text = "  ".repeat(row.depth) + row.entry.title,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .clickable {
                                    tocOpen = false
                                    scope.launch {
                                        navigator.navigateTo(
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
                        navigator.navigateTo(
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
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
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
                                        navigator.navigateTo(
                                            NavigatorNavigationTarget.ToLocatorJson(match.locatorJson),
                                        )
                                    }
                                },
                        )
                    }
                }
            }
        }
    }
}
