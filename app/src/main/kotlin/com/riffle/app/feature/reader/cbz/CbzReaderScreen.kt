package com.riffle.app.feature.reader.cbz

import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import coil.size.Size as CoilSize
import com.riffle.app.feature.reader.VolumeNavEvent
import com.riffle.app.feature.reader.rememberImmersiveModeState
import com.riffle.core.domain.comic.panel.PagePanels
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
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
    val currentPagePanels by viewModel.currentPagePanels.collectAsState()
    val currentPanelIndex by viewModel.currentPanelIndex.collectAsState()
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

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
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
                        pagePanels = currentPagePanels,
                        panelIndex = currentPanelIndex,
                        onNextPanel = viewModel::nextPanel,
                        onPrevPanel = viewModel::previousPanel,
                        onSkipGuidedPage = viewModel::skipGuidedPanelsOnPage,
                        onToggleImmersive = immersiveState::toggle,
                        volumeNavEvents = viewModel.volumeNavEvents,
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state is CbzReaderState.Ready) {
                        IconButton(
                            onClick = viewModel::togglePanelView,
                            modifier = Modifier.testTag("cbz_panel_view_toggle"),
                        ) {
                            Icon(
                                imageVector = if (panelViewOn) Icons.Filled.ViewCarousel else Icons.Filled.GridView,
                                contentDescription = if (panelViewOn) "Exit Panel View" else "Panel View",
                            )
                        }
                    }
                },
            )
        }

        val ready = state as? CbzReaderState.Ready
        if (ready != null) {
            AnimatedVisibility(
                visible = !immersiveState.isImmersive,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            ) {
                CbzThumbnailStrip(
                    currentPage = currentPage,
                    pageCount = ready.pageCount,
                    imageSource = ready.imageSource,
                    onSeek = { viewModel.jumpToPage(it) },
                )
            }
        }
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
    val bytes by produceState<ByteArray?>(initialValue = null, key1 = pageIndex, key2 = source) {
        value = withContext(Dispatchers.IO) { source.imageBytes(pageIndex) }
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
                .data(bytes)
                .crossfade(false)
                .build(),
            contentDescription = "Comic page ${pageIndex + 1}",
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

// --- Panel View (ADR 0043) ---

@Composable
private fun CbzPanelViewer(
    state: CbzReaderState.Ready,
    currentPage: Int,
    pagePanels: PagePanels?,
    panelIndex: Int,
    onNextPanel: () -> Unit,
    onPrevPanel: () -> Unit,
    onSkipGuidedPage: () -> Unit,
    onToggleImmersive: () -> Unit,
    volumeNavEvents: kotlinx.coroutines.flow.SharedFlow<VolumeNavEvent>,
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

    val bytes by produceState<ByteArray?>(initialValue = null, key1 = currentPage, key2 = state.imageSource) {
        value = withContext(Dispatchers.IO) { state.imageSource.imageBytes(currentPage) }
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
            }
            .pointerInput(currentPage, panelIndex, peeking) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val longPressMs = viewConfiguration.longPressTimeoutMillis
                    val up = withTimeoutOrNull(longPressMs) { waitForUpOrCancellation() }
                    if (up == null) {
                        // Long-press: open the peek overlay (persistent — ADR 0043 §5).
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
        val panel = if (!fitWhole) panels?.getOrNull(panelIndex.coerceIn(0, panels.size - 1)) else null

        val zoomScale: Float
        val translationX: Float
        val translationY: Float
        if (
            panel != null && viewportW > 0 && viewportH > 0 &&
            pagePanels != null && pagePanels.imageWidth > 0 && pagePanels.imageHeight > 0
        ) {
            val fitScale = min(
                viewportW.toFloat() / pagePanels.imageWidth.toFloat(),
                viewportH.toFloat() / pagePanels.imageHeight.toFloat(),
            )
            val displayedW = pagePanels.imageWidth * fitScale
            val displayedH = pagePanels.imageHeight * fitScale
            val letterboxX = (viewportW - displayedW) / 2f
            val letterboxY = (viewportH - displayedH) / 2f
            val panelDisplayedW = panel.width * fitScale
            val panelDisplayedH = panel.height * fitScale
            zoomScale = min(
                viewportW.toFloat() / panelDisplayedW,
                viewportH.toFloat() / panelDisplayedH,
            )
            val panelCentroidX = letterboxX + (panel.x + panel.width / 2f) * fitScale
            val panelCentroidY = letterboxY + (panel.y + panel.height / 2f) * fitScale
            translationX = zoomScale * (viewportW / 2f - panelCentroidX)
            translationY = zoomScale * (viewportH / 2f - panelCentroidY)
        } else {
            zoomScale = 1f
            translationX = 0f
            translationY = 0f
        }

        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(bytes)
                .crossfade(false)
                .build(),
            contentDescription = "Comic page ${currentPage + 1} panel ${panelIndex + 1}",
            loading = { CircularProgressIndicator() },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = zoomScale,
                    scaleY = zoomScale,
                    translationX = translationX,
                    translationY = translationY,
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
            Text("Skip guided panels on this page")
        }
    }
}

@Composable
internal fun CbzThumbnailStrip(
    currentPage: Int,
    pageCount: Int,
    imageSource: CbzImageSource,
    onSeek: (Int) -> Unit,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(currentPage) {
        val viewport = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
        val leading = if (viewport > 0) -(viewport / 2) else 0
        listState.animateScrollToItem(currentPage, scrollOffset = leading)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
            .navigationBarsPadding()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "${currentPage + 1} / $pageCount",
            color = MaterialTheme.colorScheme.onSurface,
        )
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .testTag("cbz_thumbnail_strip"),
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(pageCount) { index ->
                CbzThumbnail(
                    imageSource = imageSource,
                    pageIndex = index,
                    isCurrent = index == currentPage,
                    onClick = { onSeek(index) },
                )
            }
        }
    }
}

@Composable
private fun CbzThumbnail(
    imageSource: CbzImageSource,
    pageIndex: Int,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val bytes by produceState<ByteArray?>(initialValue = null, key1 = pageIndex, key2 = imageSource) {
        value = withContext(Dispatchers.IO) { runCatching { imageSource.imageBytes(pageIndex) }.getOrNull() }
    }
    val borderColor = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Transparent
    Box(
        modifier = Modifier
            .size(width = 88.dp, height = 120.dp)
            .background(Color(0xFF1A1A1A), RoundedCornerShape(2.dp))
            .border(width = 2.dp, color = borderColor, shape = RoundedCornerShape(2.dp))
            .clickable { onClick() }
            .testTag("cbz_thumb_$pageIndex"),
    ) {
        val currentBytes = bytes
        if (currentBytes != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(currentBytes)
                    .size(CoilSize(264, 360))
                    .crossfade(false)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private enum class TapZone { Left, Center, Right }

internal enum class CbzPageGestureAction { Ignore, Zoom, PanZoomed }

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
