package com.riffle.feature.reader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.ReaderTheme
import com.riffle.feature.designsystem.RiffleIcons
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.reader.CbzReaderState
import com.riffle.feature.reader.CbzReaderViewModel
import com.riffle.feature.reader.VolumeNavEvent
import com.riffle.feature.reader.cbzSegmentPageIndex
import com.riffle.feature.reader.ui.generated.resources.Res
import com.riffle.feature.reader.ui.generated.resources.reader_book_not_found
import com.riffle.feature.reader.ui.generated.resources.ui_back
import com.riffle.feature.reader.ui.generated.resources.ui_comic_formatting
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Shared CBZ reader chrome for Android and iOS. Platform hosts wrap this composable and supply
 * platform-specific implementations for image rendering ([pageContent]/[thumbnailContent]) and
 * the formatting sheet ([formattingSheet]).
 *
 * The screen owns the top bar, thumbnail strip, chapter rail, and pager/panel-view switching.
 * Platform wrappers own:
 *  - ViewModel acquisition (koinViewModel on Android, koinInject on iOS)
 *  - Keep-screen-on (window flag on Android, UIApplication on iOS)
 *  - Immersive mode state (SystemUI on Android, full-screen API on iOS)
 *  - Image decoding (BitmapFactory+Coil on Android, UIImage on iOS)
 *  - The formatting sheet contents (ComicFormattingSheet on Android, IosComicFormattingSheet on iOS)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CbzReaderScreen(
    viewModel: CbzReaderViewModel,
    onNavigateBack: () -> Unit,
    isImmersive: Boolean,
    onToggleImmersive: () -> Unit,
    /**
     * Platform-specific page image content. Called with a [Modifier] that includes the
     * graphicsLayer zoom transform (for the pager) or the panel crop transform (for panel view).
     * Must render a loading indicator while the image decodes, an error for unrecoverable failures,
     * and the decoded image once ready.
     */
    pageContent: @Composable (modifier: Modifier, page: Int) -> Unit,
    /** Bottom sheet that exposes comic formatting controls. Dismissed when [onDismiss] is called. */
    formattingSheet: @Composable (onDismiss: () -> Unit) -> Unit,
    /** Thumbnail-sized page content for the strip, defaults to [pageContent]. */
    thumbnailContent: @Composable (modifier: Modifier, page: Int) -> Unit = pageContent,
    /** Extra actions appended to the top-bar action row (e.g. dev-mode panel report on Android). */
    extraTopBarActions: @Composable () -> Unit = {},
    /** True when the OS Reduce Motion setting is active — collapses panel animations to instant. */
    isReduceMotion: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val currentPage by viewModel.currentPage.collectAsState()
    val panelViewOn by viewModel.panelViewOn.collectAsState()
    val effectivePanels by viewModel.effectivePanels.collectAsState()
    val currentPanelIndex by viewModel.currentPanelIndex.collectAsState()
    val effectiveComicFormatting by viewModel.effectiveComicFormatting.collectAsState()
    val comicBackgroundTheme by viewModel.comicBackgroundTheme.collectAsState()
    val railSegments by viewModel.railSegments.collectAsState()
    val activeRailSegmentIndex by viewModel.activeRailSegmentIndex.collectAsState()
    val railCursorPosition by viewModel.railCursorPosition.collectAsState()

    var formattingSheetOpen by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        if (state is CbzReaderState.Error || state is CbzReaderState.BookNotFound) {
            if (isImmersive) onToggleImmersive()
        }
    }

    DisposableEffect(viewModel) {
        onDispose { viewModel.onReaderClosed() }
    }

    val background = comicBackgroundTheme.readerPalette.background

    Box(modifier = modifier.fillMaxSize().background(background)) {
        when (val s = state) {
            CbzReaderState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            CbzReaderState.BookNotFound -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.reader_book_not_found), color = MaterialTheme.colorScheme.onSurface)
            }
            is CbzReaderState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(s.message, color = MaterialTheme.colorScheme.onSurface)
            }
            is CbzReaderState.Ready -> {
                if (panelViewOn) {
                    val effectivePanelAnimMs = if (isReduceMotion) 0 else effectiveComicFormatting.panelAnimationSpeedMs
                    CbzPanelViewer(
                        currentPage = currentPage,
                        pagePanels = effectivePanels,
                        panelIndex = currentPanelIndex,
                        panelAnimationSpeedMs = effectivePanelAnimMs,
                        onNextPanel = viewModel::nextPanel,
                        onPrevPanel = viewModel::previousPanel,
                        onSkipGuidedPage = viewModel::skipGuidedPanelsOnPage,
                        onToggleImmersive = onToggleImmersive,
                        volumeNavEvents = viewModel.volumeNavEvents,
                        onViewportSizeChanged = viewModel::setViewportSize,
                    ) { mod, page ->
                        pageContent(mod, page)
                    }
                } else {
                    SharedCbzPager(
                        pageCount = s.pageCount,
                        currentPage = currentPage,
                        onPageChanged = { viewModel.jumpToPage(it) },
                        onToggleImmersive = onToggleImmersive,
                        volumeNavEvents = viewModel.volumeNavEvents,
                        onNext = viewModel::nextPage,
                        onPrev = viewModel::previousPage,
                        pageContent = pageContent,
                    )
                }
            }
        }

        // Top bar
        AnimatedVisibility(
            visible = !isImmersive,
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
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag(TestTags.CBZ_READER_BACK),
                    ) {
                        Icon(RiffleIcons.ArrowBack, contentDescription = stringResource(Res.string.ui_back))
                    }
                },
                actions = {
                    if (state is CbzReaderState.Ready) {
                        IconButton(
                            onClick = { formattingSheetOpen = true },
                            modifier = Modifier.testTag(TestTags.CBZ_READER_SETTINGS),
                        ) {
                            Icon(
                                imageVector = RiffleIcons.Tune,
                                contentDescription = stringResource(Res.string.ui_comic_formatting),
                            )
                        }
                        extraTopBarActions()
                    }
                },
            )
        }

        // Bottom chrome (thumbnail strip + chapter map) — only when content is ready
        val ready = state as? CbzReaderState.Ready
        if (ready != null) {
            var chapterMapContentPx by remember { mutableStateOf(0) }
            val density = LocalDensity.current

            // Thumbnail strip
            AnimatedVisibility(
                visible = !isImmersive,
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
                        onSeek = { viewModel.jumpToPage(it) },
                        thumbnailContent = thumbnailContent,
                    )
                }
            }

            // Chapter map — always at bottom, never animated
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
                                    .background(ReaderTheme.Dark.readerPalette.background)
                                    .padding(horizontal = 14.dp, vertical = 2.dp),
                            ) {
                                Text(text = "${currentPage + 1}", style = labelStyle, color = labelColor)
                                Spacer(modifier = Modifier.weight(1f))
                                Text(text = "-${ready.pageCount - currentPage - 1}", style = labelStyle, color = labelColor)
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
                            templates = chapterMapProgressLabelTemplates(),
                            onSegmentClick = { segment ->
                                viewModel.jumpToPage(cbzSegmentPageIndex(segment))
                            },
                        )
                    }
                }
            }
        }
    }

    if (formattingSheetOpen) {
        formattingSheet { formattingSheetOpen = false }
    }
}

// ── Shared pager (Panel View OFF) ────────────────────────────────────────────

@Composable
private fun SharedCbzPager(
    pageCount: Int,
    currentPage: Int,
    onPageChanged: (Int) -> Unit,
    onToggleImmersive: () -> Unit,
    volumeNavEvents: kotlinx.coroutines.flow.SharedFlow<VolumeNavEvent>,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    pageContent: @Composable (modifier: Modifier, page: Int) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = currentPage) { pageCount }
    val scope = rememberCoroutineScope()

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != currentPage) onPageChanged(pagerState.currentPage)
    }
    LaunchedEffect(currentPage) {
        if (currentPage != pagerState.currentPage) pagerState.scrollToPage(currentPage)
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
        modifier = Modifier.fillMaxSize().testTag(TestTags.CBZ_PAGER),
    ) { pageIndex ->
        SharedCbzPage(
            pageIndex = pageIndex,
            pageCount = pageCount,
            onTapLeft = { scope.launch { pagerState.animateScrollToPage((pageIndex - 1).coerceAtLeast(0)) } },
            onTapRight = { scope.launch { pagerState.animateScrollToPage((pageIndex + 1).coerceAtMost(pageCount - 1)) } },
            onTapCenter = onToggleImmersive,
            pageContent = pageContent,
        )
    }
}

@Composable
private fun SharedCbzPage(
    pageIndex: Int,
    pageCount: Int,
    onTapLeft: () -> Unit,
    onTapRight: () -> Unit,
    onTapCenter: () -> Unit,
    pageContent: @Composable (modifier: Modifier, page: Int) -> Unit,
) {
    var scale by remember(pageIndex) { mutableStateOf(1f) }
    var offsetX by remember(pageIndex) { mutableStateOf(0f) }
    var offsetY by remember(pageIndex) { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()
    var zoomAnimJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(pageIndex) {
                detectTapGestures(
                    onDoubleTap = { tapOffset ->
                        zoomAnimJob?.cancel()
                        if (scale > 1f) {
                            val fromScale = scale
                            val fromX = offsetX
                            val fromY = offsetY
                            zoomAnimJob = scope.launch {
                                launch { animate(fromScale, 1f) { v, _ -> scale = v } }
                                launch { animate(fromX, 0f) { v, _ -> offsetX = v } }
                                animate(fromY, 0f) { v, _ -> offsetY = v }
                            }
                        } else {
                            val targetScale = 2.5f
                            val (tx, ty) = doubleTapZoomTranslation(
                                tapX = tapOffset.x,
                                tapY = tapOffset.y,
                                containerWidth = size.width.toFloat(),
                                containerHeight = size.height.toFloat(),
                                targetScale = targetScale,
                            )
                            val fromScale = scale
                            val fromX = offsetX
                            val fromY = offsetY
                            zoomAnimJob = scope.launch {
                                launch { animate(fromScale, targetScale) { v, _ -> scale = v } }
                                launch { animate(fromX, tx) { v, _ -> offsetX = v } }
                                animate(fromY, ty) { v, _ -> offsetY = v }
                            }
                        }
                    },
                    onTap = { pos ->
                        val third = size.width / 3f
                        val zone = when {
                            pos.x < third -> TapZone.Left
                            pos.x > 2 * third -> TapZone.Right
                            else -> TapZone.Center
                        }
                        when (zone) {
                            TapZone.Left -> onTapLeft()
                            TapZone.Right -> onTapRight()
                            TapZone.Center -> onTapCenter()
                        }
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
                                zoomAnimJob?.cancel()
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
                                zoomAnimJob?.cancel()
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
        pageContent(
            Modifier.fillMaxSize().graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offsetX,
                translationY = offsetY,
            ),
            pageIndex,
        )
    }
}

// ── Pure helpers — shared by Android and iOS page content slots ───────────────

private enum class TapZone { Left, Center, Right }

internal enum class CbzPageGestureAction { Ignore, Zoom, PanZoomed }

internal fun cbzPageGestureAction(pointerCount: Int, scale: Float): CbzPageGestureAction = when {
    pointerCount >= 2 -> CbzPageGestureAction.Zoom
    pointerCount == 1 && scale > 1f -> CbzPageGestureAction.PanZoomed
    else -> CbzPageGestureAction.Ignore
}

internal fun doubleTapZoomTranslation(
    tapX: Float,
    tapY: Float,
    containerWidth: Float,
    containerHeight: Float,
    targetScale: Float,
): Pair<Float, Float> =
    (tapX - containerWidth / 2f) * (1f - targetScale) to
        (tapY - containerHeight / 2f) * (1f - targetScale)
