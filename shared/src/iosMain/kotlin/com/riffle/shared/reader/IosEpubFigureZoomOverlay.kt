package com.riffle.shared.reader

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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs
import androidx.compose.ui.viewinterop.UIKitView
import com.riffle.feature.reader.FigureZoomState
import com.riffle.feature.reader.clampPanZoom
import com.riffle.feature.reader.fitImageIntoViewport
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.create
import kotlin.time.TimeSource
import platform.UIKit.UIColor
import platform.UIKit.UIImage
import platform.UIKit.UIImageView
import platform.UIKit.UIViewContentMode
import platform.CoreGraphics.CGRect
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Fullscreen figure-zoom overlay for the iOS EPUB reader.
 *
 * Mirrors [com.riffle.app.feature.reader.FigureZoomOverlay] on Android. Both share the same
 * pan/zoom math ([clampPanZoom], [fitImageIntoViewport]) from `feature:reader commonMain`. The
 * platform-specific part is image loading:
 *  - Android: [org.readium.r2.shared.publication.Publication.get] + BitmapFactory
 *  - iOS: [ReadiumSwiftNavigator.readResourceBytes] + UIImage
 *
 * Three rendering modes mirror Android:
 *  - `data:` URI → decode Base64 inline
 *  - EPUB resource → load bytes via [ReadiumSwiftNavigator.readResourceBytes]
 *  - SVG markup → render in a WKWebView via [UIKitView]
 *
 * The overlay is placed above the reader via [IosEpubReaderScreen]'s outer Box, same as Android.
 */
@Composable
internal fun IosEpubFigureZoomOverlay(
    state: FigureZoomState?,
    navigator: ReadiumSwiftNavigator,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var lastVisible by remember { mutableStateOf<FigureZoomState?>(null) }
    if (state != null) lastVisible = state
    AnimatedVisibility(
        visible = state != null,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(150)),
        modifier = modifier,
    ) {
        val visibleState = lastVisible ?: return@AnimatedVisibility
        IosEpubFigureZoomContent(
            state = visibleState,
            navigator = navigator,
            onDismiss = onDismiss,
        )
    }
}

@OptIn(ExperimentalEncodingApi::class)
@Composable
private fun IosEpubFigureZoomContent(
    state: FigureZoomState,
    navigator: ReadiumSwiftNavigator,
    onDismiss: () -> Unit,
) {
    // Load image bytes off the main thread. Null until loaded; SVG renders separately via WebView.
    var imageBytes by remember(state.href, state.svgMarkup) { mutableStateOf<ByteArray?>(null) }

    LaunchedEffect(state.href, state.svgMarkup) {
        if (state.svgMarkup != null) return@LaunchedEffect
        imageBytes = withContext(Dispatchers.IO) {
            val href = state.href
            if (href.startsWith("data:")) {
                // data:image/jpeg;base64,<payload>
                val commaIdx = href.indexOf(',')
                val meta = if (commaIdx >= 0) href.substring(0, commaIdx) else return@withContext null
                if (!meta.contains(";base64")) return@withContext null
                runCatching { Base64.decode(href.substring(commaIdx + 1)) }.getOrNull()
            } else {
                navigator.readResourceBytes(href)
            }
        }
    }
    DisposableEffect(state.href, state.svgMarkup) {
        onDispose { imageBytes = null }
    }

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
        var lastTapMark by remember { mutableStateOf<TimeSource.Monotonic.ValueTimeMark?>(null) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                // Single pointerInput block handles pan, pinch-zoom, tap-to-dismiss, and
                // double-tap-to-reset together. Two separate pointerInput blocks race on
                // single-finger events because the second block dispatches first; this unified
                // handler avoids that conflict. Mirrors FigureZoomContent on Android.
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
                            val mark = lastTapMark
                            val elapsed = mark?.elapsedNow()?.inWholeMilliseconds ?: Long.MAX_VALUE
                            if (elapsed < 300L) {
                                val reset = clampPanZoom(
                                    scale = 1f,
                                    translationX = 0f, translationY = 0f,
                                    fittedWidth = fitW.toFloat(), fittedHeight = fitH.toFloat(),
                                    viewportWidth = vpW, viewportHeight = vpH,
                                )
                                scale = reset.scale
                                tx = reset.translationX
                                ty = reset.translationY
                                lastTapMark = null
                            } else {
                                onDismiss()
                                lastTapMark = TimeSource.Monotonic.markNow()
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
            when {
                svgMarkup != null -> SvgWebView(svgMarkup = svgMarkup, modifier = imgModifier)
                imageBytes != null -> ImageBytesView(bytes = imageBytes!!, modifier = imgModifier)
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@Composable
private fun ImageBytesView(bytes: ByteArray, modifier: Modifier) {
    UIKitView(
        factory = {
            UIImageView().apply {
                contentMode = UIViewContentMode.UIViewContentModeScaleAspectFit
                backgroundColor = UIColor.clearColor
            }
        },
        update = { view ->
            bytes.usePinned { pinned ->
                val nsData = NSData.create(
                    bytes = pinned.addressOf(0),
                    length = bytes.size.toULong(),
                )
                view.image = UIImage.imageWithData(nsData)
            }
        },
        modifier = modifier,
    )
}

@OptIn(ExperimentalForeignApi::class)
@Composable
private fun SvgWebView(svgMarkup: String, modifier: Modifier) {
    val html = remember(svgMarkup) {
        """<!doctype html><html><head><meta name="viewport" content="width=device-width">
           <style>html,body{margin:0;padding:0;background:transparent}svg{width:100%;height:100%;display:block}</style>
           </head><body>$svgMarkup</body></html>""".trimIndent()
    }
    UIKitView(
        factory = {
            WKWebView(frame = kotlinx.cinterop.cValue<CGRect>(), configuration = WKWebViewConfiguration()).apply {
                opaque = false
                backgroundColor = UIColor.clearColor
                scrollView.backgroundColor = UIColor.clearColor
                scrollView.scrollEnabled = false
            }
        },
        update = { wv ->
            wv.loadHTMLString(html, baseURL = null)
        },
        modifier = modifier,
    )
}
