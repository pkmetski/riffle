@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.feature.reader

import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.riffle.app.feature.reader.formatting.RenderCapabilities
import com.riffle.app.feature.reader.formatting.toPdfiumPreferences
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.readium.adapter.pdfium.navigator.PdfiumEngineProvider
import org.readium.adapter.pdfium.navigator.PdfiumNavigatorFactory
import org.readium.adapter.pdfium.navigator.PdfiumNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.shared.publication.Locator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderScreen(
    onNavigateBack: () -> Unit,
    viewModel: PdfReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    // Raw user-picked prefs — feeds the FormattingPanel chip selection (so Auto stays
    // highlighted even though the page renders in the resolved palette).
    val pickedPrefs by viewModel.formattingPreferences.collectAsState()
    // Resolved prefs — `theme` is always concrete. Feeds pdfium's mapper + the theme scrim.
    val formattingPrefs by viewModel.effectiveFormattingPreferences.collectAsState()
    // False until the loaded prefs have propagated to effectiveFormattingPreferences. Gates
    // fragment construction so first paint never bakes in the StateFlow's default preferences
    // (mirrors EpubReaderScreen's formattingPreferencesReady gate).
    val formattingPreferencesReady by viewModel.formattingPreferencesReady.collectAsState()
    val hasBookOverrides by viewModel.hasBookOverrides.collectAsState()
    val volumeKeyNavigationEnabled by viewModel.volumeKeyNavigationEnabled.collectAsState()
    val invertVolumeKeys by viewModel.invertVolumeKeys.collectAsState()
    var showFormattingPanel by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val immersiveState = rememberImmersiveModeState()

    // Error state never loads a PDF view, so immersive's auto-hide path is never reached.
    // Force-show system bars + TopAppBar so the Back button is reachable.
    LaunchedEffect(state) {
        if (state is ReaderState.Error) immersiveState.show()
    }

    DisposableEffect(viewModel) {
        onDispose { viewModel.onReaderClosed() }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.onReaderClosed()
                Lifecycle.Event.ON_START -> viewModel.onReaderResumed()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
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

    LaunchedEffect(viewModel) {
        viewModel.syncErrorEvents.collect {
            Toast.makeText(context, "Could not sync reading progress", Toast.LENGTH_SHORT).show()
        }
    }

    val title = (state as? ReaderState.Ready)?.title ?: ""
    val tocVisible by viewModel.tocVisible.collectAsState()
    val annotationsPanelVisible by viewModel.annotationsPanelVisible.collectAsState()
    val toc by viewModel.toc.collectAsState()
    val annotations by viewModel.annotations.collectAsState()
    val currentPageBookmarked by viewModel.currentPageBookmarked.collectAsState()
    val currentPage by viewModel.currentPage.collectAsState()
    val annotationsAvailable = state is ReaderState.Ready

    // TopAppBar floats as an overlay so its show/hide never resizes the PDF view —
    // same pattern as EpubReaderScreen.
    Box(modifier = Modifier.fillMaxSize()) {
        // Reader content is edge-to-edge: status-bar insets are consumed at the AndroidView
        // root (see ViewCompat.setOnApplyWindowInsetsListener in the PDF AndroidView factory)
        // and the nav-bar inset is intentionally NOT applied, so the PDF view keeps the same
        // height whether bars are visible or hidden. The floating TopAppBar carries its own
        // TopAppBarDefaults.windowInsets to position itself below the status bar; the system
        // nav bar overlays the bottom of the PDF view without reflowing it. See ADR 0017.
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            when (val s = state) {
                ReaderState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .testTag("reader_loading"),
                    )
                }
                // Prefs haven't propagated to effectiveFormattingPreferences yet — keep the
                // spinner up rather than constructing the pdfium fragment with the StateFlow's
                // FormattingPreferences() default (mirrors EpubReaderScreen's equivalent gate).
                is ReaderState.Ready -> if (!formattingPreferencesReady) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .testTag("reader_loading"),
                    )
                } else {
                    // Theme scrim sits behind the pdfium fragment — reuses the same palette
                    // EpubReaderScreen derives its background from (ReaderTheme.palette).
                    Box(modifier = Modifier.fillMaxSize().background(formattingPrefs.theme.palette.background)) {
                        PdfNavigatorView(
                            state = s,
                            prefs = formattingPrefs,
                            onPageChanged = { locator ->
                                immersiveState.dismissOverlay()
                                viewModel.onPageChanged(locator)
                            },
                            onTap = immersiveState::toggle,
                            serverLocatorEvents = viewModel.serverLocatorEvents,
                            volumeNavEvents = viewModel.volumeNavEvents,
                            latestLocator = { viewModel.latestLocator },
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("reader_ready")
                                .semantics {
                                    contentDescription = buildString {
                                        append(currentPage?.let { "page:$it" } ?: "")
                                        append(" wake-lock:")
                                        append(if (keepScreenOn) "on" else "off")
                                    }
                                },
                        )
                    }

                    if (tocVisible) {
                        TocPanel(
                            entries = toc,
                            activeHref = currentPage?.let { pdfActiveHref((it - 1).coerceAtLeast(0)) },
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

                    // Corner bookmark ribbon. graphicsLayer{} forces it into its own GPU
                    // layer so it composites above the PDF view's SurfaceView/TextureView
                    // — Compose Box child order alone wouldn't otherwise beat the native
                    // view's hardware-accelerated draw.
                    CornerBookmarkIndicator(
                        isBookmarked = currentPageBookmarked,
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

        AnimatedVisibility(
            visible = !immersiveState.isImmersive,
            enter = slideInVertically(initialOffsetY = { -it }) + expandVertically(expandFrom = Alignment.Top),
            exit = slideOutVertically(targetOffsetY = { -it }) + shrinkVertically(shrinkTowards = Alignment.Top),
        ) {
            TopAppBar(
                title = { AutoResizeText(title, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state is ReaderState.Ready) {
                        IconButton(
                            onClick = viewModel::openToc,
                            modifier = Modifier.testTag("pdf_open_toc"),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Table of Contents")
                        }
                        IconButton(
                            onClick = viewModel::openAnnotationsPanel,
                            modifier = Modifier.testTag("pdf_open_annotations"),
                        ) {
                            Icon(Icons.Filled.Bookmarks, contentDescription = "Annotations")
                        }
                        IconButton(
                            onClick = { showFormattingPanel = true },
                            modifier = Modifier.testTag("pdf_open_formatting"),
                        ) {
                            Text(
                                "Aa",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.semantics { contentDescription = "Format" },
                            )
                        }
                    }
                },
                colors = readerTopAppBarColors(),
            )
        }

        if (showFormattingPanel) {
            ReaderSettingsSheet(
                prefs = pickedPrefs,
                capabilities = RenderCapabilities.PDF,
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

        // Chapter navigation rail along the bottom of the reader. Reuses the EPUB rail
        // visual — same Composable, fed by the PDF-side rail-segment generator. Anchored
        // to the absolute screen bottom so the system nav bar overlays it without shifting
        // it up; this keeps the rail stationary as immersive mode toggles (matches EPUB).
        if (state is ReaderState.Ready) {
            PdfChapterRailOverlay(
                viewModel = viewModel,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PdfChapterRailOverlay(
    viewModel: PdfReaderViewModel,
    modifier: Modifier = Modifier,
) {
    val railSegments by viewModel.railSegments.collectAsState()
    val activeRailSegmentIndex by viewModel.activeRailSegmentIndex.collectAsState()
    val cursorPosition by viewModel.railCursorPosition.collectAsState()
    android.util.Log.v(
        "RifflePdfRail",
        "PdfChapterRailOverlay segments=${railSegments.size} " +
            "active=$activeRailSegmentIndex cursor=$cursorPosition",
    )
    if (railSegments.isEmpty()) return
    ChapterNavigationRail(
        segments = railSegments,
        activeIndex = activeRailSegmentIndex,
        cursorPosition = cursorPosition,
        // PDF reader doesn't have its own theme picker yet (deferred to follow-up spec);
        // use Light as a neutral default that matches the typical PDF page background.
        readerTheme = ReaderTheme.Light,
        onSegmentClick = viewModel::navigateToSegment,
        modifier = modifier,
    )
}

@Composable
private fun PdfNavigatorView(
    state: ReaderState.Ready,
    prefs: FormattingPreferences,
    onPageChanged: (Locator) -> Unit,
    onTap: () -> Unit,
    serverLocatorEvents: Flow<Locator>,
    volumeNavEvents: Flow<VolumeNavEvent>,
    latestLocator: () -> Locator?,
    modifier: Modifier = Modifier,
) = key(prefs.orientation, prefs.margins) {
    PdfNavigatorViewContent(
        state = state,
        pdfiumPreferences = prefs.toPdfiumPreferences(),
        onPageChanged = onPageChanged,
        onTap = onTap,
        serverLocatorEvents = serverLocatorEvents,
        volumeNavEvents = volumeNavEvents,
        latestLocator = latestLocator,
        modifier = modifier,
    )
}

// Keyed on orientation + margins by the caller ([PdfNavigatorView]) so a change to either
// remounts this composable from scratch — the only way to feed pdfium a new PdfiumPreferences,
// since the fragment factory bakes them in at construction (same limitation EPUB has for
// double-page toggles). Other prefs (theme, keepScreenOn, volume keys) do NOT need this.
@Composable
private fun PdfNavigatorViewContent(
    state: ReaderState.Ready,
    pdfiumPreferences: org.readium.adapter.pdfium.navigator.PdfiumPreferences,
    onPageChanged: (Locator) -> Unit,
    onTap: () -> Unit,
    serverLocatorEvents: Flow<Locator>,
    volumeNavEvents: Flow<VolumeNavEvent>,
    latestLocator: () -> Locator?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val fragmentActivity = context as? FragmentActivity ?: return
    val coroutineScope = rememberCoroutineScope()
    val fragmentRef = remember { mutableStateOf<PdfiumNavigatorFragment?>(null) }

    // rememberUpdatedState ensures the listener always calls the latest onTap lambda
    // without needing to be re-created when onTap changes.
    val currentOnTap by rememberUpdatedState(onTap)
    val tapListener = remember {
        object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                android.util.Log.i("RifflePdfSel", "Readium InputListener.onTap fired at $event")
                currentOnTap()
                return false
            }
        }
    }

    LaunchedEffect(serverLocatorEvents) {
        serverLocatorEvents.collect { locator ->
            fragmentRef.value?.go(locator)
        }
    }

    LaunchedEffect(volumeNavEvents) {
        volumeNavEvents.collect { event ->
            when (event) {
                VolumeNavEvent.Forward -> fragmentRef.value?.goForward(animated = false)
                VolumeNavEvent.Backward -> fragmentRef.value?.goBackward(animated = false)
            }
        }
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

    AndroidView(
        factory = { ctx ->
            FragmentContainerView(ctx).apply {
                id = containerId
                // Compose handles all inset-based padding. Consuming insets here prevents
                // Readium's PDF views from applying status-bar padding on physical devices.
                ViewCompat.setOnApplyWindowInsetsListener(this) { _, _ ->
                    WindowInsetsCompat.CONSUMED
                }
            }
        },
        update = { containerView ->
            val fm = fragmentActivity.supportFragmentManager
            // After Activity recreation, the FragmentManager may have restored a
            // PdfiumNavigatorFragment using the default factory (not PdfiumNavigatorFactory).
            // Without the factory, the fragment cannot connect to the Readium streaming server.
            // Remove any such stale fragment so the creation path below can recreate it
            // properly with latestLocator() as the initial position.
            if (fragmentRef.value == null) {
                fm.findFragmentById(containerId)?.let { stale ->
                    fm.beginTransaction().remove(stale).commitNow()
                }
            }

            if (fm.findFragmentById(containerId) == null) {
                val fragmentFactory = PdfiumNavigatorFactory(
                    publication = state.publication,
                    pdfEngineProvider = PdfiumEngineProvider(),
                ).createFragmentFactory(
                    initialLocator = latestLocator() ?: state.initialLocator,
                    initialPreferences = pdfiumPreferences,
                )
                fm.fragmentFactory = fragmentFactory
                fm.beginTransaction()
                    .add(containerId, PdfiumNavigatorFragment::class.java, null)
                    .commitNow()
                @Suppress("UNCHECKED_CAST")
                val fragment = fm.findFragmentById(containerId) as? PdfiumNavigatorFragment
                    ?: return@AndroidView
                fragmentRef.value = fragment
                // Page advancement is via drag (PDFView's native swipe-page-turn),
                // matching paginated EPUB. Edge-tap navigation is intentionally
                // not wired — taps only toggle the reader chrome.
                fragment.addInputListener(tapListener)
                coroutineScope.launch {
                    fragment.currentLocator.collect { locator -> onPageChanged(locator) }
                }
            }
        },
        modifier = modifier,
    )
}
