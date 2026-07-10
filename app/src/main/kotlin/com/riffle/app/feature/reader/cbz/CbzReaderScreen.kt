package com.riffle.app.feature.reader.cbz

import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.riffle.app.feature.reader.VolumeNavEvent
import com.riffle.app.feature.reader.rememberImmersiveModeState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CbzReaderScreen(
    onNavigateBack: () -> Unit,
    viewModel: CbzReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val currentPage by viewModel.currentPage.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
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
            is CbzReaderState.Ready -> CbzPager(
                state = s,
                currentPage = currentPage,
                onPageChanged = { viewModel.jumpToPage(it) },
                onToggleImmersive = immersiveState::toggle,
                volumeNavEvents = viewModel.volumeNavEvents,
                onNext = viewModel::nextPage,
                onPrev = viewModel::previousPage,
            )
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
                CbzScrubber(
                    currentPage = currentPage,
                    pageCount = ready.pageCount,
                    onSeek = { viewModel.jumpToPage(it) },
                )
            }
        }
    }
}

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

    // ViewModel is the source of truth. When the user swipes, `onPageChanged` writes it back.
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != currentPage) onPageChanged(pagerState.currentPage)
    }
    // Reverse direction — jumps from scrubber / volume keys / initial resume.
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
    val bytes = remember(pageIndex, source) { source.imageBytes(pageIndex) }
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(pageIndex) {
                detectTapGestures(
                    onDoubleTap = {
                        // Double-tap resets zoom + pan, matching CDisplayEx.
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
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    if (scale > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
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

@Composable
private fun CbzScrubber(
    currentPage: Int,
    pageCount: Int,
    onSeek: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "${currentPage + 1} / $pageCount",
            color = MaterialTheme.colorScheme.onSurface,
        )
        Slider(
            value = currentPage.toFloat(),
            onValueChange = { onSeek(it.toInt()) },
            valueRange = 0f..(pageCount - 1).coerceAtLeast(1).toFloat(),
            steps = (pageCount - 2).coerceAtLeast(0),
            modifier = Modifier.weight(1f).testTag("cbz_scrubber"),
        )
    }
}

private enum class TapZone { Left, Center, Right }
