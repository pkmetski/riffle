package com.riffle.app.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.github.barteksc.pdfviewer.PDFView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Wires PDF text-selection into the reader screen:
 *
 * 1. **Long-press → highlight.** Reads ACTION_DOWN coords via a passive
 *    `View.OnTouchListener` (never consumes), then `View.OnLongClickListener`
 *    fires after the platform's 500 ms long-press timeout. The handler
 *    resolves the touch point to a (page, PDF-point), opens that page's
 *    Pdfium text page, finds the word at the point, computes its quads, and
 *    persists a yellow highlight via the ViewModel.
 *
 * 2. **Highlight rendering.** Painted directly on PDFView's canvas during
 *    its own draw pass via `setOnDrawAll(OnDrawListener)` (registered via
 *    reflection on PDFView's package-private `callbacks` field). This
 *    bypasses the Compose-overlay-vs-AndroidView z-order problem entirely
 *    — PDFView is already drawing, we just add our quads to its canvas.
 *    Canvas is in page-local screen-pixels (PDFView has transformed for
 *    page position + zoom before calling us), so the only math needed is
 *    PDF-points → page-local pixels via the page's screen size.
 *
 * The Compose Box this composable returns is empty (no Canvas), so it
 * doesn't interfere with touch dispatch.
 *
 * Selection-with-drag-handles, action-menu (Copy / Add note), and
 * tap-on-existing-highlight-to-edit are v1 cutouts; the foundation
 * (`PdfSelectionGestureMachine`, color picker, action sheets) is in place
 * and will land in a follow-up.
 */
@Composable
fun PdfSelectionOverlay(
    viewModel: PdfReaderViewModel,
    getPdfView: () -> PDFView?,
    onSingleTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val annotations by viewModel.annotations.collectAsState()
    val annotationCount = annotations.size  // explicit subscription
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val pdfView = getPdfView()

    // Install long-press handling on PDFView itself (not on a Compose overlay
    // — Compose's pointerInput would block PDFView's scroll/zoom gestures).
    // We bind via View.setOnLongClickListener which runs after the platform's
    // 500ms long-press timeout regardless of any other gesture detectors
    // PDFView or Readium have wired underneath.
    // Long-press detection lives in the Compose overlay's pointerInput
    // rather than on PDFView. Touching PDFView via setOnTouchListener /
    // setOnLongClickListener breaks Readium's tap-to-toggle-immersive
    // (PDFView's child Views capture the gesture after ACTION_DOWN, so my
    // OnTouchListener never sees ACTION_UP and the platform's long-press
    // path mis-fires). Compose's pointerInput, layered on the overlay Box
    // ABOVE the AndroidView, observes events BEFORE PDFView. By NOT
    // consuming pointer changes, events still propagate down to PDFView
    // for its own scroll/zoom/tap behavior.

    // Tick state that bumps whenever PDFView's scroll/zoom changes so the
    // highlight quads re-position. Polled at ~60Hz while the reader is on
    // screen — cheap (a single integer read per frame).
    var scrollTick by remember { mutableStateOf(0L) }
    LaunchedEffect(pdfView) {
        if (pdfView == null) return@LaunchedEffect
        var lastX = Float.NaN
        var lastY = Float.NaN
        var lastZ = Float.NaN
        while (true) {
            val x = pdfView.currentXOffset
            val y = pdfView.currentYOffset
            val z = pdfView.zoom
            if (x != lastX || y != lastY || z != lastZ) {
                lastX = x; lastY = y; lastZ = z
                scrollTick++
            }
            delay(16)  // ~60Hz
        }
    }

    // The Compose overlay just renders highlight quads as opaque Boxes
    // positioned in screen coords. Each highlight is a Box(.background)
    // with absolute offset — Compose layout/draw is rock-solid and z-orders
    // above the AndroidView sibling in declaration order.
    if (pdfView != null) {
        val density = LocalDensity.current
        // Yellow at 0.6 alpha — visible on white PDF pages without obscuring
        // the text underneath. EPUB highlights use ~0.4 against the WebView
        // background, but PDF page bitmaps are flatter (printed white), so
        // a higher alpha reads as the same visual weight.
        val fillColor = Color(0xFFFFEB3B).copy(alpha = 0.6f)
        Box(
            modifier = modifier
                .fillMaxSize()
                .pointerInput(pdfView, viewModel) {
                    // detectTapGestures owns tap + long-press detection. It
                    // consumes the touch when a gesture is detected, which
                    // means PDFView won't see taps that we handle here —
                    // so we forward tap → onSingleTap (chrome toggle). Drags
                    // (scroll/zoom) don't fire any of these callbacks; those
                    // events propagate through to PDFView normally.
                    detectTapGestures(
                        onTap = {
                            android.util.Log.i("RifflePdfSel", "onTap → chrome toggle")
                            onSingleTap()
                        },
                        onLongPress = { offset ->
                            android.util.Log.i(
                                "RifflePdfSel",
                                "onLongPress at (${offset.x}, ${offset.y})",
                            )
                            handleLongPressAt(pdfView, offset.x, offset.y, viewModel, scope)
                        },
                    )
                },
        ) {
            // Touch scrollTick + annotationCount so this scope recomposes
            // when PDFView scrolls OR new highlights land.
            @Suppress("UNUSED_EXPRESSION") scrollTick
            @Suppress("UNUSED_EXPRESSION") annotationCount
            android.util.Log.v(
                "RifflePdfSel",
                "draw scope reached: annotationCount=$annotationCount " +
                    "scrollTick=$scrollTick currentPage=${pdfView.currentPage}",
            )
            val totalPages = pdfPageCount(pdfView)
            val active = pdfView.currentPage
            // Walk a wider page window (active ± 3) so highlights remain
            // painted when the user partially scrolls past the page they
            // landed on. PDFView's currentPage reflects the "most visible"
            // page but other pages in the visible viewport must also paint.
            var drawn = 0
            for (p in (active - 3)..(active + 3)) {
                if (p < 0 || p >= totalPages) continue
                val dims = viewModel.pdfPageDimensionsPoints(p) ?: continue
                for ((id, pdfQuads) in viewModel.highlightsForPage(p)) {
                    for (pdfRect in pdfQuads) {
                        val screenRect = PdfPageCoordinates.pdfRectToScreen(
                            pdfView = pdfView,
                            pageIndex = p,
                            pdfRect = pdfRect,
                            pageWidthPoints = dims.first,
                            pageHeightPoints = dims.second,
                            pageCount = totalPages,
                        ) ?: continue
                        val w = (screenRect.right - screenRect.left).coerceAtLeast(1f)
                        val h = (screenRect.bottom - screenRect.top).coerceAtLeast(1f)
                        val wDp = with(density) { w.toDp() }
                        val hDp = with(density) { h.toDp() }
                        android.util.Log.v(
                            "RifflePdfSel",
                            "draw highlight $id p=$p pdfRect=$pdfRect → screen=$screenRect ($wDp x $hDp)",
                        )
                        drawn++
                        Box(
                            modifier = Modifier
                                .offset {
                                    IntOffset(screenRect.left.toInt(), screenRect.top.toInt())
                                }
                                .size(wDp, hDp)
                                .graphicsLayer { }
                                .background(fillColor),
                        )
                    }
                }
            }
            if (drawn == 0) {
                android.util.Log.v(
                    "RifflePdfSel",
                    "draw scope: no highlights drawn for active=$active",
                )
            }
        }
    }
}

private fun pdfPageCount(pdfView: PDFView): Int = runCatching { pdfView.pageCount }.getOrElse { 0 }

/**
 * Forwards a PDFView long-press event to the ViewModel: resolves the event's
 * (x,y) screen coords to a (page, PDF point), opens the page's text page, and
 * persists a yellow highlight on the word under the press. Idempotent if the
 * touch missed any text (returns silently).
 */
private fun handleLongPressAt(
    pdfView: PDFView,
    touchX: Float,
    touchY: Float,
    viewModel: PdfReaderViewModel,
    scope: CoroutineScope,
) {
    val totalPages = pdfPageCount(pdfView)
    val point = PdfPageCoordinates.screenToPdf(
        pdfView = pdfView,
        touchX = touchX,
        touchY = touchY,
        getPageWidthPoints = { i -> viewModel.pdfPageDimensionsPoints(i)?.first ?: 0.0 },
        getPageHeightPoints = { i -> viewModel.pdfPageDimensionsPoints(i)?.second ?: 0.0 },
        pageCount = totalPages,
    )
    android.util.Log.i("RifflePdfSel", "screenToPdf → $point")
    if (point == null) return
    scope.launch {
        val a = viewModel.createHighlightAtPdfPoint(
            pageIndex = point.pageIndex,
            xPoints = point.xPoints,
            yPoints = point.yPoints,
        )
        android.util.Log.i("RifflePdfSel", "createHighlightAtPdfPoint → ${a?.id}")
        // Force a redraw so the new quad lands on screen immediately
        // (without this, PDFView would only repaint on next scroll/zoom).
        pdfView.invalidate()
    }
}

