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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.res.painterResource
import com.riffle.app.R
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
import androidx.compose.ui.platform.LocalDensity
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
import com.riffle.app.feature.reader.readaloud.NarratedColumnProgression
import com.riffle.app.feature.reader.readaloud.PlayerCoordinator
import com.riffle.app.feature.reader.readaloud.ReadaloudDownloadDialog
import com.riffle.app.feature.reader.readaloud.ReadaloudMiniPlayer
import com.riffle.app.feature.reader.readaloud.ReadaloudPeek
import com.riffle.app.ui.theme.RiffleTheme
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReadaloudHighlightColor
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.SentenceQuote
import com.riffle.core.domain.ReaderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject
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
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.util.AbsoluteUrl

// Gates the "Highlight" text-selection action. ADR 0024 shipped highlight create + render +
// sync-ready persistence, but there's still no UI to view, delete, or recolor highlights — so
// creating one is a dead end. Keep the create/render plumbing live but hide the affordance until
// that management UI exists. Flip to true to re-enable.
private const val ANNOTATIONS_UI_ENABLED = false

/**
 * A generation counter that increments a few times shortly after [reflowTrigger] changes — so
 * decoration effects keyed on it re-apply against the reflowed layout. Pass a value that changes
 * whenever the layout reflows; in practice the formatting preferences (orientation/scroll mode, font
 * size, margins, line spacing, font family all reflow the page via submitPreferences).
 *
 * Readium does not re-render existing decorations onto a reflowed layout, so a stable highlight —
 * e.g. a paused readaloud sentence, whose activeFragmentRef isn't changing to re-trigger its own
 * effect — ends up painted on the wrong sentence. The relayout settles asynchronously; for an
 * in-place reflow there's no onPageLoaded to hang the re-apply on, so we bump a few times across a
 * short settle window: the bump that lands once the relayout has completed is the one that makes the
 * re-apply stick, and the earlier/duplicate re-applies are idempotent. The settle delays are a
 * heuristic (no engine signal exists for "reflow done"); they're sized for the low-end API-25 devices
 * the feature targets. The first composition is skipped so opening a book triggers no re-apply storm.
 *
 * Extracted (rather than inlined) so the trigger schedule is unit-testable independent of the reader.
 */
@Composable
internal fun rememberReflowReapplyGeneration(reflowTrigger: Any?): Int {
    val generation = remember { mutableStateOf(0) }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(reflowTrigger) {
        if (!settled) {
            settled = true
            return@LaunchedEffect
        }
        for (settleMs in longArrayOf(150L, 350L, 700L)) {
            delay(settleMs)
            generation.value += 1
        }
    }
    return generation.value
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpubReaderScreen(
    onNavigateBack: () -> Unit,
    onSwitchToAudiobook: (audiobookItemId: String, atSec: Double) -> Unit = { _, _ -> },
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
    val readaloudVisible by viewModel.readaloudVisible.collectAsState()
    val readaloudOpen by viewModel.readaloudOpen.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()
    val audiobookItemId by viewModel.audiobookItemId.collectAsState()
    val activeFragmentRef by viewModel.activeFragmentRef.collectAsState()
    val sentenceQuotes by viewModel.sentenceQuotes.collectAsState()
    val readaloudHighlightColor by viewModel.readaloudHighlightColor.collectAsState()
    val sentenceChapters by viewModel.sentenceChapters.collectAsState()
    val downloadPromptBytes by viewModel.downloadPromptBytes.collectAsState()
    val readaloudOfflineMessage by viewModel.readaloudOfflineMessage.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()

    // Starting (or resuming) readaloud is a "lean back and listen" intent, so drop into
    // immersive mode. See ImmersiveOnReadaloudPlay for the why (hides system bars + TopAppBar
    // together, one-way, plays nicely with rotation/sleep-resume restore).
    ImmersiveOnReadaloudPlay(
        isReadaloudPlaying = playbackState.isPlaying,
        immersiveState = immersiveState,
    )

    // Reserve a fixed bottom strip for the readaloud mini-player whenever readaloud is AVAILABLE
    // (a downloaded/usable match) and the reader is paginated. The reserve is held for the whole
    // session — NOT gated on the player being open — so toggling the player (Play-from-here, the play
    // button, close) never re-paginates and the page can never jump. Non-readaloud books, and ABS
    // matches whose bundle isn't downloaded yet, reserve nothing and render edge to edge. Scroll mode
    // needs no reserve (the player floats over a freely-scrollable page). See ReadaloudReserve.kt.
    val readaloudReservePx: Int = readaloudReserveDp(
        readaloudAvailable = readaloudAvailable,
        paginated = formattingPrefs.orientation != ReaderOrientation.Vertical,
    )

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
                        returnNavEvents = viewModel.returnNavEvents,
                        onCaptureReturnTarget = viewModel::captureReturnTarget,
                        onFollowInternalLink = viewModel::followInternalLink,
                        activeFragmentRef = activeFragmentRef,
                        sentenceQuotes = sentenceQuotes,
                        sentenceChapters = sentenceChapters,
                        narrationProgress = viewModel.narrationProgress,
                        pageTopProbeRequests = viewModel.pageTopProbeRequests,
                        onPageTopResolved = viewModel::onPageTopResolved,
                        onPlayFromHere = viewModel::playFromHere,
                        readaloudAvailable = readaloudAvailable,
                        readaloudReservePx = readaloudReservePx,
                        readaloudHighlightColor = readaloudHighlightColor,
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
                            // Jumping to a location is a "start reading there" intent, so drop
                            // straight into immersive mode (system bars + TopAppBar hidden) the
                            // same way opening the book does.
                            onEntryClick = { entry ->
                                viewModel.navigateToEntry(entry)
                                immersiveState.hide()
                            },
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
            Column(
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                if (readaloudOpen) {
                    // Reference the reader-theme palette so the player matches the chapter-rail
                    // overlay backdrop (which paints readerTheme.palette.background) and the two
                    // read as one continuous, theme-following strip.
                    //
                    // In paginated mode the page reserves this bar's height at the bottom (see
                    // readaloudReservePx / ReadaloudReserve.kt), so the player sits over an empty strip
                    // rather than live text. The backdrop is kept slightly translucent so that in the
                    // cases without a reserve — scroll mode, or when the chapter rail also shows and
                    // lifts the bar above the reserved strip — the covered text and the narrated
                    // highlight still read through. Controls are Surface CONTENT, unaffected by the
                    // alpha, so they stay opaque.
                    val readerPalette = formattingPrefs.theme.palette
                    // Swiping the bar up switches to the single large player (the audiobook player),
                    // continuing from the current listen position. Only when this title has an
                    // audiobook to switch to; otherwise the swipe does nothing.
                    ReadaloudPeek(
                        handleColor = readerPalette.foreground,
                        enabled = audiobookItemId != null,
                        onSwipeUp = {
                            audiobookItemId?.let { abId ->
                                val sec = viewModel.prepareAudiobookHandoff()
                                onSwitchToAudiobook(abId, sec)
                            }
                        },
                    ) {
                        ReadaloudMiniPlayer(
                            isPlaying = playbackState.isPlaying,
                            speed = playbackState.speed,
                            offlineMessage = readaloudOfflineMessage,
                            downloadProgress = downloadProgress,
                            canPreviousChapter = playbackState.currentChapterIndex > 0,
                            canNextChapter = playbackState.currentChapterIndex >= 0 &&
                                playbackState.currentChapterIndex < playbackState.chapterCount - 1,
                            containerColor = readerPalette.background.copy(alpha = 0.85f),
                            contentColor = readerPalette.foreground,
                            onPlayPause = viewModel::togglePlayPause,
                            onSpeedChange = viewModel::setSpeed,
                            onRewind = viewModel::rewind,
                            onForward = viewModel::forward,
                            onPreviousChapter = viewModel::previousChapter,
                            onNextChapter = viewModel::nextChapter,
                            onClose = viewModel::closeReadaloud,
                        )
                    }
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
                            if (readaloudVisible) {
                                IconButton(
                                    onClick = viewModel::openReadaloud,
                                    enabled = readaloudAvailable,
                                    modifier = Modifier.testTag("readaloud_open"),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_readaloud),
                                        contentDescription = "Readaloud",
                                    )
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
        val returnTarget by viewModel.returnTarget.collectAsState()
        if (returnTarget != null) {
            ReturnToPositionCard(
                onReturn = viewModel::returnToCapturedPosition,
                onDismiss = viewModel::dismissReturnTarget,
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
    // Whole-book progress for the "% read" label — matches book details. Kept separate from
    // cursorPosition, which places the cursor inside the active (chapter-weighted) rail segment.
    val totalProgress by viewModel.currentLocatorTotalProgression.collectAsState()
    // totalProgression is absent until Readium computes positions, and stays absent for the whole
    // session if a publication's positions never compute. Fall back to the chapter-weighted rail
    // cursor then so the label shows a sensible estimate instead of sticking at 0%. Once a real
    // whole-book value arrives it wins (and matches book details). At genuine 0% the rail cursor is
    // also ~0, so the fallback is indistinguishable there.
    val labelProgress = totalProgress?.takeIf { it > 0f } ?: cursorPosition
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
                    totalProgress = labelProgress,
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
                    readerTheme = readerTheme,
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

// How long to keep the navigation cover up after go(), to hide the new chapter's opener/white-blank
// flash while it loads and paints. Correctness of the landing no longer depends on this timing:
// [snapToTargetColumnJs] keeps the target pinned to its column through the async typography reflow
// (which can finish well after this), so the cover is purely cosmetic.
private const val NAV_COVER_SETTLE_MS = 250L

/**
 * Builds a Readium [Locator] from a readaloud fragment ref — "href#fragId", or a bare "href" when
 * the ref carries no fragment. The cssSelector targets the fragment id so Readium's EPUB decorator
 * and navigator resolve the same DOM range. Shared by the synced-highlight decoration and the
 * auto-follow so both always point at the same element.
 *
 * When a [quote] is supplied, the locator also carries the sentence's text (with neighbouring
 * context as prefix/suffix). Readium's decoration positioner uses the cssSelector when it resolves,
 * and otherwise falls back to a TextQuoteAnchor search over the document body — so the highlight
 * still lands when Readium has stripped the sentence span from the rendered (ABS) EPUB.
 */
private fun fragmentLocator(ref: String, quote: SentenceQuote? = null): Locator? =
    Locator.fromJSON(readaloudLocatorJson(ref, quote))

/**
 * The narrated sentences (span id → text) to feed [resolveSelectionSentenceJs] for a "Play from here"
 * tap, scoped to the chapter being read ([currentHref]). The resolver locates a tapped sentence by
 * searching the rendered page for each sentence's short text prefix; handed the WHOLE book it can match
 * a FOREIGN chapter's sentence whose text recurs inside a current-chapter sentence (The Martian ch16's
 * "…I'd… He thought for a moment." contains ch8's standalone "He thought for a moment.") and seek that
 * chapter instead. Scoping to [currentHref] removes the cross-chapter matches. Falls back to the whole
 * book when no sentence maps to this chapter (e.g. the rendered href can't be reconciled with the
 * bundle), preserving behaviour rather than yielding an empty resolver.
 */
internal fun scopeSentencesToChapter(
    quotes: Map<String, SentenceQuote>,
    chapters: Map<String, String>,
    currentHref: String,
): List<Pair<String, String>> {
    fun base(h: String) = h.substringBefore('#').substringAfterLast('/')
    val cur = base(currentHref)
    val scoped = quotes.entries.filter { base(chapters[it.key] ?: "") == cur && cur.isNotEmpty() }
    val use = if (scoped.isNotEmpty()) scoped else quotes.entries.toList()
    return use.map { it.key to it.value.highlight }
}

/**
 * The Readium Locator JSON for a readaloud fragment ref. Extracted (and `internal`) so the
 * text-anchoring contract is unit-testable without a navigator: when a [quote] is present the JSON
 * MUST carry a `text` block (highlight + before/after), because that's what lets Readium position
 * the highlight by text search after it strips the sentence span from the served HTML. The
 * cssSelector is kept as the fast path for when the span does survive.
 */
internal fun readaloudLocatorJson(ref: String, quote: SentenceQuote?): JSONObject {
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
    if (quote != null) {
        json.put(
            "text",
            JSONObject()
                .put("before", quote.before)
                .put("highlight", quote.highlight)
                .put("after", quote.after),
        )
    }
    return json
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
    onFootnoteTapped: (content: FootnoteContent) -> Unit,
    returnNavEvents: Flow<Locator>,
    onCaptureReturnTarget: (Locator) -> Unit,
    onFollowInternalLink: (Link, Locator) -> Unit,
    activeFragmentRef: String?,
    sentenceQuotes: Map<String, SentenceQuote>,
    sentenceChapters: Map<String, String>,
    narrationProgress: Flow<PlayerCoordinator.NarrationProgress?>,
    pageTopProbeRequests: Flow<String>,
    onPageTopResolved: (href: String, fragmentId: String?) -> Unit,
    onPlayFromHere: (fragmentRef: String) -> Unit,
    readaloudAvailable: Boolean,
    readaloudReservePx: Int = 0,
    readaloudHighlightColor: ReadaloudHighlightColor,
    annotationsAvailable: Boolean,
    highlightRenders: List<EpubReaderViewModel.HighlightRender>,
    onHighlight: (Locator) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val fragmentActivity = context as? FragmentActivity ?: return
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isFixedLayout = state.publication.metadata.layout == Layout.FIXED
    val coroutineScope = rememberCoroutineScope()
    val fragmentRef = remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    val containerRef = remember { mutableStateOf<ScrollBoundaryNavigationContainer?>(null) }
    // Covers the reader with a plain page-coloured screen while a cross-resource jump (TOC/search)
    // loads the new chapter. Readium briefly paints the new resource's opener (a figure/graphic) or
    // a white blank before scrolling to the target, and the column-snap nudges the page a beat after;
    // masking the transition hides all of that so the jump looks instantaneous.
    var navigating by remember { mutableStateOf(false) }
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
    val currentLatestLocator by rememberUpdatedState(latestLocator)
    val currentOnCaptureReturnTarget by rememberUpdatedState(onCaptureReturnTarget)
    val currentOnFollowInternalLink by rememberUpdatedState(onFollowInternalLink)
    val currentOnPlayFromHere by rememberUpdatedState(onPlayFromHere)
    val currentSentenceQuotes by rememberUpdatedState(sentenceQuotes)
    val currentSentenceChapters by rememberUpdatedState(sentenceChapters)
    val currentOnHighlight by rememberUpdatedState(onHighlight)
    val currentAnnotationsAvailable by rememberUpdatedState(annotationsAvailable)
    val currentReadaloudAvailable by rememberUpdatedState(readaloudAvailable)
    // Latest readaloud bottom reserve, read inside the (remembered-once) pagination listener so each
    // freshly loaded page re-applies the current value.
    val currentReadaloudReservePx by rememberUpdatedState(readaloudReservePx)
    val currentPublication by rememberUpdatedState(state.publication)

    // The text-selection action bar is fully owned by this callback (Readium 3.0.0's
    // selectionActionModeCallback is the only supported hook, and it replaces the WebView's default
    // menu). So besides our "Play from here" item we must re-add the standard Copy / Search / Share
    // actions the user expects, driven off the current selection's text.
    //
    // "Play from here": on click we read currentSelection() and derive a SMIL fragment ref. A
    // free-text selection rarely lands exactly on a SMIL <par> boundary, so we pass the selection's
    // first fragment id (locations.fragments) when present, else the bare href; the player resolves
    // the nearest narrated clip at/after that position (never restarting the book). Gated on
    // readaloudAvailable so it only shows where the toolbar readaloud control is enabled — i.e. a
    // Storyteller book or a matched-ABS book with a downloaded bundle, never a plain EPUB.
    val playFromHereMenuId = remember { View.generateViewId() }
    // "Highlight" creates a persisted annotation, but there's no UI yet to view/delete/recolor
    // highlights (ADR 0024 shipped create + render only). Keep the plumbing wired but hidden behind
    // ANNOTATIONS_UI_ENABLED until that management UI lands, so the affordance isn't a dead end.
    val highlightMenuId = remember { View.generateViewId() }
    val copyMenuId = remember { View.generateViewId() }
    val searchMenuId = remember { View.generateViewId() }
    val shareMenuId = remember { View.generateViewId() }
    val playFromHereActionMode = remember {
        object : android.view.ActionMode.Callback {
            override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
                menu.add(0, copyMenuId, 0, android.R.string.copy)
                if (ANNOTATIONS_UI_ENABLED && currentAnnotationsAvailable) menu.add(0, highlightMenuId, 1, "Highlight")
                if (currentReadaloudAvailable) menu.add(0, playFromHereMenuId, 2, "Play from here")
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
                        val nav = fragmentRef.value
                        coroutineScope.launch {
                            val selection = selectable.currentSelection() ?: return@launch
                            val loc = selection.locator
                            // We need the narrated-sentence span id (<span id="cNNN-sM">) the SMIL clips key
                            // on; Readium's selection locator carries only text + rect (no fragment id), so
                            // without that id the player can't map the selection to a clip and restarts the
                            // chapter from its first clip.
                            //
                            // Preferred path — resolveSelectionSentenceJs: resolve the span id by POSITION
                            // from the captured locator's text context. This survives Readium stripping the
                            // sentence spans from the served HTML (the common case — the ABS EPUB; see
                            // ReadaloudTextQuotes) because it locates the selection within the rendered prose
                            // by text offset rather than reading a DOM id that isn't there. Needs the
                            // sentence-text map, which is built off-thread after the track loads.
                            //
                            // Fallback — window.__riffleSelSpan: the selectionchange tracker
                            // (SELECTION_SPAN_TRACKER_JS) stashes the enclosing span id, which only exists on
                            // pages whose spans survived (pure-Storyteller rendering) or before the quotes
                            // map is ready. Empty → falls back to the locator fragment, then the bare href
                            // (chapter start).
                            val sentences = scopeSentencesToChapter(
                                currentSentenceQuotes, currentSentenceChapters, loc.href.toString(),
                            )
                            val byText = if (sentences.isNotEmpty() && nav != null) {
                                nav.evaluateJavascript(resolveSelectionSentenceJs(sentences))
                                    ?.trim('"')?.takeIf { it.isNotEmpty() }
                            } else {
                                null
                            }
                            val spanId = byText
                                ?: nav?.evaluateJavascript("window.__riffleSelSpan || ''")
                                    ?.trim('"')?.takeIf { it.isNotEmpty() }
                            val fragId = spanId ?: loc.locations.fragments.firstOrNull()
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
                if (context is HyperlinkNavigator.FootnoteContext) {
                    val content = FootnoteResolver.footnoteContent(context.noteContent) ?: return true
                    currentOnFootnoteTapped(content)
                    return false
                }
                // A non-footnote internal link (a cross-resource cross-reference — same-document anchors
                // are intercepted earlier by the JS bridge). Drive the navigation ourselves so we can
                // remember the origin and offer a return; defer to Readium only if we don't yet know
                // where we are. It always lands in another resource, so it's never a same-page jump.
                val origin = currentLatestLocator() ?: return true
                currentOnFollowInternalLink(link, origin)
                return false
            }

            override fun onExternalLinkActivated(url: AbsoluteUrl) = Unit
        }
    }

    // Bumps whenever a formatting change (font/margin/spacing/orientation) reflows the layout so the
    // decoration effects and the auto-follow probe below re-apply / re-centre onto the new pagination
    // (see rememberReflowReapplyGeneration). Also keyed on the readaloud reserve: it's constant across
    // the session in the common case (so opening the player is NOT a reflow trigger), but it flips once
    // if an ABS bundle finishes downloading mid-session — that one re-paginates, so re-apply then too.
    val reflowGeneration = rememberReflowReapplyGeneration(formattingPrefs to readaloudReservePx)

    // Bumps every time a page finishes loading. This is the precise "the layout is now settled" signal
    // that reflowGeneration's timer heuristic only approximates — and the only one available after a
    // device rotation, which recreates the Activity (no android:configChanges) and therefore the whole
    // Compose tree: reflowGeneration resets to 0 and its first-composition skip means it never bumps,
    // and the freshly-created fragment applies its initial decorations *before* onPageLoaded fires
    // (proven on device), positioning them against an unsettled layout. The decoration effects key on
    // this so they re-apply once the page has actually loaded. Page turns during normal reading also
    // bump it; the re-applies are idempotent (same decoration id + locator), so that's harmless.
    val pageLoadGeneration = remember { mutableStateOf(0) }

    // Injects, into each newly loaded reflowable page: the ClientRect.toJSON polyfill (see
    // RECT_TO_JSON_POLYFILL_JS), the selection-span tracker (SELECTION_SPAN_TRACKER_JS), the targeted
    // typography overrides (see TypographyOverride.kt), and the footnote-anchor install script. All are
    // idempotent (typography deduplicates by <style> tag, footnote by window.__riffleFootnoteInstalled,
    // tracker by window.__riffleSelTrackerInstalled, the polyfill by a typeof guard), so repeated
    // firings during reflow are harmless.
    val paginationListener = remember {
        object : EpubNavigatorFragment.PaginationListener {
            override fun onPageLoaded() {
                pageLoadGeneration.value += 1
                val fragment = fragmentRef.value ?: return
                coroutineScope.launch {
                    // A backward cross-resource page turn (swipe-back at a chapter start) is handled by
                    // Readium itself in paginated mode — our nav handlers never see it — and Readium
                    // positions the previous resource at its END. Capture that BEFORE the typography
                    // injection below reflows (widens) the page; the end re-snap then tracks the reflow so
                    // the page can't be stranded several columns short of the true end (the "previous
                    // chapter overshoots 4-5 pages back" bug). A forward turn lands at column 0 and a TOC
                    // jump at the chapter top, so neither reports landedAtEnd.
                    //
                    // onPageLoaded fires for offscreen-preloaded resources too, and evaluateJavascript
                    // binds to whatever resource is current at call time — so capture the resource we
                    // measure here and only re-snap below if it is still current, otherwise a swipe during
                    // the awaited injections could end-snap a DIFFERENT chapter (a forward overshoot).
                    val hrefAtLoad = currentHrefHolder[0]
                    val landedAtEnd = ColumnSnap.landedAtEnd(fragment)
                    fragment.evaluateJavascript(RECT_TO_JSON_POLYFILL_JS)
                    fragment.evaluateJavascript(SELECTION_SPAN_TRACKER_JS)
                    fragment.evaluateJavascript(typographyOverrideInjectionJs())
                    fragment.evaluateJavascript(FootnoteAnchorBridge.INSTALL_SCRIPT)
                    // Reserve the bottom strip for the readaloud player on this freshly loaded page
                    // (paginated + readaloud-available only; see ReadaloudReserve.kt). Inject the rule,
                    // then apply the current value so the page lands already paginated above the strip —
                    // no reflow when the player is later opened.
                    fragment.evaluateJavascript(readaloudReserveInjectionJs())
                    fragment.evaluateJavascript(readaloudReserveApplyJs(currentReadaloudReservePx))
                    // Install the at-rest column-snap backstop: once scrolling settles off-grid in
                    // paginated mode it rounds to the nearest column, so the page can never REST between
                    // two pages no matter what moved it. Idempotent; defers to the rAF trackers.
                    ColumnSnap.installBackstop(fragment)
                    // NOTE: do NOT snap to the column grid here for the general case. The typography
                    // injection above reflows the page asynchronously, so a snap at this point rounds a
                    // pre-reflow position and lands close-but-not-exact. The post-go() snap in the
                    // navigation handlers runs after the reflow has settled and is the authoritative one.
                    //
                    // EXCEPTION: a backward cross-resource turn has no nav handler in paginated mode (it's
                    // Readium's own ViewPager), so there is no post-go snap to keep the end pinned through
                    // the reflow. Re-pin to the last column here; snapToEnd's rAF loop tracks the reflow.
                    if (landedAtEnd && currentHrefHolder[0] == hrefAtLoad) {
                        ColumnSnap.snapToEnd(fragment)
                    }
                }
            }
        }
    }

    // Push the reserve to the live page if it changes without a page reload — readaloud becoming
    // available mid-session (an ABS bundle finishes downloading) or the reader switching to/from scroll
    // mode. In the common case the value is set at the first page load and never changes here, so this
    // is a no-op. Unlike the earlier open-gated reserve, there is no top-of-page anchor to capture: the
    // reserve isn't tied to the player opening, so this transition isn't one the user is staring at.
    LaunchedEffect(readaloudReservePx) {
        val fragment = fragmentRef.value ?: return@LaunchedEffect
        withContext(Dispatchers.Main) {
            fragment.evaluateJavascript(readaloudReserveInjectionJs())
            fragment.evaluateJavascript(readaloudReserveApplyJs(readaloudReservePx))
        }
    }

    // Tap on an in-document anchor inside the WebView → look up the target in
    // the cached spine doc. A footnote shows the popup; a regular cross-
    // reference ("Figure 4.1") scrolls to the target's column boundary so the
    // figure lands snapped on the page grid instead of the WebView's default
    // same-document scroll leaving the page split mid-column. In both cases we
    // preventDefault (return true). Only an unresolved target (cache cold / id
    // absent) falls through to the default scroll.
    //
    // We scroll via JS rather than go(): go(cssSelector) lands flush to the
    // element's box (a little inside its column → next-column sliver), go-by-
    // progression is imprecise, and goForward/goBackward report success without
    // moving on Readium 3.3.0. A direct scrollLeft snap holds because the reader
    // is sized so innerWidth == Readium's page-snap pitch (see readerWidthDp).
    DisposableEffect(Unit) {
        FootnoteAnchorBridge.setHandler { fragmentId ->
            when (
                val target = FootnoteResolver.classifyAnchorTap(
                    currentHrefHolder[0], footnoteDocCache, fragmentId,
                )
            ) {
                is FootnoteResolver.AnchorTarget.Footnote -> {
                    // Called from the JS binder thread; hop to main for Compose state.
                    coroutineScope.launch(Dispatchers.Main) {
                        currentOnFootnoteTapped(target.content)
                    }
                    true
                }
                FootnoteResolver.AnchorTarget.CrossReference -> {
                    // Capture where we are BEFORE the snap; offer a way back only if the target was
                    // actually off-page (the snap reports whether it changed columns).
                    val origin = currentLatestLocator()
                    coroutineScope.launch(Dispatchers.Main) {
                        val moved = fragmentRef.value
                            ?.let { ColumnSnap.snapToElementColumn(it, fragmentId) } ?: false
                        if (moved && origin != null) currentOnCaptureReturnTarget(origin)
                    }
                    true
                }
                FootnoteResolver.AnchorTarget.Unresolved -> false
            }
        }
        onDispose { FootnoteAnchorBridge.setHandler(null) }
    }

    LaunchedEffect(onNavigationEvents) {
        onNavigationEvents.collect { link ->
            val fragment = fragmentRef.value ?: return@collect
            // Cover only a cross-resource jump (where the load flash happens); a same-chapter jump
            // is instant and needs no mask.
            val cover = link.href.toString().substringBefore('#') !=
                currentHrefHolder[0]?.substringBefore('#')
            navigating = cover
            try {
                // Navigate and snap onto the target's column, tracked through the new chapter's async
                // typography reflow (ColumnSnap owns the grid math). The cover is just cosmetic now.
                ColumnSnap.goAndSnap(fragment, link)
                if (cover) delay(NAV_COVER_SETTLE_MS)
            } finally {
                navigating = false
            }
        }
    }

    LaunchedEffect(serverLocatorEvents) {
        serverLocatorEvents.collect { locator ->
            // Background position sync (peer/resume): navigate and snap onto the target's column, tracked
            // through the new chapter's reflow, but never cover — a cover here would flash mid-reading.
            val fragment = fragmentRef.value ?: return@collect
            // A background sync (audiobook/peer) carries a within-chapter progression but no DOM
            // fragment; preserve where go() landed (round to the column grid) instead of snapping to
            // the chapter top, so the reader lands on the actual synced page.
            ColumnSnap.goAndSnap(fragment, locator, landAtStartWhenNoTarget = false)
        }
    }

    // Navigate to a within-chapter [locator] and snap the page to the grid where go() landed
    // (landAtStartWhenNoTarget=false — don't yank to the chapter top), covering only a cross-resource
    // trip with the load mask. Shared by the search-hit and return-card routes: both carry an
    // occurrence/position-specific progression with no #fragment, so the snap must round THAT page to
    // the grid rather than column 0 (the default), which would lose the hit / the saved position.
    val goAndSnapWithCover: suspend (Locator) -> Unit = goAndSnapWithCover@{ locator ->
        val fragment = fragmentRef.value ?: return@goAndSnapWithCover
        val cover = locator.href.toString().substringBefore('#') !=
            currentHrefHolder[0]?.substringBefore('#')
        navigating = cover
        try {
            ColumnSnap.goAndSnap(fragment, locator, landAtStartWhenNoTarget = false)
            if (cover) delay(NAV_COVER_SETTLE_MS)
        } finally {
            navigating = false
        }
    }

    LaunchedEffect(returnNavEvents) {
        returnNavEvents.collect { goAndSnapWithCover(it) }
    }

    LaunchedEffect(searchNavigationEvents) {
        searchNavigationEvents.collect { goAndSnapWithCover(it) }
    }

    // Resolve "top of the current page" when readaloud reopens on a different page: ask the WebView
    // for the first narrated sentence visible on the page (spans are stripped, so we search by the
    // sentence's text — quotes are in reading order and only this chapter's sentences are in the DOM).
    LaunchedEffect(pageTopProbeRequests) {
        pageTopProbeRequests.collect { href ->
            val fragment = fragmentRef.value
            val ordered = currentSentenceQuotes.entries.toList()
            val fragmentId = if (fragment != null && ordered.isNotEmpty()) {
                val idx = fragment.evaluateJavascript(
                    firstVisibleSentenceJs(ordered.map { it.value.highlight }),
                )?.trim('"')?.toIntOrNull()
                idx?.let { ordered.getOrNull(it)?.key }
            } else {
                null
            }
            onPageTopResolved(href, fragmentId)
        }
    }

    // Tracks whether we have live search decorations painted in the WebView.
    // Prevents calling applyDecorations on initial composition (before WebView content loads),
    // which crashes the WebView renderer via a premature JS evaluation. See ADR 0015.
    val hasActiveDecorations = remember { mutableStateOf(false) }

    // Readium fixes a decoration's rects at applyDecorations time and never re-positions them. When a
    // search result is navigated to, the landing page's layout is still settling as the decoration is
    // applied (the page snaps to the column grid and the search top bar's inset resolves AFTER the first
    // apply), so the box is placed against the pre-settle layout and the text then shifts out from under
    // it — verified on device as a constant ~152px vertical offset onto the wrong word (same column, same
    // width: a placement-timing bug, not a fuzzy mis-resolution). readaloud never shows this because it
    // re-applies its decoration on every audio tick; search applies once per navigation. We therefore
    // re-apply across a short settle window after each (re)apply so the boxes track the final layout. The
    // re-applies are idempotent (same id + locator). Re-keys on reflow/pageLoad as well for rotation and
    // formatting reflows, matching the readaloud and annotations decoration effects.
    LaunchedEffect(searchResults, currentSearchIndex, reflowGeneration, pageLoadGeneration.value) {
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
        val navFragment = fragmentRef.value ?: return@LaunchedEffect
        val fragment = navFragment as? DecorableNavigator ?: return@LaunchedEffect
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
        // Apply immediately for the first paint; the re-resolve loop below then tracks the page as it
        // settles (see its comment for the why). "The first result is highlighted, the next ones aren't"
        // was this exact bug on navigated results — the landing page is still moving when the box lands.
        withContext(Dispatchers.Main) {
            fragment.applyDecorations(decorations, group = "search")
        }
        hasActiveDecorations.value = true
        // Re-resolve the box positions across the post-navigation settle window. Two things matter here:
        //  1) Readium re-positions a decoration only when its set CHANGES — re-applying the identical list
        //     is a no-op diff, so the box keeps its first (pre-settle) position. We therefore CLEAR then
        //     re-apply (back to back on the main thread, no visible gap — the same trick the readaloud
        //     group uses), which forces Readium to re-run its locator→range resolver against the current,
        //     settled DOM.
        //  2) The layout keeps moving after the first apply (go() + the column-snap rAF loop ~1.2s + the
        //     async typography reflow), so we repeat the clear+re-apply on a SPACED schedule out to ~2.6s.
        //     Spaced (not a tight poll): a 100ms loop floods the still-navigating WebView and can blank the
        //     page (ADR 0015); a handful of spaced re-applies is as safe as the first one.
        for (settleDelayMs in longArrayOf(400L, 600L, 700L, 900L)) {
            delay(settleDelayMs)
            if (fragmentRef.value !== navFragment) break // navigated away / fragment recreated — newer effect owns it
            withContext(Dispatchers.Main) {
                fragment.applyDecorations(emptyList(), group = "search")
                fragment.applyDecorations(decorations, group = "search")
            }
        }
    }

    // ---- Readaloud synced highlight --------------------------------------------------------
    // Uses the same Readium DecorableNavigator mechanism as search, on its own "readaloud" group
    // so it doesn't clobber search highlights. The active fragment ref is "href#fragId"; we build a
    // Locator that carries both the fragment-id cssSelector AND the sentence's text. Readium uses the
    // cssSelector when it resolves and otherwise falls back to a text search — necessary because it
    // strips the sentence spans from the rendered ABS EPUB. Null clears the decoration. The effect
    // also re-keys on [sentenceQuotes] so the highlight re-applies with text once the quotes (built
    // off the main thread after the track loads) become available.
    val hasReadaloudDecoration = remember { mutableStateOf(false) }
    LaunchedEffect(activeFragmentRef, reflowGeneration, pageLoadGeneration.value, sentenceQuotes, readaloudHighlightColor) {
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
        val sid = ref.substringAfter('#', "")
        val quote = sentenceQuotes[sid]
        val locator = fragmentLocator(ref, quote) ?: return@LaunchedEffect
        val decoration = Decoration(
            id = "readaloud_active",
            locator = locator,
            style = Decoration.Style.Highlight(
                tint = readaloudHighlightColor.argb,
                isActive = false,
            ),
        )
        // Clear the group before re-applying. After the fragment is recreated (rotation) or its page
        // reloads, Readium's decoration controller for the new WebView loses track of the previously
        // injected element, so applyDecorations([new]) ADDS without removing the old one and the
        // highlights accumulate — the previous sentence stays lit while the current one plays. An
        // empty apply runs the JS group's clear() (it removes the whole decoration container element),
        // so the subsequent apply always leaves exactly one highlight. The two calls execute back to
        // back on the main thread, so there is no visible gap on a normal sentence advance.
        withContext(Dispatchers.Main) {
            fragment.applyDecorations(emptyList(), group = "readaloud")
            fragment.applyDecorations(listOf(decoration), group = "readaloud")
        }
        hasReadaloudDecoration.value = true
    }

    // ---- Persisted highlights (ADR 0024) ---------------------------------------------------
    // Renders all of a book's stored highlights via the same DecorableNavigator mechanism, on its
    // own "annotations" group. Re-applied whenever the set changes — including the re-render of
    // every highlight when the book is reopened, and the immediate paint after a new highlight.
    val hasHighlightDecorations = remember { mutableStateOf(false) }
    LaunchedEffect(highlightRenders, reflowGeneration, pageLoadGeneration.value) {
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
    //  - Paginated (Horizontal) mode — each page is exactly viewport-sized, KEEP-VISIBLE follow: while
    //    the narrated sentence's start is on the current page the probe leaves the page in place, and
    //    only flips (snaps scrollLeft onto the column grid) once the sentence's start moves off the
    //    current page. This is what stops starting playback — and the player-open reflow that re-runs
    //    this probe — from yanking the line the user pressed onto a fresh column boundary. The snap
    //    holds because the reader is sized so innerWidth == Readium's page-snap pitch
    //    ([alignedReaderWidthDp]), so floor(x / innerWidth) * innerWidth is exactly a column boundary.
    //
    // A missing element (sentence in another chapter's document) reads as "off" → go(locator) jumps
    // chapters, so cross-chapter follow falls out for free in both modes.
    //
    // Re-keys on reflowGeneration (formatting reflows) and pageLoadGeneration (rotation / chapter load)
    // so the narrated sentence is re-centred after those relayouts. The player floats over the page and
    // no longer reflows it, so opening it doesn't move the narrated sentence's column.
    LaunchedEffect(activeFragmentRef, sentenceQuotes, reflowGeneration, pageLoadGeneration.value) {
        val ref = activeFragmentRef ?: return@LaunchedEffect
        val fragment = fragmentRef.value ?: return@LaunchedEffect
        if (ref.indexOf('#') < 0) return@LaunchedEffect
        // No quote yet (the map is built off-thread once playback starts) → we can neither locate the
        // sentence by text nor anchor a go(): the cssSelector-only locator can't resolve on the
        // span-stripped ABS page, so a snap would flip to chapter start. Skip until the quote arrives;
        // this effect re-keys on [sentenceQuotes] and re-runs to follow correctly once it's available.
        val quote = sentenceQuotes[ref.substringAfter('#', "")] ?: return@LaunchedEffect
        // Locate the sentence by its text (spans are stripped). The probe snaps to the sentence's
        // column itself in paginated mode; "off" comes back only when the text isn't on this resource
        // (another chapter), where we fall back to a text-anchored go() to load it.
        val where = ColumnSnap.followNarratedSentence(fragment, quote.highlight)
        if (where != "off") return@LaunchedEffect
        fragmentLocator(ref, quote)?.let { fragment.go(it, animated = false) }
    }

    // INTRA-sentence page follow (paginated): the effect above snaps to the column holding the
    // sentence's START on each sentence change, but a sentence whose text wraps across the column
    // boundary leaves its tail on the next page while the voice keeps reading it. Read-aloud timing is
    // per-sentence, so we estimate the crossing from the elapsed fraction of the sentence's clip:
    // [NarratedColumnProgression] maps that fraction onto the sentence's measured column layout and
    // tells us when to turn — only ON A CHANGE, so position polls between break points (and a manual
    // page-turn) are left alone, and a backward seek turns back.
    //
    // The sentence is re-measured whenever it changes OR a relayout moves the columns (reflow /
    // rotation / chapter load), so re-keying on those generations also re-asserts the right column
    // after the start-snap above has yanked back to column 0.
    val columnProgression = remember { NarratedColumnProgression() }
    LaunchedEffect(sentenceQuotes, reflowGeneration, pageLoadGeneration.value) {
        var measuredRef: String? = null
        narrationProgress.collect { progress ->
            val fragment = fragmentRef.value ?: return@collect
            if (progress == null) { columnProgression.reset(); measuredRef = null; return@collect }
            val ref = progress.fragmentRef
            if (ref.indexOf('#') < 0) return@collect
            val quote = sentenceQuotes[ref.substringAfter('#', "")] ?: return@collect
            if (ref != measuredRef) {
                // New sentence (or a relayout restarted this effect): measure how it spans columns.
                columnProgression.onSentence(ColumnSnap.measureNarratedColumns(fragment, quote.highlight))
                measuredRef = ref
            }
            columnProgression.advance(progress.fraction)?.let { column ->
                ColumnSnap.snapNarratedColumn(fragment, quote.highlight, column)
            }
        }
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

                    // No pill at the book's ends: a pull-down on the very first chapter or a
                    // pull-up on the very last has nowhere to go, so don't arm the gesture.
                    val idx = state.publication.readingOrder
                        .indexOfFirst { it.href.toString() == currentHrefHolder[0] }
                    container.canNavigateForward =
                        idx in 0 until state.publication.readingOrder.size - 1
                    container.canNavigateBackward = idx > 0
                }
            }
            delay(BOUNDARY_POLL_INTERVAL_MS)
        }
    }

    // Single-page paginated reflowable is the only mode with horizontal column snapping, so it's
    // the only one exposed to the column-grid drift bug — and the only one we narrow (and paint a
    // page-coloured gutter behind). Scroll / fixed-layout / double-page keep fillMaxSize and the
    // bare modifier, untouched. See reference_reader_right_margin_is_column_snap_bug.
    val density = LocalDensity.current.density
    val isPaginated = !isFixedLayout && formattingPrefs.orientation != ReaderOrientation.Vertical
    val isDoublePage = isPaginated && formattingPrefs.doublePageSpread && isLandscape
    val alignViewport = isPaginated && !isDoublePage
    val containerModifier = if (alignViewport) {
        modifier.background(formattingPrefs.theme.palette.background)
    } else {
        modifier
    }
    BoxWithConstraints(modifier = containerModifier) {
        val alignedWidth = remember(maxWidth, density) { alignedReaderWidthDp(maxWidth.value, density) }
        val readerModifier = if (alignViewport) {
            Modifier
                .width(alignedWidth.dp)
                .fillMaxHeight()
                .align(Alignment.Center)
        } else {
            Modifier.fillMaxSize()
        }
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

                val isScrollMode = formattingPrefs.orientation == ReaderOrientation.Vertical
                val density = container.resources.displayMetrics.density
                val (topPx, bottomPx) = readerContainerPaddingPx(
                    margins = formattingPrefs.margins,
                    density = density,
                    isFixedLayout = isFixedLayout,
                    isScrollMode = isScrollMode,
                )
                if (fragmentContainer.paddingTop != topPx || fragmentContainer.paddingBottom != bottomPx) {
                    fragmentContainer.setPadding(0, topPx, 0, bottomPx)
                }

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
            modifier = readerModifier,
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
        // Masks the load/snap transition during a cross-resource jump. Drawn last so it sits above
        // the reader; the page colour makes it read as a brief blank rather than a flash of the
        // wrong page.
        if (navigating) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(formattingPrefs.theme.palette.background)
                    .testTag("reader_nav_cover")
                    .semantics { contentDescription = "Loading" },
            )
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
