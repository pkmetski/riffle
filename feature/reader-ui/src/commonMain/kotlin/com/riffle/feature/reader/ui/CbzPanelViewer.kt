package com.riffle.feature.reader.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.comic.panel.PagePanels
import com.riffle.core.domain.comic.panel.PanelFitTransform
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.reader.VolumeNavEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import com.riffle.feature.reader.ui.generated.resources.Res
import com.riffle.feature.reader.ui.generated.resources.ui_skip_guided_panels_on_this_page

/**
 * Shared Panel View compositor (ADR 0055). Handles touch, panel-to-panel animation, and the
 * long-press peek overlay. Image rendering is delegated to [pageContent] so Android (Coil/Bitmap)
 * and iOS (UIImage/UIKitView) each supply their own rendering without platform code in commonMain.
 *
 * [pageContent] receives a [Modifier] that includes `fillMaxSize` and the `graphicsLayer`
 * zoom/translation for the current panel; apply it to whatever surface renders the comic page.
 * The slot is responsible for loading and displaying the image for [currentPage].
 */
@Composable
fun CbzPanelViewer(
    currentPage: Int,
    pagePanels: PagePanels?,
    panelIndex: Int,
    panelAnimationSpeedMs: Int,
    onNextPanel: () -> Unit,
    onPrevPanel: () -> Unit,
    onSkipGuidedPage: () -> Unit,
    onToggleImmersive: () -> Unit,
    volumeNavEvents: SharedFlow<VolumeNavEvent>,
    onViewportSizeChanged: ((Int, Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
    pageContent: @Composable (modifier: Modifier, page: Int) -> Unit,
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

    var viewportW by remember { mutableStateOf(0) }
    var viewportH by remember { mutableStateOf(0) }

    Box(
        modifier = modifier
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
                        peeking = true
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
            .testTag(TestTags.CBZ_PANEL_VIEWER),
        contentAlignment = Alignment.Center,
    ) {
        val panels = pagePanels?.panels
        val fitWhole = pagePanels == null || pagePanels.isFallback || panels.isNullOrEmpty() || peeking
        val panel = if (!fitWhole && panels != null) panels.getOrNull(panelIndex.coerceIn(0, panels.size - 1)) else null

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

        val tweenSpec = remember(panelAnimationSpeedMs) { tween<Float>(durationMillis = panelAnimationSpeedMs) }
        val scaleAnim = remember(currentPage, pagePanels, viewportW, viewportH) { Animatable(transform.scale) }
        val txAnim = remember(currentPage, pagePanels, viewportW, viewportH) { Animatable(transform.translationX) }
        val tyAnim = remember(currentPage, pagePanels, viewportW, viewportH) { Animatable(transform.translationY) }

        LaunchedEffect(transform.scale, transform.translationX, transform.translationY) {
            if (viewportW <= 0 || viewportH <= 0) return@LaunchedEffect
            if (transform.scale == scaleAnim.targetValue &&
                transform.translationX == txAnim.targetValue &&
                transform.translationY == tyAnim.targetValue) return@LaunchedEffect
            if (panelAnimationSpeedMs == 0) {
                scaleAnim.snapTo(transform.scale)
                txAnim.snapTo(transform.translationX)
                tyAnim.snapTo(transform.translationY)
            } else {
                launch { scaleAnim.animateTo(transform.scale, tweenSpec) }
                launch { txAnim.animateTo(transform.translationX, tweenSpec) }
                launch { tyAnim.animateTo(transform.translationY, tweenSpec) }
            }
        }

        // Hold on the spinner while detection is in progress and this page has non-fallback panels
        // (i.e. detection is expected but hasn't arrived yet). This prevents the jarring
        // whole-page → panel-focused snap that happens when we render at Identity and then animate
        // to the panel transform once results arrive. Fallback pages (no panels detected) go
        // straight to the image at Identity.
        val detectingPanels = pagePanels == null
        if (detectingPanels) {
            CircularProgressIndicator()
        } else {
            pageContent(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scaleAnim.value
                        scaleY = scaleAnim.value
                        translationX = txAnim.value
                        translationY = tyAnim.value
                    },
                currentPage,
            )
        }

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
            .testTag(TestTags.CBZ_PANEL_PEEK),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Button(
            onClick = onSkip,
            modifier = Modifier
                .padding(24.dp)
                .testTag(TestTags.CBZ_PANEL_PEEK_SKIP),
        ) {
            Text(stringResource(Res.string.ui_skip_guided_panels_on_this_page))
        }
    }
}
