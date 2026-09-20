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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitViewController
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.LazyPublicationCapability
import com.riffle.core.catalog.LazyPublicationShape
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.appearance.AppearanceCoordinator
import com.riffle.core.domain.appearance.withResolvedTheme
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.SessionPayload
import com.riffle.core.models.TocEntry
import com.riffle.feature.reader.NavigatorNavigationTarget
import com.riffle.feature.reader.NavigatorSearchMatch
import com.riffle.feature.reader.flattenToc
import com.riffle.feature.reader.readiumFontFamilyName
import com.riffle.feature.reader.toReadiumTextStyling
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
    val formattingPreferencesStore = koinInject<FormattingPreferencesStore>()
    val appearanceCoordinator = koinInject<AppearanceCoordinator>()
    val publicationInspector = koinInject<IosPublicationInspector>()
    var localPath by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var isLazyPublication by remember { mutableStateOf(false) }
    var tocOpen by remember { mutableStateOf(false) }
    var tocEntries by remember { mutableStateOf<List<TocEntry>>(emptyList()) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<NavigatorSearchMatch>>(emptyList()) }
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
        ) { prefs, appearance -> prefs.withResolvedTheme(appearance) }
            .collect { prefs ->
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

    // Load TOC once the book is open (localPath becomes non-null).
    LaunchedEffect(localPath) {
        if (localPath != null) {
            val toc = navigator.getToc()
            if (toc.isNotEmpty()) tocEntries = toc
        }
    }

    // Prefetch the next chapter whenever position changes in a lazy publication.
    LaunchedEffect(item.id) {
        navigator.positionFlow.collect { position ->
            val fetcher = lazyFetcher ?: return@collect
            val shape = lazyShape ?: return@collect
            val currentIndex = shape.spine.indexOfFirst { it.fullPath == position.href }
            if (currentIndex >= 0) {
                fetcher.prefetchNext(currentIndex)
            }
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
                        positionStore.save(item.sourceId, item.id, position.locatorJson)
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

    Box(Modifier.fillMaxSize()) {
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
