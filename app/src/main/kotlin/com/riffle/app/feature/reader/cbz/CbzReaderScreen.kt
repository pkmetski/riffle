package com.riffle.app.feature.reader.cbz

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.WindowManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.graphics.asImageBitmap
import com.riffle.app.feature.reader.ChapterMapOverlay
import com.riffle.app.feature.reader.VolumeNavEvent
import com.riffle.app.feature.reader.cbzSegmentPageIndex
import com.riffle.app.feature.readersettings.palette
import com.riffle.app.feature.reader.readerThemeLabelColor
import com.riffle.app.feature.reader.rememberImmersiveModeState
import com.riffle.core.data.comic.panel.PanelMaskEncoder
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.comic.panel.PagePanels
import com.riffle.core.domain.comic.panel.PanelBinaryMask
import com.riffle.core.domain.comic.panel.PanelFitTransform
import com.riffle.core.domain.comic.panel.PanelSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CbzReaderScreen(
    onNavigateBack: () -> Unit,
    viewModel: CbzReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val currentPage by viewModel.currentPage.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val panelViewOn by viewModel.panelViewOn.collectAsState()
    val effectivePanels by viewModel.effectivePanels.collectAsState()
    val currentPanelIndex by viewModel.currentPanelIndex.collectAsState()
    val effectiveComicFormatting by viewModel.effectiveComicFormatting.collectAsState()
    val comicBackgroundTheme by viewModel.comicBackgroundTheme.collectAsState()
    val railSegments by viewModel.railSegments.collectAsState()
    val activeRailSegmentIndex by viewModel.activeRailSegmentIndex.collectAsState()
    val railCursorPosition by viewModel.railCursorPosition.collectAsState()
    val hasComicOverrides by viewModel.hasComicOverrides.collectAsState()
    val developerModeEnabled by viewModel.developerModeEnabled.collectAsState()
    var formattingSheetOpen by remember { mutableStateOf(false) }
    var reportSheetOpen by remember { mutableStateOf(false) }
    var reportData by remember { mutableStateOf<Pair<PanelBinaryMask, ByteArray>?>(null) }
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val immersiveState = rememberImmersiveModeState()

    LaunchedEffect(state) {
        if (state is CbzReaderState.Error) immersiveState.show()
    }

    DisposableEffect(viewModel) {
        onDispose { viewModel.onReaderClosed() }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onReaderResumed()
                Lifecycle.Event.ON_STOP -> viewModel.onReaderClosed()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    DisposableEffect(keepScreenOn) {
        val window = (context as? FragmentActivity)?.window
        if (keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    Box(modifier = Modifier.fillMaxSize().background(comicBackgroundTheme.palette.background)) {
        when (val s = state) {
            CbzReaderState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is CbzReaderState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(s.message, color = MaterialTheme.colorScheme.onSurface)
            }
            is CbzReaderState.Ready -> {
                if (panelViewOn) {
                    CbzPanelViewer(
                        state = s,
                        currentPage = currentPage,
                        pagePanels = effectivePanels,
                        panelIndex = currentPanelIndex,
                        panelAnimationSpeedMs = effectiveComicFormatting.panelAnimationSpeedMs,
                        onNextPanel = viewModel::nextPanel,
                        onPrevPanel = viewModel::previousPanel,
                        onSkipGuidedPage = viewModel::skipGuidedPanelsOnPage,
                        onToggleImmersive = immersiveState::toggle,
                        volumeNavEvents = viewModel.volumeNavEvents,
                        onViewportSizeChanged = viewModel::setViewportSize,
                    )
                } else {
                    CbzPager(
                        state = s,
                        currentPage = currentPage,
                        onPageChanged = { viewModel.jumpToPage(it) },
                        onToggleImmersive = immersiveState::toggle,
                        volumeNavEvents = viewModel.volumeNavEvents,
                        onNext = viewModel::nextPage,
                        onPrev = viewModel::previousPage,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = !immersiveState.isImmersive,
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
        ) {
            TopAppBar(
                title = {
                    val title = (state as? CbzReaderState.Ready)?.title.orEmpty()
                    Text(title, maxLines = 1)
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_back))
                    }
                },
                actions = {
                    if (state is CbzReaderState.Ready) {
                        IconButton(onClick = { formattingSheetOpen = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Tune,
                                contentDescription = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_comic_formatting),
                            )
                        }
                        if (developerModeEnabled) {
                            var menuOpen by remember { mutableStateOf(false) }
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_more_options))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_report_panel_detection_issue)) },
                                    onClick = {
                                        menuOpen = false
                                        coroutineScope.launch(Dispatchers.IO) {
                                            val result = viewModel.generateMaskPng(currentPage)
                                            if (result != null) {
                                                reportData = result
                                                reportSheetOpen = true
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                },
            )
        }

        val ready = state as? CbzReaderState.Ready
        if (ready != null) {
            var chapterMapContentPx by remember { mutableStateOf(0) }
            val density = LocalDensity.current
            val effectiveThumbnailSource = ready.thumbnailSource ?: ready.imageSource
            // Cache scoped to the book session — survives immersive mode toggles so thumbnails
            // that were decoded before entering immersive are served instantly on re-exit.
            val thumbnailCache = remember(effectiveThumbnailSource) { LruCache<Int, Bitmap>(50) }

            // Pre-warm the cache ~2 s after the comic opens so the initial page render
            // claims the full IO budget first. Radiates outward from the current reading
            // position so the pages the user is most likely to scroll to are cached first.
            // Stops once the cache is full — loading beyond capacity evicts earlier entries
            // and leaves the reading neighbourhood uncached.
            // Keyed only on effectiveThumbnailSource — runs once per book, not per page turn.
            LaunchedEffect(effectiveThumbnailSource) {
                delay(2_000)
                val startPage = currentPage
                val source = effectiveThumbnailSource
                val pageCount = ready.pageCount
                withContext(Dispatchers.IO) {
                    prewarmThumbnailCache(startPage, pageCount, thumbnailCache) { index ->
                        runCatching { decodeSampledBitmap(source, index, MAX_THUMB_DIMENSION) }.getOrNull()
                    }
                }
            }

            // Thumbnail strip — animated, sits above the chapter map
            AnimatedVisibility(
                visible = !immersiveState.isImmersive,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = with(density) { chapterMapContentPx.toDp() }),
                ) {
                    CbzThumbnailStrip(
                        currentPage = currentPage,
                        pageCount = ready.pageCount,
                        imageSource = effectiveThumbnailSource,
                        thumbnailCache = thumbnailCache,
                        onSeek = { viewModel.jumpToPage(it) },
                    )
                }
            }

            // Chapter map — static, always at bottom, never animated.
            // No navigationBarsPadding: the system nav bar overlays this column without
            // shifting it up, matching how EpubReaderScreen anchors its chapter rail.
            if (effectiveComicFormatting.showChapterMap && railSegments.isNotEmpty()) {
                val labelColor = readerThemeLabelColor(ReaderTheme.Dark)
                val labelStyle = MaterialTheme.typography.labelSmall
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { chapterMapContentPx = it.height },
                    ) {
                        if (effectiveComicFormatting.showPageProgress) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(ReaderTheme.Dark.palette.background)
                                    .padding(horizontal = 14.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = "${currentPage + 1}",
                                    style = labelStyle,
                                    color = labelColor,
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                Text(
                                    text = "-${ready.pageCount - currentPage - 1}",
                                    style = labelStyle,
                                    color = labelColor,
                                )
                            }
                        }
                        ChapterMapOverlay(
                            segments = railSegments,
                            activeIndex = activeRailSegmentIndex,
                            cursorPosition = railCursorPosition,
                            totalProgress = railCursorPosition,
                            readerTheme = ReaderTheme.Dark,
                            showRail = true,
                            coloredChapterMap = true,
                            showCurrentChapterLabel = false,
                            showProgressLabels = false,
                            showReadingTimeEstimate = false,
                            onSegmentClick = { segment -> viewModel.jumpToPage(cbzSegmentPageIndex(segment)) },
                        )
                    }
                }
            }
        }
    }

    if (formattingSheetOpen) {
        ComicFormattingSheet(
            formatting = effectiveComicFormatting,
            hasBookOverrides = hasComicOverrides,
            onUpdate = viewModel::updateComicFormatting,
            onReset = viewModel::resetComicFormattingToDefaults,
            onDismiss = { formattingSheetOpen = false },
        )
    }

    val data = reportData
    if (reportSheetOpen && data != null) {
        val (mask, maskPng) = data
        val selectFailureTypeMessage = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.error_select_failure_type)
        val maskBitmap = remember(mask) {
            val pixels = PanelMaskEncoder.toArgbPixels(mask)
            android.graphics.Bitmap.createBitmap(pixels, mask.width, mask.height, android.graphics.Bitmap.Config.ARGB_8888)
                .asImageBitmap()
        }
        // Report the DETECTOR's raw output (currentPagePanels), never effectivePanels: the
        // latter is post-PanelOverflowTransform, so with Panel Overflow = SPLIT/SMART_SPLIT the
        // report would list viewport-dependent split halves the detector never produced, and a
        // regression test written from that issue would chase a nonexistent detection bug.
        val rawPanels = viewModel.currentPagePanels.collectAsState().value
        val panelReportVm = remember(currentPage, selectFailureTypeMessage) {
            PanelReportViewModel(
                bookId = viewModel.bookId,
                pageIndex = currentPage,
                // Use original image dimensions from the detected panels, not the mask bitmap
                // dimensions — the mask may be decoded at a different DPI-scaled size.
                imageWidth = rawPanels?.imageWidth ?: mask.width,
                imageHeight = rawPanels?.imageHeight ?: mask.height,
                detectedPanels = rawPanels?.panels ?: emptyList(),
                detectedSource = rawPanels?.source ?: PanelSource.Fallback,
                repository = viewModel.panelReportRepository,
                selectFailureTypeMessage = selectFailureTypeMessage,
            )
        }
        PanelReportSheet(
            viewModel = panelReportVm,
            mask = mask,
            maskBitmap = maskBitmap,
            onSubmit = { panelReportVm.submit(maskPng) },
            onDismiss = { reportSheetOpen = false },
        )

    }
}

// --- Whole-page pager (Panel View OFF) ---

@Composable
private fun CbzPager(
    state: CbzReaderState.Ready,
    currentPage: Int,
    onPageChanged: (Int) -> Unit,
    onToggleImmersive: () -> Unit,
    volumeNavEvents: kotlinx.coroutines.flow.SharedFlow<VolumeNavEvent>,
    onNext: () -> Unit,
    onPrev: () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = currentPage) { state.pageCount }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != currentPage) onPageChanged(pagerState.currentPage)
    }
    LaunchedEffect(currentPage) {
        if (currentPage != pagerState.currentPage) {
            pagerState.scrollToPage(currentPage)
        }
    }

    LaunchedEffect(volumeNavEvents) {
        volumeNavEvents.collect { event ->
            when (event) {
                VolumeNavEvent.Forward -> onNext()
                VolumeNavEvent.Backward -> onPrev()
            }
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize().testTag("cbz_pager"),
    ) { pageIndex ->
        CbzPage(
            source = state.imageSource,
            pageIndex = pageIndex,
            onTapZone = { zone ->
                when (zone) {
                    TapZone.Left -> scope.launch { pagerState.animateScrollToPage((pageIndex - 1).coerceAtLeast(0)) }
                    TapZone.Right -> scope.launch { pagerState.animateScrollToPage((pageIndex + 1).coerceAtMost(state.pageCount - 1)) }
                    TapZone.Center -> onToggleImmersive()
                }
            },
        )
    }
}

@Composable
private fun CbzPage(
    source: CbzImageSource,
    pageIndex: Int,
    onTapZone: (TapZone) -> Unit,
) {
    var scale by remember(pageIndex) { mutableStateOf(1f) }
    var offsetX by remember(pageIndex) { mutableStateOf(0f) }
    var offsetY by remember(pageIndex) { mutableStateOf(0f) }
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = pageIndex, key2 = source) {
        value = withContext(Dispatchers.IO) {
            runCatching { decodeSampledBitmap(source, pageIndex, MAX_PAGE_DIMENSION) }.getOrNull()
        }
    }
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(pageIndex) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = 1f
                        offsetX = 0f
                        offsetY = 0f
                    },
                    onTap = { pos ->
                        val third = size.width / 3f
                        val zone = when {
                            pos.x < third -> TapZone.Left
                            pos.x > 2 * third -> TapZone.Right
                            else -> TapZone.Center
                        }
                        onTapZone(zone)
                    },
                )
            }
            .pointerInput(pageIndex) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pointerCount = event.changes.count { it.pressed }
                        when (cbzPageGestureAction(pointerCount, scale)) {
                            CbzPageGestureAction.Zoom -> {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                if (scale > 1f) {
                                    offsetX += pan.x
                                    offsetY += pan.y
                                } else {
                                    offsetX = 0f
                                    offsetY = 0f
                                }
                                event.changes.forEach { it.consume() }
                            }
                            CbzPageGestureAction.PanZoomed -> {
                                val pan = event.calculatePan()
                                offsetX += pan.x
                                offsetY += pan.y
                                event.changes.forEach { it.consume() }
                            }
                            CbzPageGestureAction.Ignore -> Unit
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(bitmap)
                .crossfade(false)
                .build(),
            contentDescription = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_comic_page_number, pageIndex + 1),
            loading = { CircularProgressIndicator() },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                ),
        )
    }
}

// --- Panel View (ADR 0055) ---

@Composable
private fun CbzPanelViewer(
    state: CbzReaderState.Ready,
    currentPage: Int,
    pagePanels: PagePanels?,
    panelIndex: Int,
    panelAnimationSpeedMs: Int,
    onNextPanel: () -> Unit,
    onPrevPanel: () -> Unit,
    onSkipGuidedPage: () -> Unit,
    onToggleImmersive: () -> Unit,
    volumeNavEvents: kotlinx.coroutines.flow.SharedFlow<VolumeNavEvent>,
    onViewportSizeChanged: ((Int, Int) -> Unit)? = null,
) {
    var peeking by remember(currentPage) { mutableStateOf(false) }

    LaunchedEffect(volumeNavEvents) {
        volumeNavEvents.collect { event ->
            when (event) {
                VolumeNavEvent.Forward -> onNextPanel()
                VolumeNavEvent.Backward -> onPrevPanel()
            }
        }
    }

    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = currentPage, key2 = state.imageSource) {
        value = withContext(Dispatchers.IO) {
            runCatching { decodeSampledBitmap(state.imageSource, currentPage, MAX_PAGE_DIMENSION) }.getOrNull()
        }
    }

    var viewportW by remember { mutableStateOf(0) }
    var viewportH by remember { mutableStateOf(0) }
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                viewportW = size.width
                viewportH = size.height
                onViewportSizeChanged?.invoke(size.width, size.height)
            }
            .pointerInput(currentPage, panelIndex, peeking) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val longPressMs = viewConfiguration.longPressTimeoutMillis
                    val up = withTimeoutOrNull(longPressMs) { waitForUpOrCancellation() }
                    if (up == null) {
                        // Long-press: open the peek overlay (persistent — ADR 0055 §5).
                        peeking = true
                        // Wait for the finger to lift so we don't re-trigger.
                        waitForUpOrCancellation()
                    } else if (!peeking) {
                        val third = size.width / 3f
                        when {
                            down.position.x < third -> onPrevPanel()
                            down.position.x > 2 * third -> onNextPanel()
                            else -> onToggleImmersive()
                        }
                    }
                }
            }
            .testTag("cbz_panel_viewer"),
        contentAlignment = Alignment.Center,
    ) {
        val panels = pagePanels?.panels
        val fitWhole = pagePanels == null || pagePanels.isFallback || panels.isNullOrEmpty() || peeking
        val panel = if (!fitWhole) panels.getOrNull(panelIndex.coerceIn(0, panels.size - 1)) else null

        val transform = if (panel != null && pagePanels != null) {
            PanelFitTransform.compute(
                viewportWidth = viewportW,
                viewportHeight = viewportH,
                imageWidth = pagePanels.imageWidth,
                imageHeight = pagePanels.imageHeight,
                panel = panel,
            )
        } else {
            PanelFitTransform.Identity
        }
        val zoomScale = transform.scale
        val translationX = transform.translationX
        val translationY = transform.translationY

        // Animate scale + translation between panels. Declarative — Compose interpolates
        // whenever the target values change, and a mid-flight change simply re-targets
        // (interrupt semantics — ADR 0055 §4). Reduce Motion collapses to a snap.
        val reduceMotion = remember(context) { isReduceMotionEnabled(context) }
        val animationSpec = remember(reduceMotion, panelAnimationSpeedMs) {
            if (reduceMotion || panelAnimationSpeedMs == 0) snap<Float>() else tween<Float>(durationMillis = panelAnimationSpeedMs)
        }
        val animatedScale by animateFloatAsState(
            targetValue = zoomScale,
            animationSpec = animationSpec,
            label = "cbz_panel_scale",
        )
        val animatedTx by animateFloatAsState(
            targetValue = translationX,
            animationSpec = animationSpec,
            label = "cbz_panel_tx",
        )
        val animatedTy by animateFloatAsState(
            targetValue = translationY,
            animationSpec = animationSpec,
            label = "cbz_panel_ty",
        )

        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(bitmap)
                .crossfade(false)
                .build(),
            contentDescription = androidx.compose.ui.res.stringResource(
                com.riffle.app.R.string.ui_comic_page_panel,
                currentPage + 1,
                panelIndex + 1,
            ),
            loading = { CircularProgressIndicator() },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = animatedScale,
                    scaleY = animatedScale,
                    translationX = animatedTx,
                    translationY = animatedTy,
                ),
        )

        if (peeking) {
            CbzPanelPeekOverlay(
                onDismiss = { peeking = false },
                onSkip = {
                    peeking = false
                    onSkipGuidedPage()
                },
            )
        }
    }
}

@Composable
private fun CbzPanelPeekOverlay(
    onDismiss: () -> Unit,
    onSkip: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onDismiss() })
            }
            .testTag("cbz_panel_peek"),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Button(
            onClick = onSkip,
            modifier = Modifier
                .padding(24.dp)
                .testTag("cbz_panel_peek_skip"),
        ) {
            Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_skip_guided_panels_on_this_page))
        }
    }
}

private enum class TapZone { Left, Center, Right }

/**
 * Honour the OS Reduce Motion setting. When any of the animation scales is 0 the user has asked
 * the platform to skip transition animations; we collapse Panel View's Matrix interpolation to
 * an instant snap.
 */
private fun isReduceMotionEnabled(context: android.content.Context): Boolean {
    val cr = context.contentResolver
    fun getScale(name: String): Float = try {
        Settings.Global.getFloat(cr, name, 1f)
    } catch (_: Throwable) {
        1f
    }
    return getScale(Settings.Global.ANIMATOR_DURATION_SCALE) == 0f ||
        getScale(Settings.Global.TRANSITION_ANIMATION_SCALE) == 0f ||
        getScale(Settings.Global.WINDOW_ANIMATION_SCALE) == 0f
}

internal enum class CbzPageGestureAction { Ignore, Zoom, PanZoomed }

// Large BMPs can exceed 274MB decoded. Subsample to this max dimension to keep the Bitmap
// allocation under the app heap limit.
private const val MAX_PAGE_DIMENSION = 4096

internal fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    var sampleSize = 1
    while (maxOf(width, height) / sampleSize > maxDimension) sampleSize *= 2
    return sampleSize
}

internal fun decodeSampledBitmap(source: CbzImageSource, pageIndex: Int, maxDimension: Int): Bitmap? {
    // Try a bounds-only pass first to pick an optimal starting inSampleSize without allocating
    // any output Bitmap. BMP images OOM even on the bounds pass (Skia still reads the full row
    // data internally), so catch that and fall back to starting at 1.
    // Try a bounds-only pass first to pick an optimal starting inSampleSize without allocating
    // any output Bitmap. BMP images OOM even on the bounds pass (Skia still reads the full row
    // data internally), so catch that and fall back to starting at 1.
    val startSampleSize = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        source.openStream(pageIndex).use { BitmapFactory.decodeStream(it, null, opts) }
        calculateInSampleSize(opts.outWidth, opts.outHeight, maxDimension)
    } catch (_: Throwable) {
        1
    }
    var sampleSize = startSampleSize
    while (sampleSize <= 64) {
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = try {
            source.openStream(pageIndex).use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: OutOfMemoryError) {
            null
        }
        if (bitmap != null) return bitmap
        sampleSize *= 2
    }
    return null
}

/**
 * Decides how the per-page pointer handler should react to an event.
 *
 * The critical case is [Ignore]: a single-finger drag at scale=1 must NOT consume
 * pointer events, so `HorizontalPager` receives the swipe and turns the page.
 */
internal fun cbzPageGestureAction(pointerCount: Int, scale: Float): CbzPageGestureAction = when {
    pointerCount >= 2 -> CbzPageGestureAction.Zoom
    pointerCount == 1 && scale > 1f -> CbzPageGestureAction.PanZoomed
    else -> CbzPageGestureAction.Ignore
}

/**
 * Fills [cache] with decoded thumbnails, radiating outward from [startPage].
 *
 * Stops as soon as [cache] is full — loading beyond capacity would evict nearby entries
 * and leave the reading neighbourhood uncached (the "cache lost on page turn" bug).
 *
 * [decode] is called with a page index and must return a [Bitmap] or null on failure.
 * Production callers pass `{ decodeSampledBitmap(source, it, MAX_THUMB_DIMENSION) }`.
 * Tests pass a stub that creates a cheap 1×1 Bitmap.
 */
internal fun prewarmThumbnailCache(
    startPage: Int,
    pageCount: Int,
    cache: LruCache<Int, Bitmap>,
    decode: (Int) -> Bitmap?,
) {
    val capacity = cache.maxSize()
    var loaded = 0
    outer@ for (offset in 0..pageCount) {
        for (candidate in listOf(startPage + offset, startPage - offset).distinct()) {
            if (loaded >= capacity) break@outer
            if (candidate in 0 until pageCount && cache.get(candidate) == null) {
                decode(candidate)?.let {
                    cache.put(candidate, it)
                    loaded++
                }
            }
        }
    }
}
