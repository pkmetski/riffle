package com.riffle.feature.reader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import com.riffle.feature.reader.FigureZoomState
import com.riffle.feature.reader.clampPanZoom
import com.riffle.feature.reader.fitImageIntoViewport
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Fullscreen figure-zoom overlay shared by Android and iOS. Handles:
 *  - [AnimatedVisibility] fade in/out with [lastVisible] caching so the exit animates real content.
 *  - Pan, pinch-zoom (up to 5×), double-tap-to-reset, and tap-to-dismiss gestures.
 *  - System Back dismissal via [BackHandler].
 *
 * Platform-specific rendering is provided via [imageContent] (for raster/href figures) and
 * [svgContent] (for inline SVG figures). Both lambdas receive [imgModifier] — a [Modifier] with
 * the current pan/zoom [graphicsLayer] applied — so the transform is always in sync with the
 * gesture state without the callers managing it.
 *
 * The callers are responsible for loading image bytes and managing their lifecycle (e.g. Android
 * recycles the decoded [android.graphics.Bitmap]; iOS clears the [ByteArray] in [DisposableEffect]).
 * A loading spinner shown before bytes are available should NOT use [imgModifier] (it isn't
 * positioned at the figure centroid); use `Modifier.align(Alignment.Center)` or
 * `Modifier.fillMaxSize()` directly from the [BoxScope] receiver.
 */
@Composable
fun FigureZoomOverlay(
    state: FigureZoomState?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    imageContent: @Composable BoxScope.(href: String, imgModifier: Modifier) -> Unit,
    svgContent: @Composable BoxScope.(svgMarkup: String, imgModifier: Modifier) -> Unit,
) {
    // Cache the last non-null state so the fadeOut animates real content rather than an empty
    // subtree — a null-guarded early return during exit would make dismissal look instant.
    var lastVisible by remember { mutableStateOf<FigureZoomState?>(null) }
    if (state != null) lastVisible = state
    AnimatedVisibility(
        visible = state != null,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(150)),
        modifier = modifier,
    ) {
        val visibleState = lastVisible ?: return@AnimatedVisibility
        FigureZoomContent(
            state = visibleState,
            onDismiss = onDismiss,
            imageContent = imageContent,
            svgContent = svgContent,
        )
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun FigureZoomContent(
    state: FigureZoomState,
    onDismiss: () -> Unit,
    imageContent: @Composable BoxScope.(href: String, imgModifier: Modifier) -> Unit,
    svgContent: @Composable BoxScope.(svgMarkup: String, imgModifier: Modifier) -> Unit,
) {
    BackHandler { onDismiss() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f)),
    ) {
        val vpW = with(LocalDensity.current) { maxWidth.toPx() }
        val vpH = with(LocalDensity.current) { maxHeight.toPx() }
        val fit = fitImageIntoViewport(state.naturalWidth, state.naturalHeight, vpW, vpH)
        val fitW = fit.width.coerceAtLeast(1)
        val fitH = fit.height.coerceAtLeast(1)

        var scale by remember { mutableStateOf(1f) }
        var tx by remember { mutableStateOf(0f) }
        var ty by remember { mutableStateOf(0f) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                // Single pointerInput block handles pan, pinch-zoom, tap-to-dismiss, and
                // double-tap-to-reset together. Two separate pointerInput blocks race on
                // single-finger events because the second block dispatches first; this unified
                // handler avoids that conflict. Pattern mirrors the original Android implementation.
                .pointerInput(state) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var pastTouchSlop = false
                        var cumulativePan = Offset.Zero
                        var cumulativeZoom = 1f
                        do {
                            val event = awaitPointerEvent()
                            val anyConsumed = event.changes.any { it.isConsumed }
                            if (!anyConsumed) {
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                if (!pastTouchSlop) {
                                    cumulativePan += panChange
                                    cumulativeZoom *= zoomChange
                                    val centroidSize = event.calculateCentroidSize(useCurrent = false)
                                    val panDist = cumulativePan.getDistance()
                                    val zoomMotion = abs(1f - cumulativeZoom) * centroidSize
                                    pastTouchSlop = panDist > viewConfiguration.touchSlop ||
                                        zoomMotion > viewConfiguration.touchSlop
                                }
                                if (pastTouchSlop) {
                                    val clamped = clampPanZoom(
                                        scale = scale * zoomChange,
                                        translationX = tx + panChange.x,
                                        translationY = ty + panChange.y,
                                        fittedWidth = fitW.toFloat(),
                                        fittedHeight = fitH.toFloat(),
                                        viewportWidth = vpW,
                                        viewportHeight = vpH,
                                    )
                                    scale = clamped.scale
                                    tx = clamped.translationX
                                    ty = clamped.translationY
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        if (!pastTouchSlop) {
                            // Debounce: wait up to 300 ms for a second tap before acting.
                            // Second tap with no drag → double-tap → reset zoom.
                            // Timeout (no second tap) → single tap → dismiss.
                            var isDoubleTap = false
                            withTimeoutOrNull(300L) {
                                awaitFirstDown(requireUnconsumed = false)
                                var secondPastTouchSlop = false
                                var secondCumPan = Offset.Zero
                                var secondPressed = true
                                while (secondPressed) {
                                    val secondEvent = awaitPointerEvent()
                                    if (!secondPastTouchSlop && !secondEvent.changes.any { it.isConsumed }) {
                                        secondCumPan += secondEvent.calculatePan()
                                        secondPastTouchSlop =
                                            secondCumPan.getDistance() > viewConfiguration.touchSlop
                                    }
                                    secondPressed = secondEvent.changes.any { it.pressed }
                                }
                                isDoubleTap = !secondPastTouchSlop
                            }
                            if (isDoubleTap) {
                                val reset = clampPanZoom(
                                    scale = 1f,
                                    translationX = 0f,
                                    translationY = 0f,
                                    fittedWidth = fitW.toFloat(),
                                    fittedHeight = fitH.toFloat(),
                                    viewportWidth = vpW,
                                    viewportHeight = vpH,
                                )
                                scale = reset.scale
                                tx = reset.translationX
                                ty = reset.translationY
                            } else {
                                onDismiss()
                            }
                        }
                    }
                },
        ) {
            val imgModifier = Modifier
                .align(Alignment.Center)
                .size(
                    with(LocalDensity.current) { fitW.toDp() },
                    with(LocalDensity.current) { fitH.toDp() },
                )
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = tx, translationY = ty)

            val svgMarkup = state.svgMarkup
            if (svgMarkup != null) {
                svgContent(svgMarkup, imgModifier)
            } else {
                imageContent(state.href, imgModifier)
            }
        }
    }
}
