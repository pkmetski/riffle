package com.riffle.app.feature.reader

import android.content.res.Configuration
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.riffle.app.feature.reader.readaloud.ReadaloudDownloadDialog
import com.riffle.app.feature.reader.readaloud.ReadaloudExpandedSheet
import com.riffle.app.feature.reader.readaloud.ReadaloudMiniPlayer
import com.riffle.app.ui.theme.RiffleTheme
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.HyperlinkNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.epub.EpubLayout
import org.readium.r2.shared.publication.presentation.presentation
import org.readium.r2.shared.util.AbsoluteUrl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpubReaderScreen(
    onNavigateBack: () -> Unit,
    viewModel: EpubReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    // Raw user-picked prefs — feeds the FormattingPanel chip selection (so Auto stays
    // highlighted even though the page renders in the resolved palette).
    val pickedPrefs by viewModel.formattingPreferences.collectAsState()
    // Resolved prefs — `theme` is always concrete. Feeds Readium, the chapter rail
    // backdrop, and any palette consumer.
    val formattingPrefs by viewModel.effectiveFormattingPreferences.collectAsState()
    val hasBookOverrides by viewModel.hasBookOverrides.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val volumeKeyNavigationEnabled by viewModel.volumeKeyNavigationEnabled.collectAsState()
    val invertVolumeKeys by viewModel.invertVolumeKeys.collectAsState()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var showFormattingPanel by remember { mutableStateOf(false) }
    val immersiveState = rememberImmersiveModeState()

    // Error state never loads a WebView, so immersive's auto-hide path is never reached.
    // Force-show system bars + TopAppBar so the Back button is reachable.
    LaunchedEffect(state) {
        if (state is ReaderState.Error) immersiveState.show()
    }

    // Close reading session when screen is disposed (navigation away)
    DisposableEffect(viewModel) {
        onDispose { viewModel.onReaderClosed() }
    }

    // Pause/resume periodic sync with app lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.onReaderClosed()
                Lifecycle.Event.ON_START -> viewModel.onReaderResumed()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        // If already started (e.g. after config change), restart sync explicitly
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) viewModel.onReaderResumed()
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Screen wake lock — gated on user preference
    DisposableEffect(keepScreenOn) {
        val window = (context as? FragmentActivity)?.window
        if (keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Prevent window resizing on keyboard show/hide — avoids layout jumps when dismissing
    // the search keyboard while the reader is open.
    DisposableEffect(Unit) {
        val window = (context as? FragmentActivity)?.window
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        onDispose {
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED)
        }
    }

    // Toast on sync error
    LaunchedEffect(viewModel) {
        viewModel.syncErrorEvents.collect {
            Toast.makeText(context, "Could not sync reading progress", Toast.LENGTH_SHORT).show()
        }
    }

    val isSearchActive by viewModel.isSearchActive.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val currentSearchIndex by viewModel.currentSearchIndex.collectAsState()
    val title = (state as? ReaderState.Ready)?.title ?: ""
    val tocVisible by viewModel.tocVisible.collectAsState()
    val footnotePopup by viewModel.footnotePopup.collectAsState()

    val annotationsAvailable by viewModel.annotationsAvailable.collectAsState()
    val highlightRenders by viewModel.highlightRenders.collectAsState()
    val readaloudAvailable by viewModel.readaloudAvailable.collectAsState()
    val readaloudOpen by viewModel.readaloudOpen.collectAsState()
    val readaloudExpanded by viewModel.readaloudExpanded.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()
    val activeFragmentRef by viewModel.activeFragmentRef.collectAsState()
    val downloadPromptBytes by viewModel.downloadPromptBytes.collectAsState()
    val readaloudOfflineMessage by viewModel.readaloudOfflineMessage.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()

    // TopAppBar floats as an overlay so its show/hide never resizes the content area —
    // eliminates the compound flicker that Scaffold's topBar slot caused by reflowing the
    // WebView simultaneously with the system-bar animation.
    //
    // The WebView is intentionally NOT padded by navigationBarsPadding: with transparent
    // system bars (see MainActivity.enableEdgeToEdge + themes.xml), the page extends edge
    // to edge and the nav bar floats over the last sliver of content. Padding would carve
    // out a solid strip behind the bar that doesn't blend with any reader theme — exactly
    // the "white/black bar" the user reported when exiting Immersive mode.
    Box(modifier = Modifier.fillMaxSize()) {
        // Status bar insets are consumed at the AndroidView root (see
        // ViewCompat.setOnApplyWindowInsetsListener in EpubNavigatorView) so they never
        // reach Readium's WebViews. The floating TopAppBar carries its own
        // TopAppBarDefaults.windowInsets to position itself below the status bar when visible.
        Box(modifier = Modifier.fillMaxSize()) {
            when (val s = state) {
                ReaderState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .testTag("reader_loading"),
                    )
                }
                is ReaderState.Ready -> {
                    val locatorHref by viewModel.currentLocatorHref.collectAsState()
                    val tocEntries by viewModel.tocEntries.collectAsState()
                    LaunchedEffect(tocVisible, showFormattingPanel) {
                        if (tocVisible || showFormattingPanel) viewModel.closeSearch()
                        viewModel.onPanelStateChanged(tocVisible || showFormattingPanel)
                    }
                    EpubNavigatorView(
                        state = s,
                        formattingPrefs = formattingPrefs,
                        onPositionChanged = { locator ->
                            if (!isSearchActive) immersiveState.dismissOverlay()
                            viewModel.onPositionChanged(locator)
                            viewModel.dismissFootnotePopup()
                        },
                        onNavigationEvents = viewModel.navigationEvents,
                        serverLocatorEvents = viewModel.serverLocatorEvents,
                        searchNavigationEvents = viewModel.searchNavigationEvents,
                        searchResults = searchResults,
                        currentSearchIndex = currentSearchIndex,
                        volumeNavEvents = viewModel.volumeNavEvents,
                        onTap = immersiveState::toggle,
                        latestLocator = { viewModel.latestLocator },
                        onFootnoteTapped = viewModel::showFootnotePopup,
                        activeFragmentRef = activeFragmentRef,
                        onPlayFromHere = viewModel::playFromHere,
                        annotationsAvailable = annotationsAvailable,
                        highlightRenders = highlightRenders,
                        onHighlight = viewModel::createHighlight,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("reader_ready")
                            .semantics {
                                contentDescription = buildString {
                                    append(locatorHref ?: "")
                                    append(" theme:")
                                    append(formattingPrefs.theme.name.lowercase())
                                    append(" wake-lock:")
                                    append(if (keepScreenOn) "on" else "off")
                                }
                            },
                    )
                    if (tocVisible) {
                        TocPanel(
                            entries = tocEntries,
                            activeHref = locatorHref,
                            onEntryClick = viewModel::navigateToEntry,
                            onDismiss = viewModel::closeToc,
                        )
                    }
                }
                is ReaderState.Error -> {
                    Text(
                        s.message,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .testTag("reader_error_state"),
                    )
                }
            }
        }

        // Bottom stack (player bar above the chapter rail), both anchored to the absolute screen
        // bottom in one Column so the readaloud bar floats directly above the rail. The system nav
        // bar overlays this column without shifting it up.
        val showRailOverlay = state is ReaderState.Ready &&
            (
                formattingPrefs.showChapterMap ||
                    formattingPrefs.showReadingProgressLabels ||
                    formattingPrefs.showCurrentChapterLabel
                )
        if (state is ReaderState.Ready && (readaloudOpen || showRailOverlay)) {
            Column(modifier = Modifier.align(Alignment.BottomCenter)) {
                if (readaloudOpen) {
                    // Reference the reader-theme palette so the player matches the chapter-rail
                    // overlay backdrop (which paints readerTheme.palette.background) and the two
                    // read as one continuous, theme-following strip.
                    val readerPalette = formattingPrefs.theme.palette
                    ReadaloudMiniPlayer(
                        isPlaying = playbackState.isPlaying,
                        speed = playbackState.speed,
                        offlineMessage = readaloudOfflineMessage,
                        downloadProgress = downloadProgress,
                        containerColor = readerPalette.background,
                        contentColor = readerPalette.foreground,
                        onPlayPause = viewModel::togglePlayPause,
                        onCycleSpeed = {
                            // Cycle 0.75× → 1× → 1.25× → 1.5× → 2× → 0.75×.
                            val speeds = com.riffle.app.feature.reader.readaloud.ReadaloudController.SPEEDS
                            val idx = speeds.indexOfFirst { kotlin.math.abs(it - playbackState.speed) < 0.001f }
                            viewModel.setSpeed(if (idx < 0) 1f else speeds[(idx + 1) % speeds.size])
                        },
                        onClose = viewModel::closeReadaloud,
                        onExpand = viewModel::expandPlayer,
                    )
                }
                if (showRailOverlay) {
                    EpubChapterRailOverlay(
                        viewModel = viewModel,
                        showRail = formattingPrefs.showChapterMap,
                        showProgressLabels = formattingPrefs.showReadingProgressLabels,
                        showChapterNameLabel = formattingPrefs.showCurrentChapterLabel,
                        readerTheme = formattingPrefs.theme,
                    )
                }
            }
        }

        if (readaloudOpen && readaloudExpanded) {
            ReadaloudExpandedSheet(
                isPlaying = playbackState.isPlaying,
                speed = playbackState.speed,
                positionSec = playbackState.positionSec,
                onPlayPause = viewModel::togglePlayPause,
                onSpeedSelected = viewModel::setSpeed,
                onDismiss = viewModel::collapsePlayer,
            )
        }

        downloadPromptBytes?.let { bytes ->
            ReadaloudDownloadDialog(
                sizeBytes = bytes,
                onConfirm = { wifiOnly -> viewModel.confirmDownloadAudio(wifiOnly) },
                onDismiss = viewModel::dismissDownloadPrompt,
            )
        }

        AnimatedVisibility(
            visible = !immersiveState.isImmersive,
            enter = slideInVertically(initialOffsetY = { -it }) + expandVertically(expandFrom = Alignment.Top),
            exit = slideOutVertically(targetOffsetY = { -it }) + shrinkVertically(shrinkTowards = Alignment.Top),
        ) {
            if (isSearchActive) {
                SearchTopBar(
                    query = searchQuery,
                    resultCount = searchResults.size,
                    currentIndex = currentSearchIndex,
                    onQueryChange = viewModel::onSearchQueryChanged,
                    onPrev = viewModel::prevSearchResult,
                    onNext = viewModel::nextSearchResult,
                    onClose = viewModel::closeSearch,
                    onNavigateBack = onNavigateBack,
                )
            } else {
                TopAppBar(
                    title = { AutoResizeText(title, style = MaterialTheme.typography.titleMedium) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (state is ReaderState.Ready) {
                            IconButton(onClick = viewModel::openSearch) {
                                Icon(Icons.Default.Search, contentDescription = "Search")
                            }
                            IconButton(onClick = viewModel::openToc) {
                                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Table of Contents")
                            }
                            IconButton(onClick = { showFormattingPanel = true }) {
                                Text(
                                    "Aa",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.semantics { contentDescription = "Format" },
                                )
                            }
                            if (readaloudAvailable) {
                                IconButton(
                                    onClick = viewModel::openReadaloud,
                                    modifier = Modifier.testTag("readaloud_open"),
                                ) {
                                    Icon(Icons.Default.Headphones, contentDescription = "Readaloud")
                                }
                            }
                        }
                    },
                    colors = readerTopAppBarColors(),
                )
            }
        }

        if (showFormattingPanel) {
            FormattingPanel(
                prefs = pickedPrefs,
                hasBookOverrides = hasBookOverrides,
                onPrefsChange = { viewModel.updateFormatting(it) },
                onReset = { viewModel.resetToGlobalDefaults() },
                onDismiss = { showFormattingPanel = false },
                keepScreenOn = keepScreenOn,
                onKeepScreenOnChange = { viewModel.setKeepScreenOn(it) },
                volumeKeyNavigationEnabled = volumeKeyNavigationEnabled,
                onVolumeKeyNavigationEnabledChange = { viewModel.setVolumeKeyNavigationEnabled(it) },
                invertVolumeKeys = invertVolumeKeys,
                onInvertVolumeKeysChange = { viewModel.setInvertVolumeKeys(it) },
            )
        }
        footnotePopup?.let { popupState ->
            FootnotePopup(
                state = popupState,
                onDismiss = viewModel::dismissFootnotePopup,
            )
        }
    }
}

// Reader TopAppBar palette: same translucent black as the global nav-bar scrim so both
// bars read as a single translucent overlay on the page rather than opaque chrome.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun readerTopAppBarColors() = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
    containerColor = com.riffle.app.ui.bottomBarScrimColor(),
    titleContentColor = androidx.compose.ui.graphics.Color.White,
    navigationIconContentColor = androidx.compose.ui.graphics.Color.White,
    actionIconContentColor = androidx.compose.ui.graphics.Color.White,
)

// Isolated scope: cursorPosition updates only recompose this composable, not sibling EpubNavigatorView.
@Composable
private fun EpubChapterRailOverlay(
    viewModel: EpubReaderViewModel,
    showRail: Boolean,
    showProgressLabels: Boolean,
    showChapterNameLabel: Boolean,
    readerTheme: ReaderTheme,
    modifier: Modifier = Modifier,
) {
    val railSegments by viewModel.railSegments.collectAsState()
    val activeRailSegmentIndex by viewModel.activeRailSegmentIndex.collectAsState()
    val cursorPosition by viewModel.railCursorPosition.collectAsState()
    val darkTheme = readerTheme == ReaderTheme.Dark || readerTheme == ReaderTheme.DarkDim
    RiffleTheme(darkTheme = darkTheme) {
        // Backdrop is the exact reader-theme page colour so the strip reads as page margin,
        // not chrome — no visible band, and prose flowing behind the overlay is cleanly
        // occluded instead of bleeding through the labels.
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(readerTheme.palette.background),
        ) {
            if (showProgressLabels || showChapterNameLabel) {
                ReadingProgressLabels(
                    activeChapterIndex = activeRailSegmentIndex,
                    chapterCount = railSegments.size,
                    activeChapterTitle = railSegments.getOrNull(activeRailSegmentIndex)?.title.orEmpty(),
                    totalProgress = cursorPosition,
                    readerTheme = readerTheme,
                    showCountAndPercent = showProgressLabels,
                    showChapterName = showChapterNameLabel,
                )
            }
            if (showRail) {
                ChapterNavigationRail(
                    segments = railSegments,
                    activeIndex = activeRailSegmentIndex,
                    cursorPosition = cursorPosition,
                    onSegmentClick = viewModel::navigateToSegment,
                )
            }
        }
    }
}

// Reader-theme-paired label colour: page foreground at reduced alpha so the labels read
// as continuation of the page, not chrome — and don't compete with actual body text.
// Per-theme alpha because the same alpha across themes reads as different "loudness" depending
// on the foreground/background contrast.
private fun readerThemeLabelColor(theme: ReaderTheme): Color {
    val alpha = when (theme) {
        ReaderTheme.Light -> 0.65f
        ReaderTheme.Dark -> 0.65f
        ReaderTheme.DarkDim -> 0.85f
        ReaderTheme.Sepia -> 0.70f
        // Auto resolves upstream; treat as Light if it slips through.
        ReaderTheme.Auto -> 0.65f
    }
    return theme.palette.foreground.copy(alpha = alpha)
}

@Composable
private fun ReadingProgressLabels(
    activeChapterIndex: Int,
    chapterCount: Int,
    activeChapterTitle: String,
    totalProgress: Float,
    readerTheme: ReaderTheme,
    showCountAndPercent: Boolean,
    showChapterName: Boolean,
) {
    val chapterCountText = if (chapterCount > 0) {
        "Chapter ${(activeChapterIndex + 1).coerceAtMost(chapterCount)} of $chapterCount"
    } else {
        ""
    }
    val pctText = "%.1f%%".format(totalProgress.coerceIn(0f, 1f) * 100f)
    val textColor = readerThemeLabelColor(readerTheme)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .testTag("reading_progress_labels"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCountAndPercent) {
            Text(
                text = chapterCountText,
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                textAlign = TextAlign.Start,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .testTag("reading_progress_chapter")
                    .semantics { contentDescription = "Reading progress: $chapterCountText" },
            )
        }
        if (showChapterName) {
            Text(
                text = activeChapterTitle,
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                textAlign = TextAlign.Center,
                fontStyle = FontStyle.Italic,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(2f)
                    .testTag("reading_progress_chapter_name")
                    .semantics { contentDescription = "Current chapter: $activeChapterTitle" },
            )
        }
        if (showCountAndPercent) {
            Text(
                text = pctText,
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .testTag("reading_progress_percent")
                    .semantics { contentDescription = "Total progress: $pctText" },
            )
        }
    }
}

// Singleton so the EpubNavigatorFactory only creates one DataStore for formatting_preferences
// per process — creating multiple instances triggers a DataStore "multiple active" crash.
private val sharedEpubNavigatorConfig by lazy { EpubNavigatorFactory.Configuration() }

private const val BOUNDARY_POLL_INTERVAL_MS = 120L

/**
 * Builds a Readium [Locator] from a readaloud fragment ref — "href#fragId", or a bare "href" when
 * the ref carries no fragment. The cssSelector targets the fragment id so Readium's EPUB decorator
 * and navigator resolve the same DOM range. Shared by the synced-highlight decoration and the
 * auto-follow so both always point at the same element.
 */
private fun fragmentLocator(ref: String): Locator? {
    val hashIdx = ref.indexOf('#')
    val href = if (hashIdx >= 0) ref.substring(0, hashIdx) else ref
    val fragId = if (hashIdx >= 0) ref.substring(hashIdx + 1) else null
    val json = JSONObject()
        .put("href", href)
        .put("type", "application/xhtml+xml")
        .put(
            "locations",
            JSONObject().apply {
                if (fragId != null) {
                    put("fragments", org.json.JSONArray().put(fragId))
                    put("cssSelector", "#$fragId")
                }
            },
        )
    return Locator.fromJSON(json)
}

@OptIn(ExperimentalReadiumApi::class)
@Composable
private fun EpubNavigatorView(
    state: ReaderState.Ready,
    formattingPrefs: FormattingPreferences,
    onPositionChanged: (Locator) -> Unit,
    onNavigationEvents: Flow<Link>,
    serverLocatorEvents: Flow<Locator>,
    searchNavigationEvents: Flow<Locator>,
    searchResults: List<Locator>,
    currentSearchIndex: Int,
    volumeNavEvents: Flow<VolumeNavEvent>,
    onTap: () -> Unit,
    latestLocator: () -> Locator?,
    onFootnoteTapped: (content: String) -> Unit,
    activeFragmentRef: String?,
    onPlayFromHere: (fragmentRef: String) -> Unit,
    annotationsAvailable: Boolean,
    highlightRenders: List<EpubReaderViewModel.HighlightRender>,
    onHighlight: (Locator) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val fragmentActivity = context as? FragmentActivity ?: return
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isFixedLayout = state.publication.metadata.presentation.layout == EpubLayout.FIXED
    val coroutineScope = rememberCoroutineScope()
    val fragmentRef = remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    val containerRef = remember { mutableStateOf<ScrollBoundaryNavigationContainer?>(null) }
    // Non-State holder for current href — written by the locator coroutine, read inside
    // navigation callbacks. Using a plain array avoids triggering recomposition on scroll.
    val currentHrefHolder = remember { arrayOf<String?>(null) }
    // Tracks the isDoublePage value the current fragment was created with; null = no fragment.
    // Plain array (not MutableState) to avoid triggering recomposition.
    val fragmentDoublePageHolder = remember { arrayOf<Boolean?>(null) }
    val currentFormattingPrefs by rememberUpdatedState(formattingPrefs)

    // rememberUpdatedState ensures the listener always calls the latest onTap lambda
    // without needing to be re-created when onTap changes.
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnFootnoteTapped by rememberUpdatedState(onFootnoteTapped)
    val currentOnPlayFromHere by rememberUpdatedState(onPlayFromHere)
    val currentOnHighlight by rememberUpdatedState(onHighlight)
    val currentAnnotationsAvailable by rememberUpdatedState(annotationsAvailable)
    val currentPublication by rememberUpdatedState(state.publication)

    // The text-selection action bar is fully owned by this callback (Readium 3.0.0's
    // selectionActionModeCallback is the only supported hook, and it replaces the WebView's default
    // menu). So besides our "Play from here"/"Highlight" items we must re-add the standard
    // Copy / Search / Share actions the user expects, driven off the current selection's text.
    //
    // "Play from here": on click we read currentSelection() and derive a SMIL fragment ref. A
    // free-text selection rarely lands exactly on a SMIL <par> boundary, so we pass the selection's
    // first fragment id (locations.fragments) when present, else the bare href; the player resolves
    // the nearest narrated clip at/after that position (never restarting the book).
    val playFromHereMenuId = remember { View.generateViewId() }
    // "Highlight" is gated on annotationsAvailable so it never shows on a Storyteller-only book.
    val highlightMenuId = remember { View.generateViewId() }
    val copyMenuId = remember { View.generateViewId() }
    val searchMenuId = remember { View.generateViewId() }
    val shareMenuId = remember { View.generateViewId() }
    val playFromHereActionMode = remember {
        object : android.view.ActionMode.Callback {
            override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
                menu.add(0, copyMenuId, 0, android.R.string.copy)
                if (currentAnnotationsAvailable) menu.add(0, highlightMenuId, 1, "Highlight")
                menu.add(0, playFromHereMenuId, 2, "Play from here")
                menu.add(0, searchMenuId, 3, "Search")
                menu.add(0, shareMenuId, 4, "Share")
                return true
            }

            override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = false

            /** Runs [block] with the current selection's plain text, then clears the selection. */
            private fun withSelectionText(block: (String) -> Unit) {
                val selectable = fragmentRef.value as? org.readium.r2.navigator.SelectableNavigator ?: return
                coroutineScope.launch {
                    val selection = selectable.currentSelection() ?: return@launch
                    selection.locator.text.highlight?.takeIf { it.isNotBlank() }?.let(block)
                    selectable.clearSelection()
                }
            }

            override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem): Boolean {
                when (item.itemId) {
                    copyMenuId -> withSelectionText { text ->
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Riffle", text))
                    }
                    searchMenuId -> withSelectionText { text ->
                        val intent = android.content.Intent(android.content.Intent.ACTION_WEB_SEARCH)
                            .putExtra(android.app.SearchManager.QUERY, text)
                        if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent)
                    }
                    shareMenuId -> withSelectionText { text ->
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(android.content.Intent.EXTRA_TEXT, text)
                        context.startActivity(android.content.Intent.createChooser(send, null))
                    }
                    highlightMenuId -> {
                        val selectable = fragmentRef.value as? org.readium.r2.navigator.SelectableNavigator
                            ?: return false
                        coroutineScope.launch {
                            val selection = selectable.currentSelection() ?: return@launch
                            currentOnHighlight(selection.locator)
                            selectable.clearSelection()
                        }
                    }
                    playFromHereMenuId -> {
                        val selectable = fragmentRef.value as? org.readium.r2.navigator.SelectableNavigator
                            ?: return false
                        coroutineScope.launch {
                            val selection = selectable.currentSelection() ?: return@launch
                            val loc = selection.locator
                            val fragId = loc.locations.fragments.firstOrNull()
                            val ref = if (fragId != null) "${loc.href}#$fragId" else loc.href.toString()
                            currentOnPlayFromHere(ref)
                            selectable.clearSelection()
                        }
                    }
                    else -> return false
                }
                mode.finish()
                return true
            }

            override fun onDestroyActionMode(mode: android.view.ActionMode) {}
        }
    }
    // ConcurrentHashMap: writes happen on Dispatchers.IO from the locator
    // coroutine; reads happen on the JS binder thread inside resolveAnchorTap.
    val footnoteDocCache = remember { ConcurrentHashMap<String, Document>() }
    val tapListener = remember {
        object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                currentOnTap()
                return false
            }
        }
    }
    val fragmentListener = remember {
        object : EpubNavigatorFragment.Listener {
            // Kept as a safety net for EPUBs where Readium's own footnote
            // detection succeeds; the primary path is the JS bridge below,
            // because Readium 3.0.0's selector breaks on dotted IDs.
            override fun shouldFollowInternalLink(
                link: Link,
                context: HyperlinkNavigator.LinkContext?,
            ): Boolean {
                if (context !is HyperlinkNavigator.FootnoteContext) return true
                val plainText = Jsoup.parse(context.noteContent).text().trim()
                if (plainText.isEmpty()) return true
                currentOnFootnoteTapped(plainText)
                return false
            }

            override fun onExternalLinkActivated(url: AbsoluteUrl) = Unit
        }
    }

    // Injects the targeted typography overrides (see TypographyOverride.kt) into each newly
    // loaded reflowable page, plus the footnote-anchor install script. Both are idempotent
    // (typography deduplicates by <style> tag, footnote by window.__riffleFootnoteInstalled),
    // so repeated firings during reflow are harmless.
    val paginationListener = remember {
        object : EpubNavigatorFragment.PaginationListener {
            override fun onPageLoaded() {
                val fragment = fragmentRef.value ?: return
                coroutineScope.launch {
                    fragment.evaluateJavascript(typographyOverrideInjectionJs())
                    fragment.evaluateJavascript(FootnoteAnchorBridge.INSTALL_SCRIPT)
                }
            }
        }
    }

    // Tap on an in-document anchor inside the WebView → look up the target in
    // the cached spine doc; if it's a footnote, show the popup and tell JS to
    // preventDefault. Otherwise return false and let the WebView scroll.
    DisposableEffect(Unit) {
        FootnoteAnchorBridge.setHandler { fragmentId ->
            val text = FootnoteResolver.resolveAnchorTap(
                currentHrefHolder[0], footnoteDocCache, fragmentId,
            ) ?: return@setHandler false
            // Called from the JS binder thread; hop to main for Compose state.
            coroutineScope.launch(Dispatchers.Main) {
                currentOnFootnoteTapped(text)
            }
            true
        }
        onDispose { FootnoteAnchorBridge.setHandler(null) }
    }

    LaunchedEffect(onNavigationEvents) {
        onNavigationEvents.collect { link ->
            fragmentRef.value?.go(link)
        }
    }

    LaunchedEffect(serverLocatorEvents) {
        serverLocatorEvents.collect { locator ->
            fragmentRef.value?.go(locator)
        }
    }

    LaunchedEffect(searchNavigationEvents) {
        searchNavigationEvents.collect { locator ->
            fragmentRef.value?.go(locator)
        }
    }

    // Tracks whether we have live search decorations painted in the WebView.
    // Prevents calling applyDecorations on initial composition (before WebView content loads),
    // which crashes the WebView renderer via a premature JS evaluation. See ADR 0015.
    val hasActiveDecorations = remember { mutableStateOf(false) }

    LaunchedEffect(searchResults, currentSearchIndex) {
        if (searchResults.isEmpty()) {
            if (!hasActiveDecorations.value) return@LaunchedEffect
            val fragment = fragmentRef.value as? DecorableNavigator ?: return@LaunchedEffect
            // applyDecorations calls evaluateJavascript which requires the main thread.
            // Compose test infrastructure (FrameDeferringContinuationInterceptor) can resume
            // LaunchedEffect coroutines on DefaultDispatcher; withContext(Main) ensures safety.
            withContext(Dispatchers.Main) {
                fragment.applyDecorations(emptyList(), group = "search")
            }
            hasActiveDecorations.value = false
            return@LaunchedEffect
        }
        val fragment = fragmentRef.value as? DecorableNavigator ?: return@LaunchedEffect
        val decorations = searchResults.mapIndexed { index, locator ->
            Decoration(
                id = "search_$index",
                locator = locator,
                style = if (index == currentSearchIndex)
                    Decoration.Style.Highlight(tint = android.graphics.Color.parseColor("#FFF5A623"))
                else
                    Decoration.Style.Highlight(tint = android.graphics.Color.parseColor("#FFFDE68A")),
            )
        }
        withContext(Dispatchers.Main) {
            fragment.applyDecorations(decorations, group = "search")
        }
        hasActiveDecorations.value = true
    }

    // ---- Readaloud synced highlight --------------------------------------------------------
    // Uses the same Readium DecorableNavigator mechanism as search, on its own "readaloud" group
    // so it doesn't clobber search highlights. The active fragment ref is "href#fragId"; we build
    // a Locator with that href and a cssSelector targeting the fragment id, which is how Readium's
    // EPUB decorator resolves the DOM range for a highlight. Null clears the decoration.
    val hasReadaloudDecoration = remember { mutableStateOf(false) }
    LaunchedEffect(activeFragmentRef) {
        val fragment = fragmentRef.value as? DecorableNavigator ?: return@LaunchedEffect
        val ref = activeFragmentRef
        if (ref == null) {
            if (!hasReadaloudDecoration.value) return@LaunchedEffect
            withContext(Dispatchers.Main) {
                fragment.applyDecorations(emptyList(), group = "readaloud")
            }
            hasReadaloudDecoration.value = false
            return@LaunchedEffect
        }
        val locator = fragmentLocator(ref) ?: return@LaunchedEffect
        val decoration = Decoration(
            id = "readaloud_active",
            locator = locator,
            style = Decoration.Style.Highlight(
                tint = android.graphics.Color.parseColor("#FF7DD3FC"),
                isActive = false,
            ),
        )
        withContext(Dispatchers.Main) {
            fragment.applyDecorations(listOf(decoration), group = "readaloud")
        }
        hasReadaloudDecoration.value = true
    }

    // ---- Persisted highlights (ADR 0024) ---------------------------------------------------
    // Renders all of a book's stored highlights via the same DecorableNavigator mechanism, on its
    // own "annotations" group. Re-applied whenever the set changes — including the re-render of
    // every highlight when the book is reopened, and the immediate paint after a new highlight.
    val hasHighlightDecorations = remember { mutableStateOf(false) }
    LaunchedEffect(highlightRenders) {
        val fragment = fragmentRef.value as? DecorableNavigator ?: return@LaunchedEffect
        if (highlightRenders.isEmpty()) {
            if (!hasHighlightDecorations.value) return@LaunchedEffect
            withContext(Dispatchers.Main) {
                fragment.applyDecorations(emptyList(), group = "annotations")
            }
            hasHighlightDecorations.value = false
            return@LaunchedEffect
        }
        val decorations = highlightRenders.map { h ->
            Decoration(
                id = h.id,
                locator = h.locator,
                style = Decoration.Style.Highlight(tint = highlightTint(h.color)),
            )
        }
        withContext(Dispatchers.Main) {
            fragment.applyDecorations(decorations, group = "annotations")
        }
        hasHighlightDecorations.value = true
    }

    // ---- Auto-follow: keep the narrated sentence on screen ---------------------------------
    // Playback drives activeFragmentRef forward (audio-clock, one change per narrated sentence); the
    // page should follow the narrated sentence. Readium 3.0.0 can't enumerate visible fragments, so
    // we ask the WebView for the element's on-screen rect and act per layout:
    //
    //  - Scroll (Vertical) mode — the document overflows the viewport, so we scroll it to KEEP THE
    //    SENTENCE CENTERED, the natural karaoke-follow.
    //  - Paginated (Horizontal) mode — each page is exactly viewport-sized (nothing to scroll), so we
    //    can't centre; instead we snap to the sentence's page when it isn't cleanly on the current
    //    one. "on" requires FULL horizontal containment (within a tolerance): a reader resting at a
    //    fractional page offset shows a clipped column plus a sliver of the next, and a sentence in
    //    that sliver is "partly visible" but misaligned — requiring full containment snaps it instead
    //    of leaving the page dragged sideways. go(locator) lands on a page boundary (aligned), which
    //    also corrects the fractional offset; because we only rest on an aligned page, the saved
    //    position stays aligned too.
    //
    // A missing element (sentence in another chapter's document) reads as off → go(locator) jumps
    // chapters, so cross-chapter follow falls out for free in both modes.
    LaunchedEffect(activeFragmentRef) {
        val ref = activeFragmentRef ?: return@LaunchedEffect
        val fragment = fragmentRef.value ?: return@LaunchedEffect
        val hashIdx = ref.indexOf('#')
        if (hashIdx < 0) return@LaunchedEffect
        val fragId = ref.substring(hashIdx + 1)
        val where = fragment.evaluateJavascript(
            """
            (function(){
              var e=document.getElementById(${JSONObject.quote(fragId)});
              if(!e) return "off";
              var r=e.getBoundingClientRect();
              var se=document.scrollingElement||document.documentElement;
              if(se && se.scrollHeight > window.innerHeight + 4){
                var delta=Math.round((r.top+r.bottom)/2 - window.innerHeight/2);
                if(Math.abs(delta) > 8) window.scrollBy(0, delta);
                return "on";
              }
              var TOL=24;
              return (r.left >= -TOL && r.right <= window.innerWidth+TOL && r.top < window.innerHeight && r.bottom > 0) ? "on" : "off";
            })()
            """.trimIndent(),
        )?.trim('"')
        if (where != "off") return@LaunchedEffect
        fragmentLocator(ref)?.let { fragment.go(it, animated = false) }
    }

    LaunchedEffect(volumeNavEvents) {
        volumeNavEvents.collect { event ->
            val fragment = fragmentRef.value ?: return@collect
            val container = containerRef.value
            if (currentFormattingPrefs.orientation == ReaderOrientation.Vertical && container != null) {
                when (event) {
                    VolumeNavEvent.Forward -> {
                        val atBottom = fragment.evaluateJavascript(
                            "(window.scrollY + window.innerHeight >= document.body.scrollHeight - 4).toString()"
                        )?.trim('"') == "true"
                        container.handleVolumeScroll(forward = true, atBoundary = atBottom) { js ->
                            launch { fragment.evaluateJavascript(js) }
                        }
                    }
                    VolumeNavEvent.Backward -> {
                        val atTop = fragment.evaluateJavascript(
                            "(window.scrollY <= 4).toString()"
                        )?.trim('"') == "true"
                        container.handleVolumeScroll(forward = false, atBoundary = atTop) { js ->
                            launch { fragment.evaluateJavascript(js) }
                        }
                    }
                }
            } else {
                when (event) {
                    VolumeNavEvent.Forward -> fragment.goForward(animated = false)
                    VolumeNavEvent.Backward -> fragment.goBackward(animated = false)
                }
            }
        }
    }

    // fragmentRef.value is a key so the effect re-fires when the fragment becomes available
    // after rotation (isLandscape changes while fragmentRef is null, so the call would be lost).
    LaunchedEffect(formattingPrefs, isLandscape, fragmentRef.value) {
        fragmentRef.value?.submitPreferences(formattingPrefs.toEpubPreferences(isLandscape, isFixedLayout))
    }

    DisposableEffect(tapListener) {
        onDispose { fragmentRef.value?.removeInputListener(tapListener) }
    }

    // Remove the fragment from FM when navigating away (not on rotation) so it doesn't
    // linger in saved state and crash restoration on the next configuration change.
    DisposableEffect(Unit) {
        onDispose {
            if (!fragmentActivity.isChangingConfigurations) {
                fragmentRef.value?.let { frag ->
                    runCatching {
                        fragmentActivity.supportFragmentManager
                            .beginTransaction()
                            .remove(frag)
                            .commitNowAllowingStateLoss()
                    }
                }
            }
        }
    }

    val containerId = rememberSaveable { View.generateViewId() }

    var pullActive by remember { mutableStateOf(false) }
    var pullForward by remember { mutableStateOf(true) }
    var pullProgress by remember { mutableFloatStateOf(0f) }

    // Poll WebView scroll boundaries so ScrollBoundaryNavigationContainer can decide
    // synchronously inside ACTION_MOVE whether the user is wedged against a chapter end.
    // Readium's locator progression value is unreliable for this — it keeps emitting during
    // touch even when scroll position hasn't moved, which broke the previous staleness check.
    LaunchedEffect(fragmentRef.value, currentFormattingPrefs.orientation) {
        val fragment = fragmentRef.value ?: return@LaunchedEffect
        if (currentFormattingPrefs.orientation != ReaderOrientation.Vertical) return@LaunchedEffect
        while (true) {
            val container = containerRef.value
            if (container != null) {
                withContext(Dispatchers.Main) {
                    val atBottom = fragment.evaluateJavascript(
                        "(window.scrollY + window.innerHeight >= document.body.scrollHeight - 4).toString()"
                    )?.trim('"') == "true"
                    val atTop = fragment.evaluateJavascript(
                        "(window.scrollY <= 4).toString()"
                    )?.trim('"') == "true"
                    container.atForwardBoundary = atBottom
                    container.atBackwardBoundary = atTop
                }
            }
            delay(BOUNDARY_POLL_INTERVAL_MS)
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                ScrollBoundaryNavigationContainer(ctx).apply {
                    // Compose handles all inset-based padding (navigationBarsPadding on the outer
                    // Box, TopAppBarDefaults.windowInsets on the floating TopAppBar). Consuming
                    // insets here prevents Readium's WebViews from applying status-bar padding,
                    // which on physical devices remains non-zero even after controller.hide().
                    ViewCompat.setOnApplyWindowInsetsListener(this) { _, _ -> WindowInsetsCompat.CONSUMED }
                    onPullStarted = { fwd ->
                        pullForward = fwd
                        pullProgress = 0f
                        pullActive = true
                    }
                    onPullEnded = { pullActive = false }
                    onPullProgress = { p -> pullProgress = p }
                    val fragmentContainer = FragmentContainerView(ctx).apply { id = containerId }
                    addView(fragmentContainer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                }.also { containerRef.value = it }
            },
            update = { container ->
                container.isScrollMode = formattingPrefs.orientation == ReaderOrientation.Vertical

                val fragmentContainer = container.getChildAt(0) as? FragmentContainerView
                    ?: return@AndroidView
                val fm = fragmentActivity.supportFragmentManager

                // RS properties (colCount/colWidth) are baked into the fragment at creation time and
                // cannot be changed via submitPreferences. Recreate the fragment whenever the
                // double-page mode changes so the new RS config takes effect.
                val isDoublePage = !isFixedLayout &&
                    formattingPrefs.orientation != ReaderOrientation.Vertical &&
                    formattingPrefs.doublePageSpread &&
                    isLandscape
                val existingFrag = fragmentRef.value
                if (existingFrag != null && fragmentDoublePageHolder[0] != isDoublePage) {
                    fm.beginTransaction().remove(existingFrag).commitNow()
                    fragmentRef.value = null
                    fragmentDoublePageHolder[0] = null
                }

                // After Activity recreation, the FragmentManager may have restored an
                // EpubNavigatorFragment using the default factory (not EpubNavigatorFactory).
                // Without the factory, the fragment's WebView cannot connect to the Readium
                // streaming server. Remove any such stale fragment so the creation path below
                // can recreate it properly with latestLocator() as the initial position.
                if (fragmentRef.value == null) {
                    fm.findFragmentById(containerId)?.let { stale ->
                        fm.beginTransaction().remove(stale).commitNow()
                    }
                }

                if (fm.findFragmentById(containerId) == null) {
                    val fragmentFactory = EpubNavigatorFactory(
                        publication = state.publication,
                        configuration = sharedEpubNavigatorConfig,
                    ).createFragmentFactory(
                        initialLocator = latestLocator() ?: state.initialLocator,
                        initialPreferences = formattingPrefs.toEpubPreferences(isLandscape, isFixedLayout),
                        configuration = formattingPrefs.toFragmentConfiguration(isLandscape, isFixedLayout).apply {
                            registerBundledFonts()
                            registerJavascriptInterface(FootnoteAnchorBridge.JS_NAME) { _ ->
                                FootnoteAnchorBridge.bridge
                            }
                            // Adds the "Play from here" item to the text-selection action bar.
                            selectionActionModeCallback = playFromHereActionMode
                        },
                        listener = fragmentListener,
                        paginationListener = paginationListener,
                    )
                    fm.fragmentFactory = fragmentFactory
                    fm.beginTransaction()
                        .add(containerId, EpubNavigatorFragment::class.java, null)
                        .commitNow()
                    val fragment = fm.findFragmentById(containerId) as? EpubNavigatorFragment
                        ?: return@AndroidView
                    fragmentRef.value = fragment
                    fragmentDoublePageHolder[0] = isDoublePage
                    fragment.addInputListener(tapListener)
                    coroutineScope.launch {
                        fragment.currentLocator.collect { locator ->
                            currentHrefHolder[0] = locator.href.toString()
                            onPositionChanged(locator)
                            val key = locator.href.removeFragment().toString()
                            if (key.isNotEmpty() && !footnoteDocCache.containsKey(key)) {
                                launch(Dispatchers.IO) {
                                    val bytes = currentPublication.get(locator.href.removeFragment())
                                        ?.read()?.getOrNull() ?: return@launch
                                    footnoteDocCache[key] = FootnoteResolver.parse(
                                        String(bytes, Charsets.UTF_8)
                                    )
                                }
                            }
                        }
                    }
                }

                fragmentRef.value?.let { fragment ->
                    container.onNavigateForward = navigateForward@{
                        val idx = state.publication.readingOrder
                            .indexOfFirst { it.href.toString() == currentHrefHolder[0] }
                        if (idx < 0 || idx >= state.publication.readingOrder.size - 1) return@navigateForward
                        fragment.goForward(animated = false)
                    }
                    container.onNavigateBackward = navigateBackward@{
                        val idx = state.publication.readingOrder
                            .indexOfFirst { it.href.toString() == currentHrefHolder[0] }
                        if (idx <= 0) return@navigateBackward
                        val prevLink = state.publication.readingOrder[idx - 1]
                        val locator = Locator.fromJSON(
                            JSONObject()
                                .put("href", prevLink.href.toString())
                                .put("type", "application/xhtml+xml")
                                .put("locations", JSONObject().put("progression", 1.0))
                        ) ?: return@navigateBackward
                        coroutineScope.launch {
                            fragment.go(locator, animated = false)
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        AnimatedVisibility(
            visible = pullActive,
            enter = slideInVertically(initialOffsetY = { if (pullForward) it else -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { if (pullForward) it else -it }) + fadeOut(),
            modifier = Modifier
                .align(if (pullForward) Alignment.BottomCenter else Alignment.TopCenter)
                .padding(
                    // Clear the chapter rail (~24dp) for forward; the floating top app bar
                    // is hidden during reading so a smaller top inset is enough.
                    bottom = if (pullForward) 56.dp else 0.dp,
                    top = if (pullForward) 0.dp else 56.dp,
                ),
        ) {
            PullChip(forward = pullForward, progress = pullProgress)
        }
    }
}

@Composable
private fun PullChip(forward: Boolean, progress: Float) {
    // inverseSurface/inverseOnSurface is the same pair Material 3 uses for Snackbars — it's
    // explicitly the "always contrasts with the page" pair, so the chip stays visible in
    // light, dark, and sepia reader themes alike.
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                trackColor = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.3f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (forward) "Next chapter" else "Previous chapter",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}


// Maps an annotation colour token to a Readium highlight tint. v1 has only yellow (ADR 0024); a
// later colour-picker slice maps `color` to other tints.
private fun highlightTint(color: String): Int =
    android.graphics.Color.parseColor("#FFFDE68A")
