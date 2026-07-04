package com.riffle.app.feature.reader

import android.content.res.Configuration
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.graphicsLayer
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
import com.riffle.app.feature.reader.formatting.RenderCapabilities
import com.riffle.app.feature.reader.presenter.ContinuousPresenter
import com.riffle.app.feature.reader.presenter.NavigationOptions
import com.riffle.app.feature.reader.presenter.NavigationTarget
import com.riffle.app.feature.reader.presenter.PageDirection
import com.riffle.app.feature.reader.presenter.ReaderPresenter
import com.riffle.app.feature.reader.presenter.ReadiumPresenter
import com.riffle.app.feature.reader.sentence.SentencePlaybackController
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntRect
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
import com.riffle.app.ui.theme.RiffleIcons
import com.riffle.app.ui.theme.RiffleTheme
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.HighlightColor
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.SentenceQuote
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.TimeRemaining
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.HyperlinkNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.util.AbsoluteUrl
import androidx.activity.compose.BackHandler
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.riffle.app.feature.audiobook.AudiobookPlayerScreen
import java.net.URLEncoder


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
    windowSizeClass: WindowSizeClass,
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
    // False until the loaded prefs have propagated to effectiveFormattingPreferences. Gates
    // navigator construction so first paint never uses the StateFlow's default.
    val formattingPreferencesReady by viewModel.formattingPreferencesReady.collectAsState()
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

    // Push the reader viewport width to the VM so Auto-Scroll can compute words-per-line from
    // the actual on-screen column. Re-fires on rotation / configuration changes.
    val configuration = LocalConfiguration.current
    val displayDensity = context.resources.displayMetrics.density
    LaunchedEffect(configuration.screenWidthDp, displayDensity) {
        viewModel.setReaderViewportWidthPx((configuration.screenWidthDp * displayDensity).toInt())
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

    val skipIntervalSec by viewModel.skipIntervalSec.collectAsState()
    val rewindIntervalSec by viewModel.rewindIntervalSec.collectAsState()
    val isSearchActive by viewModel.isSearchActive.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val currentSearchIndex by viewModel.currentSearchIndex.collectAsState()
    val title = (state as? ReaderState.Ready)?.title ?: ""
    val tocVisible by viewModel.tocVisible.collectAsState()
    val footnotePopup by viewModel.footnotePopup.collectAsState()

    val annotationsAvailable by viewModel.annotationsAvailable.collectAsState()
    val annotationsPanelVisible by viewModel.annotationsPanelVisible.collectAsState()
    val annotations by viewModel.annotations.collectAsState()
    val isCurrentPageBookmarked by viewModel.isCurrentPageBookmarked.collectAsState()
    val highlightRenders by viewModel.highlightRenders.collectAsState()
    val highlightToEdit by viewModel.highlightToEdit.collectAsState()
    val railSegments by viewModel.railSegments.collectAsState()
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
    val readaloudBarMessage by viewModel.readaloudBarMessage.collectAsState()
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
        paginated = formattingPrefs.orientation == ReaderOrientation.Horizontal,
    )

    // Track the rendered height of the chapter rail overlay so its pixels can be added to the CSS
    // reserve, preventing Readium from paginating text behind the overlay in paged mode.
    var railOverlayHeightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val paginated = formattingPrefs.orientation != ReaderOrientation.Vertical
    val railReserveCssPx: Int = if (paginated) {
        (railOverlayHeightPx / density.density).roundToInt()
    } else 0
    val totalReserveCssPx: Int = readaloudReservePx + railReserveCssPx

    // TopAppBar floats as an overlay so its show/hide never resizes the content area —
    // eliminates the compound flicker that Scaffold's topBar slot caused by reflowing the
    // WebView simultaneously with the system-bar animation.
    //
    // The WebView is intentionally NOT padded by navigationBarsPadding: with transparent
    // system bars (see MainActivity.enableEdgeToEdge + themes.xml), the page extends edge
    // to edge and the nav bar floats over the last sliver of content. Padding would carve
    // out a solid strip behind the bar that doesn't blend with any reader theme — exactly
    // the "white/black bar" the user reported when exiting Immersive mode.

    // `showAudiobookOverlay` drives visibility; the overlay composable itself is always mounted
    // when `audiobookItemId != null` so AudiobookPlayerViewModel pre-warms in the background.
    var showAudiobookOverlay by rememberSaveable { mutableStateOf(false) }

    // Dismiss the audiobook overlay when the system back button is pressed, rather than
    // navigating back through the outer NavHost (which would exit the reader entirely).
    BackHandler(enabled = showAudiobookOverlay) {
        showAudiobookOverlay = false
        viewModel.onAudiobookOverlayDismissed()
    }

    // Auto-Scroll HUD pill lives at the outer Box so it overlays the chapter rail and
    // ContinuousReaderView, and survives Immersive Mode (which only hides the TopAppBar).
    val autoScrollStateForPill by viewModel.autoScrollState.collectAsState()

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
                // Prefs haven't propagated to effectiveFormattingPreferences yet — keep
                // the spinner up rather than constructing the Readium navigator with the
                // StateFlow's FormattingPreferences() default. Without this gate, fast/cached
                // opens occasionally rendered the first chapter with no typography overrides
                // applied (tiny unstyled text at the top-left of the page), surviving until
                // the user closed and reopened the book.
                is ReaderState.Ready -> if (!formattingPreferencesReady) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .testTag("reader_loading"),
                    )
                } else {
                    val locatorHref by viewModel.currentLocatorHref.collectAsState()
                    val tocEntries by viewModel.tocEntries.collectAsState()
                    LaunchedEffect(tocVisible, showFormattingPanel) {
                        if (tocVisible || showFormattingPanel) viewModel.closeSearch()
                        viewModel.onPanelStateChanged(tocVisible || showFormattingPanel)
                    }
                    // Pause Auto-Scroll while any reader panel is open (TOC / Formatting /
                    // Search / Annotations); resume on close. Per ADR 0037.
                    LaunchedEffect(tocVisible, showFormattingPanel, isSearchActive, annotationsPanelVisible) {
                        val anyOpen = tocVisible || showFormattingPanel || isSearchActive || annotationsPanelVisible
                        viewModel.setAutoScrollPaused(
                            paused = anyOpen,
                            cause = com.riffle.core.domain.autoscroll.PauseCause.PanelOpen,
                        )
                    }
                    val spinePositions by viewModel.spinePositionCounts.collectAsState()
                    EpubNavigatorView(
                        state = s,
                        formattingPrefs = formattingPrefs,
                        railSegments = railSegments,
                        spinePositions = spinePositions,
                        // Use formattingPreferences (= _formattingPreferences, set synchronously by
                        // loadFormattingPreferences()) rather than effectiveFormattingPreferences.
                        // effectiveFormattingPreferences propagates through a combine→stateIn chain
                        // that updates asynchronously, so its .value may still be the default
                        // FormattingPreferences() (Horizontal) when the startup nav event fires on a
                        // fast (cached) epub open — causing the handler to take the paged path and
                        // consume the event without calling view.navigateTo(). formattingPreferences
                        // carries the user's actual orientation as soon as loadFormattingPreferences()
                        // returns, with no async hop.
                        formattingPrefsProvider = { viewModel.formattingPreferences.value },
                        onPositionChanged = { locator ->
                            if (!isSearchActive) immersiveState.dismissOverlay()
                            viewModel.onPositionChanged(locator)
                            viewModel.dismissFootnotePopup()
                        },
                        onViewportFractionMeasured = viewModel::putViewportFraction,
                        onNavigationEvents = viewModel.navigationEvents,
                        serverLocatorEvents = viewModel.serverLocatorEvents,
                        searchNavigationEvents = viewModel.searchNavigationEvents,
                        annotationNavigationEvents = viewModel.annotationNavigationEvents,
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
                        onEnsureSentenceQuotesReady = viewModel::ensureSentenceQuotesReady,
                        readaloudAvailable = readaloudAvailable,
                        readaloudReservePx = totalReserveCssPx,
                        readaloudHighlightColor = readaloudHighlightColor,
                        annotationsAvailable = annotationsAvailable,
                        highlightRenders = highlightRenders,
                        onHighlight = viewModel::createHighlight,
                        highlightToEdit = highlightToEdit,
                        onOpenHighlightActions = viewModel::openHighlightActions,
                        onOpenNoteReader = viewModel::openNoteReader,
                        onDismissHighlightActions = viewModel::dismissHighlightActions,
                        onRecolorHighlight = viewModel::recolorHighlight,
                        onDeleteHighlight = viewModel::deleteHighlight,
                        onUpdateHighlightNote = viewModel::updateHighlightNote,
                        autoScrollDeltas = viewModel.autoScrollScrollDeltas,
                        onReachedEndOfBook = viewModel::reachedEndOfBookForAutoScroll,
                        onAutoScrollPause = viewModel::pauseAutoScroll,
                        onAutoScrollResume = viewModel::resumeAutoScrollIfPaused,
                        dispatchers = viewModel.dispatchers,
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
                    if (annotationsPanelVisible) {
                        AnnotationsPanel(
                            annotations = annotations,
                            onNavigate = { id -> viewModel.navigateToAnnotation(id) },
                            onDelete = { id -> viewModel.deleteAnnotation(id) },
                            onRename = { id, title -> viewModel.renameBookmark(id, title) },
                            onDismiss = viewModel::closeAnnotationsPanel,
                        )
                    }
                    // Corner bookmark ribbon: must live inside this inner Box (sibling of
                    // EpubNavigatorView) so it sits above the AndroidView in the Compose hit-test
                    // tree. Placing it in the outer Box means the fillMaxSize AndroidView consumes
                    // all touches before Compose's outer-box elements ever see them.
                    // `isVisible = annotationsAvailable` omits the `state is ReaderState.Ready`
                    // guard deliberately — we're already inside that branch, so it's implicit.
                    // `graphicsLayer { }` forces the ribbon into its own GPU layer so it composites
                    // above Readium's hardware-accelerated WebView surface — Compose Box child
                    // order alone doesn't beat the WebView, which paints from its own RenderNode
                    // and otherwise hides the ribbon once the page settles.
                    CornerBookmarkIndicator(
                        isBookmarked = isCurrentPageBookmarked,
                        isVisible = annotationsAvailable,
                        onToggle = viewModel::toggleBookmark,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = 12.dp)
                            .graphicsLayer { },
                    )
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
                    formattingPrefs.showCurrentChapterLabel ||
                    formattingPrefs.showReadingTimeEstimate
                )
        if (state is ReaderState.Ready && (readaloudOpen || showRailOverlay)) {
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
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
                            if (audiobookItemId != null) {
                                viewModel.prepareAudiobookHandoff()
                                showAudiobookOverlay = true
                            }
                        },
                        onDragHint = { viewModel.hintAudiobookHandoff() },
                        onDragAbandoned = { viewModel.cancelHandoffHint() },
                    ) {
                        ReadaloudMiniPlayer(
                            isPlaying = playbackState.isPlaying,
                            speed = playbackState.speed,
                            skipIntervalSeconds = skipIntervalSec.toInt(),
                            rewindIntervalSeconds = rewindIntervalSec.toInt(),
                            barMessage = readaloudBarMessage,
                            downloadProgress = downloadProgress,
                            canPreviousChapter = playbackState.currentChapterIndex > 0,
                            canNextChapter = playbackState.currentChapterIndex >= 0 &&
                                playbackState.currentChapterIndex < playbackState.chapterCount - 1,
                            containerColor = readerPalette.background.copy(alpha = 0.65f),
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
                        showReadingTimeEstimate = formattingPrefs.showReadingTimeEstimate,
                        readerTheme = formattingPrefs.theme,
                        modifier = Modifier.onSizeChanged { railOverlayHeightPx = it.height },
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
                            IconButton(onClick = viewModel::openAnnotationsPanel) {
                                Icon(RiffleIcons.Annotations, contentDescription = "Annotations")
                            }
                            IconButton(onClick = { showFormattingPanel = true }) {
                                Text(
                                    "Aa",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.semantics { contentDescription = "Format" },
                                )
                            }
                            val autoScrollState by viewModel.autoScrollState.collectAsState()
                            val orientation = formattingPrefs.orientation
                            if (orientation == ReaderOrientation.Vertical || orientation == ReaderOrientation.Continuous) {
                                com.riffle.app.feature.reader.autoscroll.AutoScrollToggleIcon(
                                    isRunning = autoScrollState is com.riffle.core.domain.autoscroll.AutoScrollState.Running,
                                    onClick = {
                                        if (autoScrollState is com.riffle.core.domain.autoscroll.AutoScrollState.Running) {
                                            viewModel.stopAutoScroll()
                                        } else {
                                            viewModel.startAutoScroll()
                                        }
                                    },
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
            ReaderSettingsSheet(
                prefs = pickedPrefs,
                capabilities = RenderCapabilities.EPUB,
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
            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
            FootnotePopup(
                state = popupState,
                onDismiss = viewModel::dismissFootnotePopup,
                onLinkTap = { url ->
                    // Capture the reading position BEFORE the external app launches: in paginated mode,
                    // the popup overlay + the external launch leave Readium pinned at the chapter top on
                    // resume (the popup's tap cancels the WebView's column-snap mid-flight). We restore
                    // the captured position in EpubReaderViewModel.onReaderResumed.
                    viewModel.captureFootnotePopupLinkOrigin()
                    viewModel.dismissFootnotePopup()
                    uriHandler.openUri(url)
                },
            )
        }
        val returnTarget by viewModel.returnTarget.collectAsState()
        if (returnTarget != null) {
            ReturnToPositionCard(
                onReturn = viewModel::returnToCapturedPosition,
                onDismiss = viewModel::dismissReturnTarget,
            )
        }
        // Always mount the overlay when an audiobook is linked so AudiobookPlayerViewModel
        // can pre-warm (session open, metadata load) while the user reads. The overlay is
        // rendered at size 0 when hidden, making it invisible and non-interactive.
        audiobookItemId?.let { abItemId ->
            AudiobookPlayerOverlay(
                itemId = abItemId,
                visible = showAudiobookOverlay,
                windowSizeClass = windowSizeClass,
                onDismiss = {
                    showAudiobookOverlay = false
                    viewModel.onAudiobookOverlayDismissed()
                },
                onSwitchToReadaloud = { atSec ->
                    showAudiobookOverlay = false
                    viewModel.startReadaloudAtSecond(atSec)
                },
            )
        }
        // Auto-Scroll HUD pill — overlays everything else and survives Immersive Mode.
        com.riffle.app.feature.reader.autoscroll.AutoScrollHudPill(
            state = autoScrollStateForPill,
            onPause = { viewModel.stopAutoScroll() },
            onSlower = { viewModel.nudgeAutoScroll(by = -com.riffle.core.domain.autoscroll.AutoScrollSpeed.STEP_WPM) },
            onFaster = { viewModel.nudgeAutoScroll(by = com.riffle.core.domain.autoscroll.AutoScrollSpeed.STEP_WPM) },
        )
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
    showReadingTimeEstimate: Boolean,
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
    val chapterTimeRemaining by viewModel.chapterTimeRemaining.collectAsState()
    val bookTimeRemaining by viewModel.bookTimeRemaining.collectAsState()
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
            if (showProgressLabels || showChapterNameLabel || showReadingTimeEstimate) {
                ReadingProgressLabels(
                    activeChapterIndex = activeRailSegmentIndex,
                    chapterCount = railSegments.size,
                    activeChapterTitle = railSegments.getOrNull(activeRailSegmentIndex)?.title.orEmpty(),
                    totalProgress = labelProgress,
                    readerTheme = readerTheme,
                    showCountAndPercent = showProgressLabels,
                    showChapterName = showChapterNameLabel,
                    showReadingTimeEstimate = showReadingTimeEstimate,
                    chapterTimeRemaining = chapterTimeRemaining,
                    bookTimeRemaining = bookTimeRemaining,
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

private fun formatDuration(sec: Long): String {
    val hours = sec / 3600
    val minutes = (sec % 3600) / 60
    return when {
        hours > 0 -> "${hours}h ${minutes}min"
        else -> "${minutes}min"
    }
}

private fun formatChapterRemaining(remaining: TimeRemaining): String = when (remaining) {
    is TimeRemaining.Exact -> {
        val sec = remaining.sec
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        if (h > 0) "%d:%02d:%02d chapter".format(h, m, s)
        else "%d:%02d chapter".format(m, s)
    }
    is TimeRemaining.Estimated -> when {
        remaining.sec < 60 -> "< 1min chapter"
        else -> "~${formatDuration(remaining.sec)} chapter"
    }
}

private fun formatBookRemaining(remaining: TimeRemaining): String = when (remaining) {
    is TimeRemaining.Exact -> {
        val sec = remaining.sec
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        if (h > 0) "%d:%02d:%02d total".format(h, m, s)
        else "%d:%02d total".format(m, s)
    }
    is TimeRemaining.Estimated -> when {
        remaining.sec < 60 -> "< 1min total"
        else -> "~${formatDuration(remaining.sec)} total"
    }
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
    showReadingTimeEstimate: Boolean = false,
    chapterTimeRemaining: TimeRemaining? = null,
    bookTimeRemaining: TimeRemaining? = null,
) {
    val chapterCountText = if (chapterCount > 0) {
        "Chapter ${(activeChapterIndex + 1).coerceAtMost(chapterCount)} of $chapterCount"
    } else {
        ""
    }
    val pctText = "%.1f%%".format(totalProgress.coerceIn(0f, 1f) * 100f)
    val textColor = readerThemeLabelColor(readerTheme)
    val isExact = chapterTimeRemaining is TimeRemaining.Exact &&
        bookTimeRemaining is TimeRemaining.Exact
    val timeColor = if (isExact) MaterialTheme.colorScheme.tertiary else textColor
    val chapterTimeText = chapterTimeRemaining?.let { formatChapterRemaining(it) }
    val bookTimeText = bookTimeRemaining?.let { formatBookRemaining(it) }
    val showLeftColumn = showCountAndPercent || (showReadingTimeEstimate && chapterTimeText != null)
    val showRightColumn = showCountAndPercent || (showReadingTimeEstimate && bookTimeText != null)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp)
            .testTag("reading_progress_labels"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showLeftColumn) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .testTag("reading_progress_chapter"),
            ) {
                if (showCountAndPercent) {
                    Text(
                        text = chapterCountText,
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor,
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        modifier = Modifier.semantics {
                            contentDescription = "Reading progress: $chapterCountText"
                        },
                    )
                }
                if (showReadingTimeEstimate && chapterTimeText != null) {
                    Text(
                        text = chapterTimeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = timeColor,
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        modifier = Modifier.testTag("reading_progress_chapter_time"),
                    )
                }
            }
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
        if (showRightColumn) {
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier
                    .weight(1f)
                    .testTag("reading_progress_percent"),
            ) {
                if (showCountAndPercent) {
                    Text(
                        text = pctText,
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        modifier = Modifier.semantics {
                            contentDescription = "Total progress: $pctText"
                        },
                    )
                }
                if (showReadingTimeEstimate && bookTimeText != null) {
                    Text(
                        text = bookTimeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = timeColor,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        modifier = Modifier.testTag("reading_progress_book_time"),
                    )
                }
            }
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

/**
 * NavigationOptions for an annotation-panel tap. In continuous mode, [alignToTop] depends on
 * annotation type:
 *
 *  - **Bookmarks** → `alignToTop = true`. A page-level bookmark marks "I was here" — landing
 *    the saved position at the viewport TOP places the reader at the start of what they were
 *    reading, with the already-read content scrolled off above. Landing at the midpoint (the
 *    prior behaviour) placed the bookmark in the middle of the viewport, which readers found
 *    confusing.
 *  - **Highlights / notes** → `alignToTop = false`. A text-anchored annotation is context-
 *    dependent; centring on the mark preserves reading context above the anchor.
 *
 * The previous unified midpoint policy existed because landing bookmarks at the top produced a
 * ribbon-vs-landing offset — the `isCurrentPageBookmarked` indicator's stored midpoint
 * progression didn't match the arrival scrollY. Issue #399's live viewport-fraction eps closes
 * that gap: on `alignToTop=true` the arrival midpoint can drift up to one full
 * `viewportFraction` from the saved midpoint (the saved anchor could sit anywhere in the saved
 * viewport, top edge to bottom edge). Continuous eps is widened to full `viewportFraction` for
 * exactly this reason (see `BookmarksController.bookmarkEpsFor`), so the boundary-inclusive
 * `<= eps` check keeps the indicator lit on arrival regardless of where in the saved viewport
 * the anchor sat.
 */
internal fun annotationNavigationOptions(isBookmark: Boolean): NavigationOptions =
    NavigationOptions(landAtStartWhenNoTarget = false, alignToTop = isBookmark)

@OptIn(ExperimentalReadiumApi::class)
@Composable
private fun EpubNavigatorView(
    state: ReaderState.Ready,
    formattingPrefs: FormattingPreferences,
    railSegments: List<RailSegment>,
    spinePositions: Pair<List<String>, List<Int>>,
    formattingPrefsProvider: () -> FormattingPreferences,
    onPositionChanged: (Locator) -> Unit,
    onViewportFractionMeasured: (href: String, fraction: Double) -> Unit,
    onNavigationEvents: Flow<Link>,
    serverLocatorEvents: Flow<Locator>,
    searchNavigationEvents: Flow<Locator>,
    annotationNavigationEvents: Flow<com.riffle.app.feature.reader.session.AnnotationSession.AnnotationNavigationEvent>,
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
    onEnsureSentenceQuotesReady: suspend () -> Unit,
    readaloudAvailable: Boolean,
    readaloudReservePx: Int = 0,
    readaloudHighlightColor: HighlightColor,
    annotationsAvailable: Boolean,
    highlightRenders: List<EpubReaderViewModel.HighlightRender>,
    onHighlight: (Locator, androidx.compose.ui.unit.IntRect) -> Unit,
    highlightToEdit: EpubReaderViewModel.HighlightEditTarget?,
    onOpenHighlightActions: (String, androidx.compose.ui.unit.IntRect) -> Unit,
    onOpenNoteReader: (String, androidx.compose.ui.unit.IntRect) -> Unit,
    onDismissHighlightActions: () -> Unit,
    onRecolorHighlight: (String, HighlightColor) -> Unit,
    onDeleteHighlight: (String) -> Unit,
    onUpdateHighlightNote: (String, String?) -> Unit,
    autoScrollDeltas: Flow<Int>,
    onReachedEndOfBook: () -> Unit,
    onAutoScrollPause: (com.riffle.core.domain.autoscroll.PauseCause) -> Unit,
    onAutoScrollResume: () -> Unit,
    dispatchers: com.riffle.core.domain.DispatcherProvider,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val fragmentActivity = context as? FragmentActivity ?: return
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isFixedLayout = state.publication.metadata.layout == Layout.FIXED
    val coroutineScope = rememberCoroutineScope()
    val continuousViewRef = remember { mutableStateOf<ContinuousReaderView?>(null) }
    val isContinuous = formattingPrefs.orientation == ReaderOrientation.Continuous
    val fragmentRef = remember { mutableStateOf<EpubNavigatorFragment?>(null) }

    // Per-publication ReadiumPresenter (#300 step 2). Owns the seam between paginated/vertical
    // rendering and the rest of the reader. Step 2 routes decoration application through it; the
    // remaining concerns (typography, navigation, page-load + position events) cut over in later
    // steps. Continuous mode bypasses the presenter until step 5 introduces ContinuousPresenter.
    // MODE-FORK: Concrete presenter selection. Each adapter wraps a fundamentally different
    // renderer (Readium fragment vs custom NestedScrollView), so the two concrete handles are kept
    // for the few call sites that drive Readium-specific commands (fragment attach, navigateToLink,
    // updateLayoutContext). Mode-agnostic call sites use `readerPresenter` below.
    // Mutable holder for the current reserve so the bridge can read the live value (issue #331:
    // the bridge installs the readaloud-reserve capability on every page load and needs the
    // current value at install time).
    val currentReadaloudReservePxState = remember { mutableStateOf(readaloudReservePx) }
    currentReadaloudReservePxState.value = readaloudReservePx
    val rendererBridge: com.riffle.app.feature.reader.renderer.RendererBridge =
        remember(state.publication, coroutineScope) {
            // Shared across modes: the bridge short-circuits to a no-op when `fragmentProvider`
            // returns null (continuous mode keeps the fragment parked at height=0), so it is safe
            // to construct once per publication and not key on isContinuous.
            com.riffle.app.feature.reader.renderer.DefaultRendererBridge(
                fragmentProvider = { fragmentRef.value },
                readaloudReserveProvider = { currentReadaloudReservePxState.value },
            )
        }
    val readiumPresenter: ReadiumPresenter? = remember(state.publication, isContinuous, coroutineScope) {
        if (isContinuous) null else ReadiumPresenter(coroutineScope, state.publication, rendererBridge, dispatchers.main)
    }
    val continuousPresenter: ContinuousPresenter? = remember(isContinuous) {
        if (isContinuous) ContinuousPresenter() else null
    }
    // Mode-agnostic seam handle: one of the two concrete presenters is non-null for any given mode.
    // Use this for any call that only depends on [ReaderPresenter]'s abstract surface. Use the
    // concrete refs only where the screen still drives Readium/continuous-specific commands.
    //
    // The `!!` is justified by construction: both `remember` blocks above key on the same
    // `isContinuous` and emit one non-null value (continuous when true, Readium when false). Any
    // future third mode must update this derivation to stay non-null — `requireNotNull` would
    // surface the regression as a clear message instead of a generic NPE.
    val readerPresenter: ReaderPresenter = requireNotNull(readiumPresenter ?: continuousPresenter) {
        "ReaderPresenter must be non-null: both concrete adapters were null for isContinuous=$isContinuous"
    }
    DisposableEffect(readiumPresenter, continuousPresenter) {
        onDispose {
            readiumPresenter?.detach()
            continuousPresenter?.detach()
        }
    }

    // The Readium fragment lives inside an AndroidView that stays mounted across every orientation
    // (the factory only runs once — see the "In Continuous mode the fragment is kept alive only to
    // maintain the HTTP server" comment around line 2014). So the factory's one-shot
    // `readiumPresenter?.attach(fragment)` only ever attaches the FIRST presenter — which is null
    // when the book opens in Continuous mode, and goes stale on any later isContinuous flip that
    // rebuilds the presenter. Re-attach here whenever a fresh presenter meets the existing
    // fragment, so decoration applies and currentLocator forwarding land on a live navigator.
    LaunchedEffect(readiumPresenter, fragmentRef.value) {
        val presenter = readiumPresenter ?: return@LaunchedEffect
        val fragment = fragmentRef.value ?: return@LaunchedEffect
        presenter.attach(fragment)
    }

    // Forward per-chapter viewport-fraction measurements from the active renderer into the VM's
    // `viewportFractionByHref` map. The VM applies a per-entry distinct-until-changed guard so
    // repeat values do not churn the bookmark combine (issue #399). The producers only emit on
    // measurement/reflow events, never on scroll.
    LaunchedEffect(readerPresenter) {
        readerPresenter.viewportFractionEvents.collect { (href, fraction) ->
            onViewportFractionMeasured(href, fraction)
        }
    }

    // MODE-FORK: Highlight rendering pipeline. ContinuousHighlightRenderer drives custom JS
    // injection per ChapterWebView; ReadiumHighlightRenderer drives DecorableNavigator on the
    // attached fragment. The two pipelines are inherently different — the seam HighlightRenderer
    // type already abstracts the call sites, this `remember` only picks which concrete instance.
    val highlightRenderer: HighlightRenderer = remember(isContinuous, readiumPresenter) {
        if (isContinuous) {
            ContinuousHighlightRenderer(targetProvider = { continuousViewRef.value })
        } else {
            val presenter = readiumPresenter!! // by construction: non-null when !isContinuous
            ReadiumHighlightRenderer(
                applyDecorationsBlock = { decorations, group ->
                    presenter.applyDecorations(decorations, group)
                },
                fragmentLocator = ::fragmentLocator,
                currentNavigatorStamp = { presenter.attachmentStamp() },
            )
        }
    }

    // Owns the sentence-highlight + auto-follow LaunchedEffects (see [SentencePlaybackController.Attach]
    // below). Keyed on the three renderer/presenter refs so a mode flip (Paginated/Vertical ↔ Continuous)
    // recreates the controller — the passed lambdas capture the enclosing scope's `val`s at closure-
    // creation time (Kotlin closures capture by value, not by reference to the local variable slot), so
    // without these keys a mode flip would leave the controller invoking the pre-flip renderer while the
    // post-flip WebView surface gets nothing. This was the "highlight paints in previous mode only after
    // switching modes" regression from the ADR-0039 refactor.
    val sentencePlaybackController = remember(highlightRenderer, readerPresenter, readiumPresenter) {
        SentencePlaybackController(
            highlightRenderer = { highlightRenderer },
            readerPresenter = { readerPresenter },
            readiumPresenter = { readiumPresenter },
            fragmentLocator = ::fragmentLocator,
        )
    }

    val containerRef = remember { mutableStateOf<ScrollBoundaryNavigationContainer?>(null) }
    // Caches the most-recent text-selection bounding rect in CSS viewport px, populated by
    // RiffleSelBridge on every selectionchange — before the floating action-mode toolbar fires.
    // Written on the JS background thread; read on the main thread in onGetContentRect.
    val pagedSelectionRectCss = remember { AtomicReference<android.graphics.RectF?>(null) }
    // Set by the touchstart snapshot in SELECTION_SPAN_TRACKER_JS: true if a non-collapsed text
    // selection was live at the start of the current touch. Read in the InputListener.onTap
    // handler so a tap that only dismisses the text-selection popup does not also toggle
    // immersive mode. Written on the JS background thread; consumed on the main thread.
    val pagedSelectionActiveAtDown = remember { AtomicBoolean(false) }
    val pagedSelectionRectBridge = remember {
        RiffleSelectionRectBridge(pagedSelectionRectCss, pagedSelectionActiveAtDown)
    }
    // Tracks whether Readium is currently in vertical-scroll mode (scroll=true). Read from the
    // startActionModeForChild wrapper to route the selection popup differently — the JS-captured
    // CSS rect is viewport-relative and works cleanly for paginated columns, but in scroll mode the
    // WebView scrolls internally and the CSS coordinates get mis-mapped by the floating toolbar
    // framework (popup lands below the visible display frame). In scroll mode we bypass the CSS
    // rect and let Readium's native ActionMode callback populate outRect, then only clamp its
    // bottom into the visible band.
    val readiumIsScrollMode = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
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
    val currentEnsureSentenceQuotesReady by rememberUpdatedState(onEnsureSentenceQuotesReady)
    val currentSentenceQuotes by rememberUpdatedState(sentenceQuotes)
    val currentSentenceChapters by rememberUpdatedState(sentenceChapters)
    val currentOnHighlight by rememberUpdatedState(onHighlight)
    val currentOnOpenHighlightActions by rememberUpdatedState(onOpenHighlightActions)
    val currentOnOpenNoteReader by rememberUpdatedState(onOpenNoteReader)
    val currentOnUpdateHighlightNote by rememberUpdatedState(onUpdateHighlightNote)
    val currentAnnotationsAvailable by rememberUpdatedState(annotationsAvailable)
    val currentReadaloudAvailable by rememberUpdatedState(readaloudAvailable)
    // Latest readaloud bottom reserve, read inside the (remembered-once) pagination listener so each
    // freshly loaded page re-applies the current value.
    val currentPublication by rememberUpdatedState(state.publication)
    // rememberUpdatedState: spinePositions arrives asynchronously (Readium computes positions
    // after publication load). The AndroidView factory captures this reference so the continuous
    // onPositionChanged lambda always reads the latest pair when building the locator.
    val currentSpinePositions by rememberUpdatedState(spinePositions)

    // Coordinator created once; lambdas close over rememberUpdatedState delegates so each
    // invocation always reads the latest value, not the value at remember time.
    val coordinator = remember(state.publication) {
        ContinuousReaderCoordinator(
            publication = state.publication,
            spinePositionsProvider = { currentSpinePositions },
            latestLocator = { currentLatestLocator() },
            sentenceQuotesProvider = { currentSentenceQuotes },
            sentenceChaptersProvider = { currentSentenceChapters },
            coroutineScope = coroutineScope,
            ensureSentenceQuotesReady = { currentEnsureSentenceQuotesReady() },
            navigation = object : ContinuousNavigationSink {
                override fun onTap() = currentOnTap()
                override fun onLocator(locator: Locator) = onPositionChanged(locator)
            },
            links = object : ContinuousLinkSink {
                override fun onFollowInternalLink(link: Link, origin: Locator) =
                    currentOnFollowInternalLink(link, origin)
                override fun onExternalLink(url: String) {
                    runCatching {
                        fragmentActivity.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(url),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
                override fun onFootnote(content: FootnoteContent) = currentOnFootnoteTapped(content)
                override fun captureReturnAnchor(origin: Locator) = currentOnCaptureReturnTarget(origin)
            },
            annotations = object : ContinuousAnnotationSink {
                override fun onAnnotationTap(id: String, rect: IntRect) =
                    currentOnOpenHighlightActions(id, rect)
                override fun onAnnotationNoteTap(id: String, rect: IntRect) =
                    currentOnOpenNoteReader(id, rect)
                override fun onHighlight(locator: Locator, rect: IntRect) =
                    currentOnHighlight(locator, rect)
                override fun onPlayFromHere(fragmentRef: String) =
                    currentOnPlayFromHere(fragmentRef)
            },
        )
    }

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
    val highlightMenuId = remember { View.generateViewId() }
    val copyMenuId = remember { View.generateViewId() }
    val searchMenuId = remember { View.generateViewId() }
    val shareMenuId = remember { View.generateViewId() }
    val playFromHereActionMode = remember {
        object : android.view.ActionMode.Callback {
            override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
                menu.add(0, copyMenuId, 0, android.R.string.copy)
                if (currentAnnotationsAvailable) {
                    menu.add(0, highlightMenuId, 1, "Highlight")
                        .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
                }
                if (currentReadaloudAvailable) {
                    menu.add(0, playFromHereMenuId, 2, "Play")
                        .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
                }
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
                        val container = containerRef.value ?: return false
                        coroutineScope.launch {
                            val selection = selectable.currentSelection() ?: return@launch
                            val rawRect = selection.rect
                                ?: run { selectable.clearSelection(); return@launch }
                            val rect = rawRect.toWindowIntRect(container)
                            currentOnHighlight(selection.locator, rect)
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
                            // Wait for the sentence-quote map to be built off the SMIL sidecar/bundle.
                            // Without this, a first-play tap resolves against an empty quote map, `byText`
                            // falls to null, and the Readium fallback anchor (e.g. `#d1e770`) reaches
                            // ReadaloudTrack.resolveStartClip — which drops to the first clip of the
                            // chapter, restarting playback. ensureSentenceQuotesReady() is a no-op once
                            // the build has run.
                            currentEnsureSentenceQuotesReady()
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
                                rendererBridge.resolveSelectionSentence(sentences)
                            } else {
                                null
                            }
                            val spanId = byText
                                ?: nav?.let { rendererBridge.readSelectionSpanId() }
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
                if (consumeSelectionSuppressedTap(pagedSelectionActiveAtDown)) return false
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
                    val landedAtEnd = rendererBridge.landedAtEnd()
                    // Install every paged/vertical-mode JS capability in dependency order — rect
                    // polyfill, selection tracker, typography, footnote bridge, readaloud reserve,
                    // settle backstop — through the renderer bridge (#331). The capability list is
                    // declared once in DefaultRendererBridge; adding a new one is a registration,
                    // not another evaluateJavascript() at this call site.
                    //
                    // MUST run before bumping [pageLoadGeneration]: the polyfills that ship with the
                    // rect capability (ChildNode.append, Object.entries, ResizeObserver) are the only
                    // reason Readium's `registerDecorationTemplates` doesn't throw on the old-Chromium
                    // WebViews. The annotation re-apply LaunchedEffect keys on pageLoadGeneration; if
                    // we bump before installing polyfills, the re-apply's evaluateJavascript queues
                    // BEFORE ours and Readium's template registration runs against a still-broken
                    // engine — decorations register into an internal map but never emit CSS or DOM.
                    rendererBridge.installPageCapabilities()
                    pageLoadGeneration.value += 1
                    // NOTE: do NOT snap to the column grid here for the general case. The typography
                    // injection above reflows the page asynchronously, so a snap at this point rounds a
                    // pre-reflow position and lands close-but-not-exact. The post-go() snap in the
                    // navigation handlers runs after the reflow has settled and is the authoritative one.
                    //
                    // EXCEPTION: a backward cross-resource turn has no nav handler in paginated mode (it's
                    // Readium's own ViewPager), so there is no post-go snap to keep the end pinned through
                    // the reflow. Re-pin to the last column here; snapToEnd's rAF loop tracks the reflow.
                    if (landedAtEnd && currentHrefHolder[0] == hrefAtLoad) {
                        rendererBridge.snapToEnd()
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
        if (fragmentRef.value == null) return@LaunchedEffect
        withContext(dispatchers.main) {
            rendererBridge.applyReadaloudReserve(readaloudReservePx)
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
                    coroutineScope.launch(dispatchers.main) {
                        currentOnFootnoteTapped(target.content)
                    }
                    true
                }
                FootnoteResolver.AnchorTarget.CrossReference -> {
                    // Capture where we are BEFORE the snap; offer a way back only if the target was
                    // actually off-page (the snap reports whether it changed columns).
                    val origin = currentLatestLocator()
                    coroutineScope.launch(dispatchers.main) {
                        val moved = if (fragmentRef.value != null) {
                            rendererBridge.snapToElement(fragmentId)
                        } else {
                            false
                        }
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
            // In Continuous mode the Readium fragment is a server-keeper only (invisible,
            // height=0): fragment.go() would suspend forever on the invisible WebView, leaving
            // navigating=true and covering the reader with a permanent blank overlay. So TOC entries,
            // chapter-map segments and internal links are routed to ContinuousReaderView.navigateTo
            // instead of the fragment.
            //
            // Read orientation from formattingPrefsProvider() rather than the Compose-collected
            // formattingPrefs: on a startup navigation from item-details, Compose state may not
            // have re-rendered yet. formattingPrefsProvider reads _formattingPreferences directly
            // (set synchronously by loadFormattingPreferences() before openBook() fires the event),
            // so it always carries the correct orientation at this point.
            if (formattingPrefsProvider().orientation == ReaderOrientation.Continuous) {
                coordinator.onTocNavigation(link)
                return@collect
            }
            // Wait for the fragment to be ready — same timing concern as the continuous case above.
            snapshotFlow { fragmentRef.value }.filterNotNull().first()
            // Cover only a cross-resource jump (where the load flash happens); a same-chapter jump
            // is instant and needs no mask.
            val cover = link.href.toString().substringBefore('#') !=
                currentHrefHolder[0]?.substringBefore('#')
            navigating = cover
            try {
                // Navigate and snap onto the target's column, tracked through the new chapter's async
                // typography reflow (ColumnSnap owns the grid math). The cover is just cosmetic now.
                readiumPresenter?.navigateToLink(link)
                if (cover) delay(NAV_COVER_SETTLE_MS)
            } finally {
                navigating = false
            }
        }
    }

    // Background position sync (peer/resume/audiobook handoff). Never covers — a flash
    // mid-reading would be jarring. The background sync carries a within-chapter progression but
    // no DOM fragment, so landAtStartWhenNoTarget=false preserves where go() landed instead of
    // snapping to the chapter top.
    //
    // Awaits the fragment via snapshotFlow before snapping — same race as [goAndSnapWithCover]. On a
    // cold open the resume locator is emitted on Activity ON_START (EpubReaderViewModel.onReaderResumed),
    // which fires BEFORE the AndroidView composition has assigned fragmentRef. A bare `?.let` here
    // silently drops the snap, leaving the page wherever Readium's initialLocator landing rested — which
    // can be off-grid (the "page slightly turned to next" bug on book open). The at-rest backstop
    // [SETTLE_SNAP_INSTALL_JS] doesn't rescue it: it arms on a scroll event, and Readium's programmatic
    // initial landing never fires one.
    // All four nav pipelines below route through the same seam: presenter.navigateTo(target, opts).
    // The screen owns the cover-and-wait-for-fragment policy because that's UI state (the
    // `navigating` cover overlay). The seam owns whether snap / animation / vertical-vs-paginated
    // applies — vertical's snap=true is a no-op since column pagination doesn't apply to scroll
    // mode, so we don't fork the call sites on orientation.

    /** Wait for the renderer to be ready, then drive the seam under the screen-owned cover. */
    val navigateWithCover: suspend (NavigationTarget, NavigationOptions, String) -> Unit = nav@{ target, opts, href ->
        if (!isContinuous) {
            // Paginated/vertical wait for the EpubNavigatorFragment — same race the TOC handler dodges.
            // Continuous mode's view is created synchronously on first composition, so no wait needed.
            snapshotFlow { fragmentRef.value }.filterNotNull().first()
        }
        val cover = href.substringBefore('#') != currentHrefHolder[0]?.substringBefore('#')
        navigating = cover
        try {
            readerPresenter.navigateTo(target, opts)
            if (cover) delay(NAV_COVER_SETTLE_MS)
        } finally {
            navigating = false
        }
    }

    LaunchedEffect(serverLocatorEvents) {
        // Background server-progress sync: honour the locator's progression (no chapter-top yank),
        // no cover — a flash mid-reading would be jarring.
        serverLocatorEvents.collect { locator ->
            if (!isContinuous) snapshotFlow { fragmentRef.value }.filterNotNull().first()
            readerPresenter.navigateTo(
                NavigationTarget.ToLocatorJson(locator.toJSON().toString()),
                NavigationOptions(landAtStartWhenNoTarget = false),
            )
        }
    }

    LaunchedEffect(returnNavEvents) {
        returnNavEvents.collect { locator ->
            navigateWithCover(
                NavigationTarget.ToLocatorJson(locator.toJSON().toString()),
                NavigationOptions(landAtStartWhenNoTarget = false),
                locator.href.toString(),
            )
        }
    }

    LaunchedEffect(searchNavigationEvents) {
        searchNavigationEvents.collect { locator ->
            navigateWithCover(
                NavigationTarget.ToLocatorJson(locator.toJSON().toString()),
                NavigationOptions(landAtStartWhenNoTarget = false),
                locator.href.toString(),
            )
            val text = locator.text.highlight?.take(40) ?: ""
            if (text.isNotBlank()) {
                highlightRenderer.highlightSearchMatch(
                    href = locator.href.toString(),
                    text = text,
                )
            }
        }
    }

    // MODE-FORK: annotation-nav landing precision differs by renderer. Readium (paginated + vertical)
    // has DecorableNavigator + fragment-anchored go(), so the paragraph-level Locator lands correctly.
    // Continuous has no equivalent seam — the paragraph anchor is only ~correct, so we look up the
    // actual `<mark data-riffle-ann>` device-Y and centre on it. Cannot live behind ReaderPresenter
    // without leaking Readium/Continuous internals up the stack.
    LaunchedEffect(annotationNavigationEvents, isContinuous) {
        annotationNavigationEvents.collect { event ->
            val view = continuousViewRef.value
            if (isContinuous && !event.isBookmark && event.annotationId != null && view != null) {
                val locations = event.locator.locations
                val href = event.locator.href.toString()
                val anchor = locations.fragments.firstOrNull()
                val fullHref = if (anchor != null) "$href#$anchor" else href
                view.navigateTo(
                    href = fullHref,
                    progression = locations.progression?.toFloat() ?: 0f,
                    alignToTop = false,
                    focusAnnotationId = event.annotationId,
                )
            } else {
                navigateWithCover(
                    NavigationTarget.ToLocatorJson(event.locator.toJSON().toString()),
                    annotationNavigationOptions(isBookmark = event.isBookmark),
                    event.locator.href.toString(),
                )
            }
        }
    }

    // Resolve "top of the current page" when readaloud reopens on a different page: ask the WebView
    // for the first narrated sentence visible on the page (spans are stripped, so we search by the
    // sentence's text — quotes are in reading order and only this chapter's sentences are in the DOM).
    LaunchedEffect(pageTopProbeRequests) {
        pageTopProbeRequests.collect { href ->
            val ordered = currentSentenceQuotes.entries.toList()
            val fragmentId = if (fragmentRef.value != null && ordered.isNotEmpty()) {
                val idx = rendererBridge.firstVisibleSentenceIndex(ordered.map { it.value.highlight })
                idx?.let { ordered.getOrNull(it)?.key }
            } else {
                null
            }
            onPageTopResolved(href, fragmentId)
        }
    }

    LaunchedEffect(highlightRenderer, searchResults, currentSearchIndex, reflowGeneration, pageLoadGeneration.value) {
        highlightRenderer.applySearch(searchResults, currentSearchIndex)
    }

    // ---- Readaloud synced highlight + auto-follow ------------------------------------------
    // See [SentencePlaybackController.Attach] for the key-list rationale (unchanged from the
    // inline LaunchedEffects this replaces) and the auto-follow behaviour.
    sentencePlaybackController.Attach(
        activeFragmentRef = activeFragmentRef,
        sentenceQuotes = sentenceQuotes,
        readaloudHighlightColor = readaloudHighlightColor,
        reflowGeneration = reflowGeneration,
        pageLoadGeneration = pageLoadGeneration.value,
    )

    // ---- Persisted highlights (annotations + note glyphs) ----------------------------------
    // Superset keys: continuous re-keys on activeFragmentRef's base href when a new chapter
    // enters the sliding window; Readium re-applies on reflow/pageLoad events. The
    // [highlightRenderer] key catches an orientation flip mid-session: the renderer is
    // recreated (Readium ↔ Continuous) but none of the other keys necessarily change at that
    // moment, so without keying on the renderer the fresh instance would never be invoked
    // and the previously-applied highlights would stay invisible until the book is reopened.
    // (Continuous mode also has a bootstrap inside LaunchedEffect(continuousView) below,
    // because the ContinuousReaderView ref binds during AndroidView's layout phase and is
    // worth applying to as soon as the ref is non-null — a slot the highlightRenderer key
    // alone doesn't cover.)
    LaunchedEffect(highlightRenderer, highlightRenders, reflowGeneration, pageLoadGeneration.value, activeFragmentRef?.substringBefore('#')) {
        highlightRenderer.applyAnnotations(highlightRenders)
    }

    LaunchedEffect(highlightRenderer, highlightRenders, reflowGeneration, pageLoadGeneration.value) {
        highlightRenderer.applyNoteGlyphs(highlightRenders)
    }

    // ---- Decoration tap listener (annotations) ---------------------------------------------
    // Opens the highlight-actions popup when the user taps an existing highlight decoration.
    DisposableEffect(fragmentRef.value) {
        val fragment = fragmentRef.value as? DecorableNavigator
        val listener = object : DecorableNavigator.Listener {
            override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                if (event.group != "annotations") return false
                val container = containerRef.value ?: return false
                val rawRect = event.rect ?: return false
                val rect = rawRect.toWindowIntRect(container)
                currentOnOpenHighlightActions(event.decoration.id, rect)
                return true
            }
        }
        fragment?.addDecorationListener("annotations", listener)
        onDispose { fragment?.removeDecorationListener(listener) }
    }

    // ---- Decoration tap listener (annotation-notes) ----------------------------------------
    // Tapping the margin note glyph opens the same highlight-actions popup as tapping the
    // highlight text. The glyph lives in the left gutter (outside the text hit area), so the
    // "annotations" listener above does NOT fire for it — this dedicated listener is required.
    DisposableEffect(fragmentRef.value) {
        val fragment = fragmentRef.value as? DecorableNavigator
        val listener = object : DecorableNavigator.Listener {
            override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                if (event.group != "annotation-notes") return false
                val container = containerRef.value ?: return false
                val rawRect = event.rect ?: return false
                val rect = rawRect.toWindowIntRect(container)
                currentOnOpenNoteReader(event.decoration.id, rect)
                return true
            }
        }
        fragment?.addDecorationListener("annotation-notes", listener)
        onDispose { fragment?.removeDecorationListener(listener) }
    }

    // ---- Auto-follow: keep the narrated sentence on screen ---------------------------------
    // Now hosted by [sentencePlaybackController.Attach] above (see that call and
    // [SentencePlaybackController] for the full rationale) — extracted per ADR 0039 so a future
    // non-Readaloud driver (Cadence) can attach the same pipeline.

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
            if (progress == null) { columnProgression.reset(); measuredRef = null; return@collect }
            val ref = progress.fragmentRef
            if (ref.indexOf('#') < 0) return@collect
            val quote = sentenceQuotes[ref.substringAfter('#', "")] ?: return@collect
            if (ref != measuredRef) {
                // New sentence (or a relayout restarted this effect): measure how it spans columns.
                // Empty in vertical / continuous — the progression then never advances, which is the
                // correct behaviour for those modes.
                columnProgression.onSentence(readerPresenter.measureReadaloudColumns(quote.highlight))
                measuredRef = ref
            }
            columnProgression.advance(progress.fraction)?.let { column ->
                readerPresenter.snapReadaloudColumn(quote.highlight, column)
            }
        }
    }

    LaunchedEffect(volumeNavEvents) {
        volumeNavEvents.collect { event ->
            val direction = if (event == VolumeNavEvent.Forward) PageDirection.Forward else PageDirection.Backward
            val container = containerRef.value
            // Vertical (scroll) mode is the only path that uses ScrollBoundaryNavigationContainer's
            // smooth in-page scroll: a volume press scrolls a viewport within the chapter unless
            // the user is at the boundary, in which case the container drives a cross-resource
            // turn via its installed JS evaluator. Paginated + continuous fall through to the
            // mode-agnostic presenter.pageBy(); ContinuousPresenter and ReadiumPresenter own the
            // mode-specific turn semantics.
            if (currentFormattingPrefs.orientation == ReaderOrientation.Vertical && container != null) {
                if (fragmentRef.value == null) return@collect
                val boundary = readerPresenter.scrollBoundary()
                val atBoundary = if (direction == PageDirection.Forward) boundary.atForwardBoundary
                else boundary.atBackwardBoundary
                container.handleVolumeScroll(forward = direction == PageDirection.Forward, atBoundary = atBoundary) { js ->
                    launch { rendererBridge.evaluateBoundaryScroll(js) }
                }
            } else {
                readerPresenter.pageBy(direction)
            }
        }
    }

    // fragmentRef.value is a key so the effect re-fires when the fragment becomes available
    // after rotation (isLandscape changes while fragmentRef is null, so the call would be lost).
    LaunchedEffect(formattingPrefs, isLandscape, fragmentRef.value, readiumPresenter) {
        readiumPresenter?.updateLayoutContext(isLandscape = isLandscape, isFixedLayout = isFixedLayout)
        readiumPresenter?.applyTypography(formattingPrefs)
        coordinator.onPreferencesChanged(formattingPrefs)
    }

    // If the user switches TO Continuous mode while a navigating cover was active (the
    // onNavigationEvents LaunchedEffect gets cancelled mid-goAndSnap), navigating would stay
    // true indefinitely. Clear it whenever isContinuous becomes true.
    LaunchedEffect(isContinuous) {
        if (isContinuous) navigating = false
    }

    // Sync the Readium fragment to the continuous reader's position when the user switches
    // FROM Continuous to a Readium-driven mode. In continuous mode the fragment is held alive
    // for its HTTP server only — invisible, height=0 — and TOC/scroll navigation steers
    // ContinuousReaderView via ContinuousPresenter, leaving the fragment parked at
    // wherever the book first opened. Without this, mode-switching showed the user the original
    // book-open chapter instead of the chapter they were actually reading in continuous mode.
    //
    // Suppress the first composition so a fresh open (already initialised with the same
    // locator) doesn't fire a redundant nav. wasContinuous tracks the previous value via
    // rememberSaveable so a config change can't fire a spurious sync.
    var wasContinuous by rememberSaveable { mutableStateOf(isContinuous) }
    LaunchedEffect(isContinuous) {
        val transitionedFromContinuous = wasContinuous && !isContinuous
        wasContinuous = isContinuous
        if (!transitionedFromContinuous) return@LaunchedEffect
        val locator = currentLatestLocator() ?: return@LaunchedEffect
        // Wait for the fragment to be present AND for the AndroidView update block to have
        // flipped its container visibility (mode change → recomposition → update runs). The
        // navigator's WebView only completes go() once visible, so navigating it while still
        // height=0 would suspend forever (the same gotcha called out in onNavigationEvents).
        val fragment = snapshotFlow { fragmentRef.value }.filterNotNull().first()
        fragment.go(locator, animated = false)
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

    // Poll scroll boundaries so ScrollBoundaryNavigationContainer can decide synchronously inside
    // ACTION_MOVE whether the user is wedged against a chapter end. Readium's locator progression
    // value is unreliable for this — it keeps emitting during touch even when scroll position
    // hasn't moved, which broke the previous staleness check. The seam owns the WebView query so
    // the screen stays free of inline JS strings; the loop lifecycle stays here because polling is
    // a UI concern, not a renderer concern.
    LaunchedEffect(fragmentRef.value, currentFormattingPrefs.orientation) {
        if (currentFormattingPrefs.orientation != ReaderOrientation.Vertical) return@LaunchedEffect
        if (fragmentRef.value == null) return@LaunchedEffect
        while (true) {
            val container = containerRef.value
            if (container != null) {
                withContext(dispatchers.main) {
                    val boundary = readerPresenter.scrollBoundary()
                    container.atForwardBoundary = boundary.atForwardBoundary
                    container.atBackwardBoundary = boundary.atBackwardBoundary

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
    val isPaginated = !isFixedLayout && formattingPrefs.orientation == ReaderOrientation.Horizontal
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
                    // FrameLayout wrapper that intercepts the action-mode callback Readium passes
                    // up via startActionModeForChild. Readium's Callback2Wrapper delegates
                    // onGetContentRect to the native WebView callback, which returns wrong
                    // coordinates for selections at the top of the page, placing the floating
                    // toolbar at the bottom of the screen. By wrapping the callback here (above
                    // FragmentContainerView, which is final) we substitute the JS-captured rect
                    // from RiffleSelBridge (written by selectionchange before startActionMode fires)
                    // and the toolbar anchors to the actual selection.
                    val actionModeInterceptor = object : FrameLayout(ctx) {
                        override fun startActionModeForChild(
                            originalView: android.view.View,
                            callback: android.view.ActionMode.Callback?,
                            type: Int,
                        ): android.view.ActionMode? {
                            val inner = callback ?: return super.startActionModeForChild(originalView, null, type)
                            val wrapped = object : android.view.ActionMode.Callback2() {
                                override fun onGetContentRect(
                                    mode: android.view.ActionMode,
                                    view: android.view.View,
                                    outRect: android.graphics.Rect,
                                ) {
                                    // Paginated mode: use the JS-captured CSS rect (Readium's default
                                    // returns wrong coordinates for selections at the top of a page,
                                    // placing the toolbar at the bottom of the screen). Scroll mode:
                                    // the CSS rect from getBoundingClientRect() maps poorly through
                                    // the WebView's internal scroll → the toolbar can land off-screen
                                    // below the visible frame. Fall through to Readium's native
                                    // callback in scroll mode; it already reports the selection in
                                    // whatever coordinate space the framework expects for a scrolled
                                    // WebView.
                                    val cssRect = pagedSelectionRectCss.get()
                                    if (!readiumIsScrollMode.get() && cssRect != null && !cssRect.isEmpty) {
                                        val dpr = view.resources.displayMetrics.density
                                        outRect.set(
                                            (cssRect.left * dpr).toInt(),
                                            (cssRect.top * dpr).toInt(),
                                            (cssRect.right * dpr).toInt(),
                                            (cssRect.bottom * dpr).toInt(),
                                        )
                                    } else if (inner is android.view.ActionMode.Callback2) {
                                        inner.onGetContentRect(mode, view, outRect)
                                    } else {
                                        super.onGetContentRect(mode, view, outRect)
                                    }
                                    // Whichever path populated outRect, pull its bottom into the
                                    // window-visible band if it sticks out below (gesture-bar strip
                                    // on edge-to-edge layouts). Preserves the framework's above-
                                    // placement anchor when the selection is already inside the band.
                                    clampReaderSelectionRectBottom(view, outRect)
                                }
                                override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = inner.onCreateActionMode(mode, menu)
                                override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = inner.onPrepareActionMode(mode, menu)
                                override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem) = inner.onActionItemClicked(mode, item)
                                override fun onDestroyActionMode(mode: android.view.ActionMode) = inner.onDestroyActionMode(mode)
                            }
                            return super.startActionModeForChild(originalView, wrapped, type)
                        }
                    }
                    val fragmentContainer = FragmentContainerView(ctx).apply { id = containerId }
                    actionModeInterceptor.addView(fragmentContainer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                    addView(actionModeInterceptor, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                }.also { containerRef.value = it }
            },
            update = { container ->
                // In Continuous mode the fragment is kept alive only to maintain the HTTP server.
                // Collapse it to zero-height so ContinuousReaderView takes the full screen.
                if (formattingPrefs.orientation == ReaderOrientation.Continuous) {
                    container.layoutParams = container.layoutParams?.apply { height = 0 }
                    container.visibility = View.INVISIBLE
                } else {
                    container.layoutParams = container.layoutParams?.apply { height = FrameLayout.LayoutParams.MATCH_PARENT }
                    container.visibility = View.VISIBLE
                }
                container.isScrollMode = formattingPrefs.orientation != ReaderOrientation.Horizontal
                // MODE-FORK: routes the Readium ActionMode-wrapper's coordinate handling —
                // paginated columns want the JS-captured CSS rect, vertical scroll wants Readium's
                // native callback because the CSS rect maps wrong through the WebView's internal
                // scroll. This is a UI-lifecycle fork (framework popup positioning), not domain
                // behaviour, so it doesn't belong behind ReaderPresenter.
                readiumIsScrollMode.set(formattingPrefs.orientation == ReaderOrientation.Vertical)
                // Pull callbacks capture composable-local State vars; re-set on every update so
                // back-stack returns (which re-create the composable but reuse the cached View)
                // always write to the current State instances rather than stale ones.
                container.onPullStarted = { fwd ->
                    pullForward = fwd
                    pullProgress = 0f
                    pullActive = true
                }
                container.onPullEnded = { pullActive = false }
                container.onPullProgress = { p -> pullProgress = p }

                val fragmentContainer = (container.getChildAt(0) as? android.view.ViewGroup)
                    ?.getChildAt(0) as? FragmentContainerView
                    ?: return@AndroidView

                val isScrollMode = formattingPrefs.orientation != ReaderOrientation.Horizontal
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
                    formattingPrefs.orientation == ReaderOrientation.Horizontal &&
                    formattingPrefs.doublePageSpread &&
                    isLandscape
                val existingFrag = fragmentRef.value
                if (existingFrag != null && fragmentDoublePageHolder[0] != isDoublePage) {
                    fm.beginTransaction().remove(existingFrag).commitNow()
                    readiumPresenter?.detach()
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
                            registerJavascriptInterface("RiffleSelBridge") { _ ->
                                pagedSelectionRectBridge
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
                    // Attach to the ReaderPresenter seam (#300 step 2). Today only decoration
                    // application flows through it; remaining concerns cut over in later steps.
                    readiumPresenter?.attach(fragment)
                    // Readium's built-in ANIMATED page transition (Kotlin toolkit 3.2.0+): edge taps
                    // turn the page with a smooth slide instead of an instant jump. Added before
                    // tapListener so the adapter consumes horizontal edge taps and tapListener only
                    // sees the center taps it doesn't handle.
                    fragment.addInputListener(
                        DirectionalNavigationAdapter(
                            navigator = fragment,
                            animatedTransition = true,
                        ),
                    )
                    fragment.addInputListener(tapListener)
                    coroutineScope.launch {
                        fragment.currentLocator.collect { locator ->
                            currentHrefHolder[0] = locator.href.toString()
                            // In Continuous mode the EpubNavigatorFragment is held alive only to keep
                            // Readium's HTTP server up (see the height=0 / INVISIBLE collapse above).
                            // Its currentLocator still emits its content-top "initial locator"
                            // (progression=0 with the chapter title) shortly after WebView load —
                            // typically AFTER ContinuousReaderView has already persisted a real
                            // midpoint-derived position via [onRawPosition]. Without this guard, that
                            // delayed Readium emission overwrites the saved position with prog=0,
                            // and the next reopen lands viewport-top at the chapter top instead of
                            // the user's actual reading spot. ContinuousReaderView owns position
                            // tracking in this mode.
                            if (formattingPrefsProvider().orientation != ReaderOrientation.Continuous) {
                                onPositionChanged(locator)
                            }
                            val key = locator.href.removeFragment().toString()
                            if (key.isNotEmpty() && !footnoteDocCache.containsKey(key)) {
                                launch(dispatchers.io) {
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

                fragmentRef.value?.let { _ ->
                    container.onNavigateForward = navigateForward@{
                        val idx = state.publication.readingOrder
                            .indexOfFirst { it.href.toString() == currentHrefHolder[0] }
                        if (idx < 0 || idx >= state.publication.readingOrder.size - 1) return@navigateForward
                        coroutineScope.launch { readiumPresenter?.pageBy(PageDirection.Forward) }
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
                            readiumPresenter?.navigateToLocator(locator, snap = false, animated = false)
                        }
                    }
                }
            },
            modifier = readerModifier,
        )
        if (isContinuous) {
            // Readium always serves EPUB resources at https://readium_package/<href>.
            // ChapterWebView intercepts that virtual host and fetches from the Publication object,
            // so we can construct chapter URLs directly without querying the fragment's WebView.
            val chapters = remember {
                state.publication.readingOrder.map { link ->
                    ContinuousReaderView.ChapterEntry(
                        link,
                        "https://readium_package/${link.href.toString().trimStart('/')}",
                    )
                }
            }
            AndroidView(
                factory = { ctx ->
                    ContinuousReaderView(ctx).also { view ->
                        continuousViewRef.value = view
                        // Wire the presenter BEFORE attach so the coordinator's onRawPosition
                        // handler routes raw-position events through it on the very first scroll.
                        coordinator.presenter = continuousPresenter
                        continuousPresenter?.attach(view)
                        coordinator.attach(view)
                        view.annotationsAvailable = currentAnnotationsAvailable
                        view.readaloudAvailable = currentReadaloudAvailable
                    }
                },
                // Availability flags can flip mid-session; keep the selection menu gates in sync.
                update = {
                    it.annotationsAvailable = annotationsAvailable
                    it.readaloudAvailable = readaloudAvailable
                },
                modifier = readerModifier,
            )
            // Key on the view ref: AndroidView.factory (which sets continuousViewRef) runs as a
            // layout-phase effect, while LaunchedEffect runs as a composition-phase effect — there
            // is no guaranteed order between the two on first composition. Keying on the ref means
            // this coroutine only fires (or re-fires) once the factory has actually populated it.
            val continuousView = continuousViewRef.value
            // True only on the very first composition of this screen instance. Saved across
            // recompositions and rotation so the annotation-mark anchor is consumed exactly once —
            // it must NOT replay on rotation (where the user has been reading and `latestLocator()`
            // points elsewhere). Reading `latestLocator()` here doesn't work: `runReaderSyncCycle`
            // inside `openBook` writes `lastLocator` BEFORE this LaunchedEffect fires, so it's
            // already non-null at first composition and a takeIf guard against it strips the id.
            var focusIsFresh by rememberSaveable { mutableStateOf(true) }
            LaunchedEffect(continuousView) {
                val view = continuousView ?: return@LaunchedEffect
                val initialLocator = latestLocator() ?: state.initialLocator
                val anchor = initialLocator?.locations?.fragments?.firstOrNull()
                val rawHref = initialLocator?.href?.toString()
                    ?: chapters.firstOrNull()?.link?.href?.toString()
                    ?: return@LaunchedEffect
                val initialHref = if (anchor != null) "$rawHref#$anchor" else rawHref
                val focusId = state.initialFocusAnnotationId?.takeIf { focusIsFresh }
                focusIsFresh = false
                // Bootstrap persisted highlights for this freshly-bound view BEFORE chapters load:
                // the persisted-highlights LaunchedEffect above this block doesn't re-fire on the
                // ref-null→view transition (its keys don't include continuousViewRef.value), so on
                // a paged↔continuous orientation flip the new ContinuousHighlightRenderer would
                // otherwise never be invoked and ContinuousDecorationController's persisted
                // annotation state would stay empty — onPageFinished would skip every chapter as
                // it loaded.
                highlightRenderer.applyAnnotations(highlightRenders)
                view.initialize(
                    chapters = chapters,
                    prefs = formattingPrefs,
                    initialHref = initialHref,
                    initialProgression = initialLocator?.locations?.progression?.toFloat() ?: 0f,
                    publication = state.publication,
                    // Saved reading positions come from locatorAt, which measures progression at the
                    // viewport midpoint. The inverse — scrollYForProgression — places mid-chapter
                    // progressions back at the midpoint. Top-aligning instead drifts the view
                    // forward by half a viewport on every reopen. Annotation-tap opens still land
                    // precisely via the focusAnnotationId mark-anchor path in openWindowAt, which
                    // takes priority over the progression fallback.
                    alignToTop = false,
                    focusAnnotationId = focusId,
                )
            }
            // Drive Auto-Scroll deltas into the ContinuousReaderView's scrollBy. When the view
            // reaches the bottom of the book the delta is rejected by the NestedScrollView and we
            // dispatch ReachedEndOfBook to stop the controller silently (ADR 0037).
            LaunchedEffect(continuousView) {
                val view = continuousView ?: return@LaunchedEffect
                autoScrollDeltas.collect { delta ->
                    val before = view.scrollY
                    view.scrollBy(0, delta)
                    val maxScroll = (view.getChildAt(0)?.height ?: 0) - view.height
                    if (view.scrollY == before || view.scrollY >= maxScroll - 1) {
                        onReachedEndOfBook()
                    }
                }
            }
        }
        // Vertical (Readium scroll) mode: drive Auto-Scroll deltas via JS `window.scrollBy`
        // because Readium's scroll mode intercepts native View.scrollBy on the inner WebView
        // (the existing volume-key path uses the same JS scroll mechanism, see line ~1810).
        // At chapter end we hand off to fragment.goForward() and use a ~600ms settle window so
        // the next chapter's WebView is painted before the ticker resumes scrolling on it.
        if (formattingPrefs.orientation == ReaderOrientation.Vertical) {
            val verticalFragment = fragmentRef.value
            LaunchedEffect(verticalFragment) {
                if (verticalFragment == null) return@LaunchedEffect
                // JS evaluation is suspending inside the renderer bridge: a delta isn't collected
                // until the previous round-trip resolves, so per-delta dispatch already gets
                // natural backpressure-driven batching (no need to accumulate manually before the
                // call — the accumulator inside the controller only emits whole pixels).
                autoScrollDeltas.collect { flushed ->
                    // null = the WebView is gone mid-tick (chapter swap, fragment recreate): skip
                    // the iteration, the next delta will arrive once the fragment is back. The
                    // original direct-evaluateJavascript path used `?: return@collect` here for
                    // the same reason — must NOT treat it as "stuck at end" and fire end-of-book.
                    val moved = rendererBridge.scrollByPx(flushed) ?: return@collect
                    if (moved) return@collect
                    // Stuck at the bottom of this chapter. Vertical mode stops auto-scroll
                    // rather than auto-advancing — the user's eye is mid-viewport when scrollY
                    // hits max, so an immediate goForward would swap out ~half a viewport of
                    // unread text. The user pulls to advance, then re-taps the toggle on the
                    // next chapter when ready. Continuous mode has no chapter boundary so this
                    // limitation only applies here.
                    onReachedEndOfBook()
                }
            }
        }
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

        // Highlight actions sheet — opens when the user taps an existing highlight or immediately
        // after creating one. Floats as an overlay inside the reader so it has access to the
        // reader's BoxWithConstraints scope and sits above the reader content.
        var noteEditorTarget by remember { mutableStateOf<EpubReaderViewModel.HighlightEditTarget?>(null) }
        val editTarget = highlightToEdit
        if (editTarget != null) {
            val current = highlightRenders.firstOrNull { it.id == editTarget.id }
            HighlightActionsPopup(
                anchorRect = editTarget.anchorRect,
                selected = current?.let { HighlightColor.fromToken(it.color) },
                note = current?.note,
                onPick = { color -> onRecolorHighlight(editTarget.id, color) },
                onDelete = { onDeleteHighlight(editTarget.id) },
                onOpenNoteEditor = {
                    noteEditorTarget = editTarget
                },
                onDismiss = onDismissHighlightActions,
                noteOnly = editTarget.noteOnly,
            )
        }
        val noteTarget = noteEditorTarget
        if (noteTarget != null) {
            val current = highlightRenders.firstOrNull { it.id == noteTarget.id }
            NoteEditorDialog(
                initialNote = current?.note ?: "",
                onConfirm = { text ->
                    onUpdateHighlightNote(noteTarget.id, text.takeIf { it.isNotBlank() })
                    noteEditorTarget = null
                },
                onDismiss = { noteEditorTarget = null },
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

@Composable
private fun AudiobookPlayerOverlay(
    itemId: String,
    visible: Boolean,
    windowSizeClass: WindowSizeClass,
    onDismiss: () -> Unit,
    onSwitchToReadaloud: (Double) -> Unit,
) {
    val navController = rememberNavController()
    val encoded = URLEncoder.encode(itemId, "UTF-8")
    // Always use the PREWARM_SENTINEL (-2f) so the NavBackStackEntry — and therefore
    // AudiobookPlayerViewModel — is created once and kept alive between swipe-up/down cycles.
    // The actual start position arrives via AudiobookHandoffState when the overlay is revealed.
    val startRoute = "overlay_audiobook/$encoded?startAtSec=-2.0"

    // Slide up on show, slide down on dismiss. `overlayVisible` trails `visible` on the way out —
    // it stays true until the slide-down animation finishes, then flips to false (size 0).
    val slideProgress = remember { Animatable(0f) }
    var overlayVisible by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            overlayVisible = true
            slideProgress.snapTo(0f)
            slideProgress.animateTo(1f, tween(durationMillis = 320, easing = FastOutSlowInEasing))
        } else {
            slideProgress.animateTo(0f, tween(durationMillis = 280, easing = FastOutSlowInEasing))
            overlayVisible = false
        }
    }

    NavHost(
        navController = navController,
        startDestination = startRoute,
        // size(0.dp) keeps the composable in the tree for pre-warming but makes it invisible.
        modifier = if (overlayVisible) {
            Modifier.fillMaxSize().graphicsLayer {
                translationY = (1f - slideProgress.value) * size.height
            }
        } else {
            Modifier.size(0.dp)
        },
    ) {
        composable(
            route = "overlay_audiobook/{itemId}?startAtSec={startAtSec}",
            arguments = listOf(
                navArgument("itemId") { type = NavType.StringType },
                navArgument("startAtSec") {
                    type = NavType.FloatType
                    defaultValue = -2f
                },
            ),
        ) {
            AudiobookPlayerScreen(
                windowSizeClass = windowSizeClass,
                onNavigateBack = onDismiss,
                onSwitchToReadaloud = { _, atSec -> onSwitchToReadaloud(atSec) },
            )
        }
    }
}

/**
 * Pull `outRect.bottom` up into the WebView's window-visible display frame, but only if it
 * sticks out below. The `outRect.top` — and therefore the framework's anchor for above-placement —
 * is left untouched whenever the selection is at least partially inside the visible band, so a
 * word visible at the last row still gets a popup that sits right above it rather than jumping up
 * to the visible-frame edge. When the selection is entirely below the visible band (real device
 * with a gesture bar strip painting content the user sees but the system considers off-window),
 * both edges get pulled to the band bottom so the framework's above-placement forces the toolbar
 * back into the visible area instead of falling off-screen below.
 */
internal fun clampReaderSelectionRectBottom(view: android.view.View, outRect: android.graphics.Rect) {
    val wvdf = android.graphics.Rect()
    view.getWindowVisibleDisplayFrame(wvdf)
    val locWin = IntArray(2)
    view.getLocationInWindow(locWin)
    val (newTop, newBottom) = clampReaderSelectionRectBottomYs(
        rectTop = outRect.top,
        rectBottom = outRect.bottom,
        viewportTop = wvdf.top,
        viewportBottom = wvdf.bottom,
        viewLocationInWindowY = locWin[1],
        viewHeight = view.height,
    )
    outRect.top = newTop
    outRect.bottom = newBottom
}

/**
 * Pure y-axis logic for [clampReaderSelectionRectBottom]. Returns the new (top, bottom) pair.
 * Only pulls `rectBottom` (and, if the entire selection is below the band, also `rectTop`) into
 * the visible band; leaves both alone when the selection is already inside the band.
 */
internal fun clampReaderSelectionRectBottomYs(
    rectTop: Int,
    rectBottom: Int,
    viewportTop: Int,
    viewportBottom: Int,
    viewLocationInWindowY: Int,
    viewHeight: Int,
): Pair<Int, Int> {
    val viewportBottomLocal = (viewportBottom - viewLocationInWindowY).coerceAtMost(viewHeight)
    val viewportTopLocal = (viewportTop - viewLocationInWindowY).coerceAtLeast(0)
    if (viewportBottomLocal <= viewportTopLocal) return rectTop to rectBottom
    if (rectBottom <= viewportBottomLocal) return rectTop to rectBottom
    val newBottom = viewportBottomLocal
    val newTop = if (rectTop > viewportBottomLocal) viewportBottomLocal else rectTop
    return newTop to newBottom
}

// Returns true if the tap that just fired should be swallowed because it dismissed an active
// text-selection popup. Consumes the flag (clears it) so subsequent taps toggle immersive
// normally. Extracted for JVM unit-testing — the paged InputListener.onTap wraps around a
// Compose closure that can't be exercised without a live Readium fragment.
internal fun consumeSelectionSuppressedTap(activeAtDown: AtomicBoolean): Boolean =
    activeAtDown.getAndSet(false)

// Named class (not anonymous) so Android's addJavascriptInterface reflection can discover the
// @JavascriptInterface-annotated methods reliably across all API levels and R8 configurations.
internal class RiffleSelectionRectBridge(
    private val store: java.util.concurrent.atomic.AtomicReference<android.graphics.RectF?>,
    private val activeAtDown: java.util.concurrent.atomic.AtomicBoolean,
) {
    @android.webkit.JavascriptInterface
    fun onRect(left: Double, top: Double, right: Double, bottom: Double) {
        store.set(android.graphics.RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()))
    }

    // Called from the touchstart listener in SELECTION_SPAN_TRACKER_JS with the selection state
    // at the start of the touch (before any tap-to-dismiss has cleared it). The paged
    // InputListener.onTap handler consumes this on the same touch to decide whether the tap is
    // a selection-popup dismissal (skip immersive toggle) or a normal tap (toggle as usual).
    @android.webkit.JavascriptInterface
    fun onActiveAtDown(active: Boolean) {
        activeAtDown.set(active)
    }
}
