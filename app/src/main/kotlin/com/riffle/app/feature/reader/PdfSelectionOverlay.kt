package com.riffle.app.feature.reader

import android.graphics.RectF
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.listener.OnLongPressListener
import com.github.barteksc.pdfviewer.listener.OnTapListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Touch-down-to-up duration past which a finger-down counts as a long-press
// even if Android's GestureDetector.onLongPress never fired. ViewConfiguration's
// LONG_PRESS_TIMEOUT is 500ms by default; we use the same.
private const val LONG_PRESS_DURATION_MS = 500L

/**
 * Translucent overlay layered on top of the PDF view that **paints persisted
 * highlight quads** for the visible pages. The overlay itself is touch-
 * transparent — it has no pointerInput modifier — so PDF view receives all
 * gestures unchanged (scroll, pinch-zoom, edge-tap navigation).
 *
 * Long-press is captured by registering an [OnLongPressListener] on PDFView's
 * `callbacks` field (reflection — the field is package-private and the only
 * public registration path is during the Configurator build, which Readium
 * owns). The listener fires on the underlying view's touch events, so it
 * coexists with PDFView's own gestures (drag-to-scroll, pinch-zoom).
 *
 * Selection-with-drag-handles, action-menu with Copy/Note, and tap-on-existing-
 * highlight-to-edit are explicit v1 cutouts; the foundation (gesture state
 * machine, color picker, action sheet) is already in place and will land in
 * a follow-up. v1 ships single-long-press-creates-word-highlight with the
 * default yellow color so the end-to-end pipeline (touch → text resolve →
 * persist → sync → render) is exercised in production.
 */
@Composable
fun PdfSelectionOverlay(
    viewModel: PdfReaderViewModel,
    getPdfView: () -> PDFView?,
    modifier: Modifier = Modifier,
) {
    @Suppress("unused") val annotations by viewModel.annotations.collectAsState() // recompose on new highlights
    val currentPage by viewModel.currentPage.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val pdfView = getPdfView()

    // Hook long-press detection into PDFView's Callbacks for the duration of
    // this Composable's life. Reflection access to the package-private
    // `callbacks` field — the only public registration site is the
    // Configurator builder, which Readium owns at fragment-construction time.
    //
    // We register two listeners:
    // * OnLongPressListener — fires for real touchscreen long-presses via
    //   Android's GestureDetector (reliable on physical devices).
    // * OnTapListener — fires on tap-release; we compute event.eventTime −
    //   event.downTime and treat anything ≥ LONG_PRESS_DURATION_MS as a
    //   long-press. GestureDetector.onLongPress fails to fire when adb's
    //   `input swipe x y x y duration` synthesizes many ACTION_MOVE events
    //   at identical coords — those events cancel the long-press timer
    //   internally. The OnTap path catches that case AND is a safety net
    //   for any phone-vendor gesture-detection quirks. Both paths converge
    //   on the same handleLongPress code; whichever fires first wins.
    DisposableEffect(pdfView, viewModel) {
        val view = pdfView
        if (view == null) return@DisposableEffect onDispose { }
        val longPress = OnLongPressListener { event ->
            handleLongPress(view, event, viewModel, scope)
        }
        val tap = OnTapListener { event ->
            val held = event.eventTime - event.downTime
            if (held >= LONG_PRESS_DURATION_MS) {
                handleLongPress(view, event, viewModel, scope)
                true  // consume — don't trigger Riffle's tap-to-toggle-immersive
            } else {
                false
            }
        }
        val installed = installPdfViewListeners(view, longPress, tap)
        onDispose {
            if (installed) installPdfViewListeners(view, null, null)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (pdfView != null) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("pdf_selection_overlay"),
            ) {
                // Use the static yellow highlight color for v1; full palette
                // matches EPUB's `HighlightColor` enum and will land with the
                // color picker.
                val fillColor = Color(0xFFFFEB3B).copy(alpha = 0.35f)
                val totalPages = pdfPageCount(pdfView)
                // For each visible page, draw any persisted quads.
                val active = (currentPage ?: 1) - 1
                // Defensive: only look at the active page and its immediate
                // neighbours (the PDF view may show parts of 2 pages mid-scroll).
                for (p in (active - 1)..(active + 1)) {
                    if (p < 0 || p >= totalPages) continue
                    val dims = viewModel.pdfPageDimensionsPoints(p) ?: continue
                    viewModel.highlightsForPage(p).forEach { (_, pdfQuads) ->
                        pdfQuads.forEach { pdfRect ->
                            val screenRect = PdfPageCoordinates.pdfRectToScreen(
                                pdfView = pdfView,
                                pageIndex = p,
                                pdfRect = pdfRect,
                                pageWidthPoints = dims.first,
                                pageHeightPoints = dims.second,
                                pageCount = totalPages,
                            ) ?: return@forEach
                            drawRect(
                                color = fillColor,
                                topLeft = Offset(screenRect.left, screenRect.top),
                                size = androidx.compose.ui.geometry.Size(
                                    width = screenRect.right - screenRect.left,
                                    height = screenRect.bottom - screenRect.top,
                                ),
                            )
                        }
                    }
                }
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
private fun handleLongPress(
    pdfView: PDFView,
    event: MotionEvent,
    viewModel: PdfReaderViewModel,
    scope: CoroutineScope,
) {
    val totalPages = pdfPageCount(pdfView)
    android.util.Log.i(
        "RifflePdfSel",
        "onLongPress event x=${event.x} y=${event.y} pageCount=$totalPages " +
            "zoom=${pdfView.zoom} xOff=${pdfView.currentXOffset} yOff=${pdfView.currentYOffset} " +
            "currentPage=${pdfView.currentPage}",
    )
    val point = PdfPageCoordinates.screenToPdf(
        pdfView = pdfView,
        touchX = event.x,
        touchY = event.y,
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
        android.util.Log.i("RifflePdfSel", "createHighlightAtPdfPoint → $a")
    }
}

/**
 * Reflective install of [listener] on the PDFView's `callbacks` field. Pass
 * null to uninstall. Returns true if the field was reachable. PDFView only
 * exposes `setOnLongPress` via its Configurator at build time; we register
 * after the fact, which Readium's wiring doesn't allow.
 */
private fun installPdfViewListeners(
    pdfView: PDFView,
    longPress: OnLongPressListener?,
    tap: OnTapListener?,
): Boolean {
    return runCatching {
        val callbacksField = pdfView.javaClass.getDeclaredField("callbacks").apply { isAccessible = true }
        val callbacks = callbacksField.get(pdfView) ?: run {
            android.util.Log.w("RifflePdfSel", "PDFView.callbacks was null")
            return@runCatching false
        }
        callbacks.javaClass.getMethod(
            "setOnLongPress",
            com.github.barteksc.pdfviewer.listener.OnLongPressListener::class.java,
        ).invoke(callbacks, longPress)
        callbacks.javaClass.getMethod(
            "setOnTap",
            com.github.barteksc.pdfviewer.listener.OnTapListener::class.java,
        ).invoke(callbacks, tap)
        android.util.Log.i(
            "RifflePdfSel",
            "Installed listeners: longPress=${longPress != null} tap=${tap != null}",
        )
        true
    }.getOrElse { t ->
        android.util.Log.e("RifflePdfSel", "Failed to install listeners", t)
        false
    }
}
