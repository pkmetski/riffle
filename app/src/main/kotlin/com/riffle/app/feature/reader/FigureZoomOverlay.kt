@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.feature.reader

import android.annotation.SuppressLint
import android.util.Base64
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url

/**
 * Fullscreen overlay that shows the tapped [state] figure zoomed into a fit-to-viewport view with
 * background text dimmed. Handles pinch-to-zoom (up to 5×), drag-to-pan (clamped so the image can't
 * leave the viewport), double-tap to reset, and tap-outside / system Back to dismiss.
 *
 * The overlay is mounted at the TOP of [EpubReaderScreen]'s outer Box so it renders above every
 * reader mode (paginated, vertical, continuous) and above the reader chrome without any per-mode
 * plumbing.
 *
 * Image loading:
 *  - `data:` URIs: decoded inline via Base64.
 *  - Other `href`s: fetched via [Publication.get] — the same path annotations use, so images
 *    load offline and don't re-hit the network.
 *  - Inline SVG (`state.svgMarkup != null`): rendered in a minimal WebView so vector art
 *    reflows to the fit box.
 */
@Composable
internal fun FigureZoomOverlay(
    state: FigureZoomState?,
    publication: Publication?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Cache the last non-null state so the fadeOut animates the actual content instead of an
    // empty subtree — a null-guarded `return@AnimatedVisibility` during exit would render nothing
    // for the whole 150ms, making dismissal look instant.
    var lastVisible by remember { mutableStateOf<FigureZoomState?>(null) }
    if (state != null) lastVisible = state
    AnimatedVisibility(
        visible = state != null,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(150)),
        modifier = modifier,
    ) {
        val visibleState = lastVisible ?: return@AnimatedVisibility
        FigureZoomContent(state = visibleState, publication = publication, onDismiss = onDismiss)
    }
}

@Composable
private fun FigureZoomContent(
    state: FigureZoomState,
    publication: Publication?,
    onDismiss: () -> Unit,
) {
    BackHandler(enabled = true) { onDismiss() }

    // Load image bytes off the main thread. Bitmap loading only — SVGs render via WebView below.
    val bitmap = remember(state.href, state.svgMarkup) {
        mutableStateOf<android.graphics.Bitmap?>(null)
    }
    // Cap the decode at 2048px on either axis. That's the largest natural size a user can meaningfully
    // resolve on a phone/tablet at 5x pinch (the max zoom clamp), and it prevents a 12-megapixel figure
    // from allocating ~48 MB on decode — enough to OOM the annotations flow on a 1 GB device.
    val decodeCapPx = 2048
    LaunchedEffect(state.href, state.svgMarkup) {
        if (state.svgMarkup != null) return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) {
            val bytes = loadImageBytes(state.href, publication) ?: return@withContext null
            decodeSampledBitmap(bytes, decodeCapPx, decodeCapPx)
        }
        bitmap.value = decoded
    }
    // Recycle the decoded Bitmap when this overlay leaves composition or the target figure changes.
    // Without this, quickly opening/closing several figure zooms keeps their full-resolution bitmaps
    // in the mutableStateOf remember scope until GC pressure eventually collects them.
    DisposableEffect(state.href, state.svgMarkup) {
        onDispose {
            val b = bitmap.value
            bitmap.value = null
            if (b != null && !b.isRecycled) b.recycle()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f))
            .pointerInput(state) {
                // Tap outside the image dismisses. Tap on the image is consumed by the transformable
                // pointer stream, so this only fires for background taps.
                detectTapGestures(onTap = { onDismiss() })
            },
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val vpW = with(LocalDensity.current) { maxWidth.toPx() }
            val vpH = with(LocalDensity.current) { maxHeight.toPx() }
            val fit = fitImageIntoViewport(state.naturalWidth, state.naturalHeight, vpW, vpH)
            val fitW = fit.width.coerceAtLeast(1)
            val fitH = fit.height.coerceAtLeast(1)

            var scale by remember { mutableStateOf(1f) }
            var tx by remember { mutableStateOf(0f) }
            var ty by remember { mutableStateOf(0f) }

            val transformState = rememberTransformableState { zoomChange, panChange, _ ->
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
            }

            val imgModifier = Modifier
                .align(Alignment.Center)
                .size(with(LocalDensity.current) { fitW.toDp() }, with(LocalDensity.current) { fitH.toDp() })
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = tx, translationY = ty)
                .transformable(transformState)
                .pointerInput(state) {
                    detectTapGestures(
                        onDoubleTap = {
                            // Route the reset through clampPanZoom so the single source of truth
                            // for valid transforms owns it — if minScale ever moves off 1f, the
                            // reset can't land outside the clamp and jump on the next pan.
                            val reset = clampPanZoom(
                                scale = 1f,
                                translationX = 0f, translationY = 0f,
                                fittedWidth = fitW.toFloat(), fittedHeight = fitH.toFloat(),
                                viewportWidth = vpW, viewportHeight = vpH,
                            )
                            scale = reset.scale
                            tx = reset.translationX
                            ty = reset.translationY
                        },
                        onTap = { /* consume — don't dismiss */ },
                    )
                }

            when {
                state.svgMarkup != null -> {
                    SvgWebView(svgMarkup = state.svgMarkup, modifier = imgModifier)
                }
                bitmap.value != null -> {
                    Image(
                        bitmap = bitmap.value!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = imgModifier,
                    )
                }
                else -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SvgWebView(svgMarkup: String, modifier: Modifier) {
    // Inline SVGs are rendered in a barebones WebView. The graphicsLayer scale on the parent
    // Modifier resizes the WebView tile, so pinch/zoom work naturally.
    val html = remember(svgMarkup) {
        """<!doctype html><html><head><meta name="viewport" content="width=device-width">
           <style>html,body{margin:0;padding:0;background:transparent}svg{width:100%;height:100%;display:block}</style>
           </head><body>$svgMarkup</body></html>""".trimIndent()
    }
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(0)
                settings.javaScriptEnabled = false
                settings.useWideViewPort = false
                settings.loadWithOverviewMode = true
            }
        },
        update = { wv ->
            wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        },
        modifier = modifier,
    )
}

/**
 * Load image bytes for the given [href] (as reported by the WebView).
 *  - `data:` URI → Base64-decoded payload.
 *  - `http`/`https`/`file` under Readium's `readium_package` virtual host → strip and query the
 *    Publication.
 *  - Anything else that resolves to a Publication href → same.
 */
private suspend fun loadImageBytes(href: String, publication: Publication?): ByteArray? {
    if (href.startsWith("data:")) {
        val comma = href.indexOf(',')
        if (comma < 0) return null
        val meta = href.substring(0, comma)
        val payload = href.substring(comma + 1)
        // Only base64 payloads decode cleanly to bitmap bytes. A URL-encoded data URI is text
        // (typically inline SVG), and re-encoding it via URLDecoder+String.toByteArray produces
        // UTF-8 bytes that BitmapFactory can't decode — the overlay would spin forever. Return
        // null so the caller shows the "not available" state instead of feeding corrupt bytes.
        // Inline SVG never arrives here (JS captures outerHTML into svgMarkup, not src).
        if (!meta.contains(";base64")) return null
        return runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull()
    }
    val pub = publication ?: return null
    // Strip the readium_package origin the WebView reports for served EPUB resources.
    val stripped = href
        .removePrefix("http://readium_package/")
        .removePrefix("https://readium_package/")
        .substringBefore('#')
    val url = Url(stripped) ?: return null
    return pub.get(url)?.read()?.getOrNull()
}
