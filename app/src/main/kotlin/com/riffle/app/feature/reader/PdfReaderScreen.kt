@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.feature.reader

import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.riffle.app.feature.reader.formatting.toPdfiumPreferences
import com.riffle.feature.settings.ui.readersettings.TocPanel
import com.riffle.feature.settings.ui.readersettings.formatting.RenderCapabilities
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderTheme
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.reader.PdfOutlineNode
import com.riffle.feature.reader.PdfReaderState
import com.riffle.feature.reader.PdfReaderViewModel
import com.riffle.feature.reader.VolumeNavEvent
import com.riffle.feature.reader.ui.ChapterNavigationRail
import com.riffle.feature.reader.ui.ReaderTopBar
import com.riffle.feature.reader.ui.SharedAnnotationsPanel
import com.riffle.feature.reader.ui.chapterMapProgressLabelTemplates
import com.riffle.feature.reader.ui.chapterRailProgressPercent
import com.riffle.feature.reader.ui.formatTemplate
import com.riffle.feature.reader.pdfActiveHref
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.adapter.pdfium.navigator.PdfiumDefaults
import org.readium.adapter.pdfium.navigator.PdfiumEngineProvider
import org.readium.adapter.pdfium.navigator.PdfiumNavigatorFactory
import org.readium.adapter.pdfium.navigator.PdfiumNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.shared.publication.Locator
import com.riffle.feature.source.ui.CornerBookmarkIndicator

// TODO(figure-zoom): PDF figure-zoom follow-up. The EPUB reader supports single-tap-on-figure
// opening a fullscreen zoom overlay (see FigureZoomOverlay + FigureTapScript). For PDF, the
// equivalent path is: on tap, use Pdfium's FPDF_GetPageObject (via PdfiumEngineProvider's page
// object API) to hit-test image objects at the tap location, extract the pixel region as a Bitmap,
// and feed it into the same FigureZoomOverlay composable. The overlay already accepts a
// FigureZoomState with an in-memory bitmap path (via svgMarkup=null + href pointing to a temp file
// or a new data URI). Deferred from v1; do not implement without a decode-cost benchmark on a
// large-page PDF.
@Composable
fun PdfReaderScreen(
    onNavigateBack: () -> Unit,
    viewModel: PdfReaderViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    // Raw user-picked prefs — feeds the FormattingPanel chip selection (so Auto stays
    // highlighted even though the page renders in the resolved palette).
    val pickedPrefs by viewModel.formattingPreferences.collectAsState()
    // Resolved prefs — `theme` is always concrete. Feeds pdfium's mapper + the theme scrim.
    val formattingPrefs by viewModel.effectiveFormattingPreferences.collectAsState()
    val hasBookOverrides by viewModel.hasBookOverrides.collectAsState()
    // Populated by AndroidPdfRepository.loadItem(); non-null once the PDF is open.
    val pdfRepo: AndroidPdfRepository = koinInject()
    val openedState by pdfRepo.openedState.collectAsState()
    val volumeKeyNavigationEnabled by viewModel.volumeKeyNavigationEnabled.collectAsState()
    val invertVolumeKeys by viewModel.invertVolumeKeys.collectAsState()
    var showFormattingPanel by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val immersiveState = rememberImmersiveModeState()

    // Error state never loads a PDF view, so immersive's auto-hide path is never reached.
    // Force-show system bars + TopAppBar so the Back button is reachable.
    LaunchedEffect(state) {
        if (state is PdfReaderState.Error || state is PdfReaderState.BookNotFound) immersiveState.show()
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

    val syncProgressError = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_could_not_sync_reading_progress)

    LaunchedEffect(viewModel) {
        viewModel.syncErrorEvents.collect {
            Toast.makeText(context, syncProgressError, Toast.LENGTH_SHORT).show()
        }
    }

    val title = (state as? PdfReaderState.Ready)?.title
        ?: (state as? PdfReaderState.AwaitingNavigator)?.title
        ?: ""
    val tocVisible by viewModel.tocVisible.collectAsState()
    val annotationsPanelVisible by viewModel.annotationsPanelVisible.collectAsState()
    val toc by viewModel.toc.collectAsState()
    val annotations by viewModel.annotations.collectAsState()
    val currentPageBookmarked by viewModel.currentPageBookmarked.collectAsState()
    val currentPage by viewModel.currentPage.collectAsState()
    val annotationsAvailable = state is PdfReaderState.Ready

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
                PdfReaderState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .testTag(TestTags.READER_LOADING),
                    )
                }
                is PdfReaderState.AwaitingNavigator, is PdfReaderState.Ready -> {
                    val opened = openedState
                    if (opened == null) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .testTag(TestTags.READER_LOADING),
                        )
                    } else {
                        val awaitingPage = (s as? PdfReaderState.AwaitingNavigator)?.initialPage ?: 0
                        PdfNavigatorView(
                            publication = opened.publication,
                            initialPage = awaitingPage,
                            totalPages = opened.totalPages,
                            outline = opened.outline,
                            currentPage = currentPage,
                            prefs = formattingPrefs,
                            onPageChanged = { page0Based, pages, progression ->
                                immersiveState.dismissOverlay()
                                viewModel.onPageChanged(page0Based, pages, progression)
                            },
                            onTap = immersiveState::toggle,
                            onNavigatorReady = viewModel::onNavigatorReady,
                            serverPageEvents = viewModel.serverPageEvents,
                            volumeNavEvents = viewModel.volumeNavEvents,
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag(TestTags.READER_READY)
                                .semantics {
                                    contentDescription = buildString {
                                        append(currentPage?.let { "page:$it" } ?: "")
                                        append(" wake-lock:")
                                        append(if (keepScreenOn) "on" else "off")
                                    }
                                },
                        )

                        if (tocVisible && state is PdfReaderState.Ready) {
                            TocPanel(
                                entries = toc,
                                activeHref = currentPage?.let { pdfActiveHref(it) },
                                onEntryClick = { entry ->
                                    viewModel.navigateToEntry(entry)
                                    immersiveState.hide()
                                },
                                onDismiss = viewModel::closeToc,
                            )
                        }

                        if (annotationsPanelVisible && state is PdfReaderState.Ready) {
                            SharedAnnotationsPanel(
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
                }
                is PdfReaderState.Error -> {
                    Text(
                        s.message,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .testTag(TestTags.READER_ERROR_STATE),
                    )
                }
                PdfReaderState.BookNotFound -> {
                    Text(
                        "Book not found",
                        modifier = Modifier
                            .align(Alignment.Center)
                            .testTag(TestTags.READER_ERROR_STATE),
                    )
                }
            }
        }

        ReaderTopBar(
            visible = !immersiveState.isImmersive,
            title = title,
            isReady = state is PdfReaderState.Ready,
            onBack = onNavigateBack,
            onSearch = {},
            onToc = viewModel::openToc,
            onAnnotations = viewModel::openAnnotationsPanel,
            onFormat = { showFormattingPanel = true },
            extraActions = {},
        )

        if (showFormattingPanel) {
            ReaderSettingsSheet(
                prefs = pickedPrefs,
                capabilities = RenderCapabilities.PDF,
                hasBookOverrides = hasBookOverrides,
                onPrefsChange = { viewModel.updateFormatting(it) },
                onReset = { viewModel.resetToGlobalDefaults() },
                onDismiss = { showFormattingPanel = false },
            )
        }

        // Chapter navigation rail along the bottom of the reader. Reuses the EPUB rail
        // visual — same Composable, fed by the PDF-side rail-segment generator. Anchored
        // to the absolute screen bottom so the system nav bar overlays it without shifting
        // it up; this keeps the rail stationary as immersive mode toggles (matches EPUB).
        if (state is PdfReaderState.Ready && formattingPrefs.showChapterMap) {
            PdfChapterRailOverlay(
                viewModel = viewModel,
                coloredChapterMap = formattingPrefs.coloredChapterMap,
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
    coloredChapterMap: Boolean,
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
    val templates = chapterMapProgressLabelTemplates()
    ChapterNavigationRail(
        segments = railSegments,
        activeIndex = activeRailSegmentIndex,
        cursorPosition = cursorPosition,
        // PDF reader doesn't have its own theme picker yet (deferred to follow-up spec);
        // use Light as a neutral default that matches the typical PDF page background.
        readerTheme = ReaderTheme.Light,
        railContentDescription = formatTemplate(
            templates.activeRailSegmentProgress,
            railSegments.getOrNull(activeRailSegmentIndex)?.title.orEmpty(),
            chapterRailProgressPercent(cursorPosition),
        ),
        onSegmentClick = viewModel::navigateToSegment,
        coloredChapterMap = coloredChapterMap,
        modifier = modifier,
    )
}

@Composable
private fun PdfNavigatorView(
    publication: Publication,
    initialPage: Int,
    totalPages: Int,
    outline: List<PdfOutlineNode>,
    currentPage: Int?,
    prefs: FormattingPreferences,
    onPageChanged: (page0Based: Int, totalPages: Int, progression: Float) -> Unit,
    onTap: () -> Unit,
    onNavigatorReady: (totalPages: Int, outline: List<PdfOutlineNode>) -> Unit,
    serverPageEvents: Flow<Int>,
    volumeNavEvents: Flow<VolumeNavEvent>,
    modifier: Modifier = Modifier,
) = key(prefs.margins) {
    // Only margins actually reaches PdfiumPreferences (via pageSpacing); the mapper hardcodes
    // vertical/width so orientation is intentionally excluded from the recreation key.
    PdfNavigatorViewContent(
        publication = publication,
        initialPage = initialPage,
        totalPages = totalPages,
        outline = outline,
        currentPage = currentPage,
        pdfiumPreferences = prefs.toPdfiumPreferences(),
        onPageChanged = onPageChanged,
        onTap = onTap,
        onNavigatorReady = onNavigatorReady,
        serverPageEvents = serverPageEvents,
        volumeNavEvents = volumeNavEvents,
        modifier = modifier,
    )
}

// Keyed on orientation + margins by the caller ([PdfNavigatorView]) so a change to either
// remounts this composable from scratch — the only way to feed pdfium a new PdfiumPreferences,
// since the fragment factory bakes them in at construction (same limitation EPUB has for
// double-page toggles). Other prefs (theme, keepScreenOn, volume keys) do NOT need this.
@Composable
private fun PdfNavigatorViewContent(
    publication: Publication,
    initialPage: Int,
    totalPages: Int,
    outline: List<PdfOutlineNode>,
    currentPage: Int?,
    pdfiumPreferences: org.readium.adapter.pdfium.navigator.PdfiumPreferences,
    onPageChanged: (page0Based: Int, totalPages: Int, progression: Float) -> Unit,
    onTap: () -> Unit,
    onNavigatorReady: (totalPages: Int, outline: List<PdfOutlineNode>) -> Unit,
    serverPageEvents: Flow<Int>,
    volumeNavEvents: Flow<VolumeNavEvent>,
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

    val currentPageRef by rememberUpdatedState(currentPage)
    val totalPagesRef by rememberUpdatedState(totalPages)

    LaunchedEffect(serverPageEvents) {
        serverPageEvents.collect { page0Based ->
            val locator = page0BasedToLocator(publication, page0Based, totalPagesRef) ?: return@collect
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
        update = { _ ->
            val fm = fragmentActivity.supportFragmentManager
            // After Activity recreation, the FragmentManager may have restored a
            // PdfiumNavigatorFragment using the default factory (not PdfiumNavigatorFactory).
            // Without the factory, the fragment cannot connect to the Readium streaming server.
            // Remove any such stale fragment so the creation path below can recreate it
            // properly with the resume locator as the initial position.
            if (fragmentRef.value == null) {
                fm.findFragmentById(containerId)?.let { stale ->
                    fm.beginTransaction().remove(stale).commitNowAllowingStateLoss()
                }
            }

            if (fm.findFragmentById(containerId) == null) {
                val resumeLocator = currentPageRef
                    ?.let { page0BasedToLocator(publication, it, totalPagesRef) }
                    ?: page0BasedToLocator(publication, initialPage, totalPages)
                val fragmentFactory = PdfiumNavigatorFactory(
                    publication = publication,
                    pdfEngineProvider = PdfiumEngineProvider(defaults = PdfiumDefaults(scroll = true)),
                ).createFragmentFactory(
                    initialLocator = resumeLocator,
                    initialPreferences = pdfiumPreferences,
                )
                fm.fragmentFactory = fragmentFactory
                fm.beginTransaction()
                    .add(containerId, PdfiumNavigatorFragment::class.java, null)
                    .commitNowAllowingStateLoss()
                @Suppress("UNCHECKED_CAST")
                val fragment = fm.findFragmentById(containerId) as? PdfiumNavigatorFragment
                    ?: return@AndroidView
                fragmentRef.value = fragment
                onNavigatorReady(totalPages, outline)
                // Page advancement is via drag (PDFView's native swipe-page-turn),
                // matching paginated EPUB. Edge-tap navigation is intentionally
                // not wired — taps only toggle the reader chrome.
                fragment.addInputListener(tapListener)
                coroutineScope.launch {
                    fragment.currentLocator.collect { locator ->
                        val position1Based = locator.locations.position ?: return@collect
                        val page0Based = (position1Based - 1).coerceAtLeast(0)
                        val progression = locator.locations.progression?.toFloat() ?: 0f
                        onPageChanged(page0Based, totalPagesRef, progression)
                    }
                }
            }
        },
        // Apply the user's margin as horizontal padding around the fragment. Pdfium honors
        // Fit.WIDTH by rescaling pages to the container's inner width, so shrinking the
        // container narrows the pages visibly — this is the observable side of the "margins"
        // slider (pageSpacing alone controls the between-page gap, which pdfium may or may
        // not honor at runtime and is only visible when scrolling past a page boundary).
        // pageSpacing == null means "at default; no override" — apply no padding either.
        modifier = modifier.padding(horizontal = ((pdfiumPreferences.pageSpacing ?: 0.0).coerceAtLeast(0.0) / 2.0).toFloat().dp),
    )
}

private fun page0BasedToLocator(publication: Publication, page0Based: Int, totalPages: Int): Locator? {
    val href = publication.readingOrder.firstOrNull()?.url() ?: return null
    val progression = if (totalPages > 0) page0Based.toDouble() / totalPages else 0.0
    return Locator(
        href = href,
        mediaType = MediaType.PDF,
        locations = Locator.Locations(
            position = page0Based + 1,
            progression = progression,
        ),
    )
}
