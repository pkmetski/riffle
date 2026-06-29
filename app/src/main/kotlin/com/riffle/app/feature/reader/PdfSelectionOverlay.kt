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
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeout
import com.github.barteksc.pdfviewer.PDFView
import com.riffle.core.domain.HighlightColor
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
    val pending by viewModel.pendingSelection.collectAsState()
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
                    // Custom long-press detection with a generous slop (24dp)
                    // because Compose's stock `detectTapGestures` uses
                    // ViewConfiguration.touchSlop (~8dp) for cancellation —
                    // tight enough that natural finger jitter during a 500ms
                    // press often cancels onLongPress before it fires, giving
                    // the user the experience of "long-press doesn't work."
                    // We tolerate up to ~24dp of jitter, the same threshold
                    // Android's stock OnLongClickListener uses internally.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startPos = down.position
                        val longPressMs = 500L
                        val slopPx = 24.dp.toPx()
                        val slopSqPx = slopPx * slopPx
                        var movedTooFar = false
                        var released = false
                        try {
                            withTimeout(longPressMs) {
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val change = ev.changes.firstOrNull { it.id == down.id }
                                        ?: continue
                                    if (!change.pressed) {
                                        released = true
                                        return@withTimeout
                                    }
                                    val dx = change.position.x - startPos.x
                                    val dy = change.position.y - startPos.y
                                    if (dx * dx + dy * dy > slopSqPx) {
                                        movedTooFar = true
                                        return@withTimeout
                                    }
                                }
                            }
                        } catch (_: PointerEventTimeoutCancellationException) {
                            // Hit the timeout while still pressed within slop — long press!
                        }
                        when {
                            released -> {
                                // Short tap. The selection overlay sits above
                                // PDFView, so Readium's DirectionalNavigationAdapter
                                // never sees edge taps — partition the tap zone
                                // ourselves and forward edges to page navigation.
                                if (viewModel.pendingSelection.value != null) {
                                    viewModel.discardPendingSelection()
                                } else {
                                    val viewWidth = size.width.toFloat()
                                    when (PdfTapZoneClassifier.classify(startPos.x, viewWidth)) {
                                        PdfTapZone.LeftEdge -> viewModel.requestPageNav(forward = false)
                                        PdfTapZone.RightEdge -> viewModel.requestPageNav(forward = true)
                                        PdfTapZone.Center -> onSingleTap()
                                    }
                                }
                            }
                            movedTooFar -> {
                                // User dragged — likely a scroll. Don't fire
                                // anything; events propagate to PDFView for
                                // its own scroll handling.
                            }
                            else -> {
                                // 500ms held within slop — fire long-press.
                                handleLongPressAt(pdfView, startPos.x, startPos.y, viewModel)
                            }
                        }
                    }
                },
        ) {
            // Touch scrollTick + annotationCount so this scope recomposes
            // when PDFView scrolls OR new highlights land.
            @Suppress("UNUSED_EXPRESSION") scrollTick
            @Suppress("UNUSED_EXPRESSION") annotationCount
            val totalPages = pdfPageCount(pdfView)
            val active = pdfView.currentPage

            // Paint committed (persisted) highlights, using each row's saved color.
            for (p in (active - 3)..(active + 3)) {
                if (p < 0 || p >= totalPages) continue
                val dims = viewModel.pdfPageDimensionsPoints(p) ?: continue
                for ((id, pdfQuads) in viewModel.highlightsForPage(p)) {
                    val annotationColor = viewModel.annotations.value
                        .firstOrNull { it.id == id }
                        ?.color
                        ?.let { HighlightColor.fromToken(it) }
                        ?: HighlightColor.entries.first()
                    val color = Color(annotationColor.argb).copy(alpha = 0.55f)
                    for (pdfRect in pdfQuads) {
                        val screenRect = PdfPageCoordinates.pdfRectToScreen(
                            pdfView = pdfView,
                            pageIndex = p,
                            pdfRect = pdfRect,
                            pageWidthPoints = dims.first,
                            pageHeightPoints = dims.second,
                            pageCount = totalPages,
                        ) ?: continue
                        renderQuadBox(screenRect, color, density)
                    }
                }
            }

            // Paint the pending (uncommitted) selection — a transient yellow
            // hint shown while the action-popup is up. Dismissing the popup
            // clears the pending state and this draw branch goes away.
            val pendingSnapshot = pending
            if (pendingSnapshot != null) {
                val dims = viewModel.pdfPageDimensionsPoints(pendingSnapshot.pageIndex)
                if (dims != null) {
                    for (pdfRect in pendingSnapshot.quads) {
                        val screenRect = PdfPageCoordinates.pdfRectToScreen(
                            pdfView = pdfView,
                            pageIndex = pendingSnapshot.pageIndex,
                            pdfRect = pdfRect,
                            pageWidthPoints = dims.first,
                            pageHeightPoints = dims.second,
                            pageCount = totalPages,
                        ) ?: continue
                        renderQuadBox(screenRect, fillColor, density)
                    }
                }
            }
        }

        // The action popup is anchored to the word's screen-space rect. Picking
        // a color commits the highlight; tapping outside / pressing back
        // discards. The note flow is deferred — onOpenNoteEditor commits with
        // the default color for now (future patch will add a notes editor).
        val pendingSnapshot = pending
        if (pendingSnapshot != null) {
            HighlightActionsPopup(
                anchorRect = pendingSnapshot.anchorRect,
                selected = null,
                note = null,
                onPick = { color -> viewModel.commitPendingSelection(color) },
                onDelete = { viewModel.discardPendingSelection() },
                onOpenNoteEditor = {
                    // For v1, treat "Add note" as commit-with-default-color.
                    // Future patch: open a separate notes editor sheet.
                    viewModel.commitPendingSelection(HighlightColor.entries.first())
                },
                onDismiss = { viewModel.discardPendingSelection() },
            )
        }
    }
}

@Composable
private fun renderQuadBox(
    screenRect: android.graphics.RectF,
    color: Color,
    density: androidx.compose.ui.unit.Density,
) {
    val w = (screenRect.right - screenRect.left).coerceAtLeast(1f)
    val h = (screenRect.bottom - screenRect.top).coerceAtLeast(1f)
    val wDp = with(density) { w.toDp() }
    val hDp = with(density) { h.toDp() }
    Box(
        modifier = Modifier
            .offset { IntOffset(screenRect.left.toInt(), screenRect.top.toInt()) }
            .size(wDp, hDp)
            .graphicsLayer { }
            .background(color),
    )
}

private fun pdfPageCount(pdfView: PDFView): Int = runCatching { pdfView.pageCount }.getOrElse { 0 }

/**
 * Forwards a long-press to the ViewModel: resolves the touch point to a
 * (page, PDF point), uses [PdfReaderViewModel.beginPendingSelection] to
 * attempt to snap to the word at that point. Iff a word resolves, the
 * ViewModel transitions into pendingSelection state and the overlay
 * recomposes to show the [HighlightActionsPopup]. If no word hits (touch
 * on whitespace, figure, equation), the long-press is silently ignored —
 * we do NOT widen the tolerance and pick a random nearby char, because
 * that produced visually-arbitrary highlight placement.
 *
 * The anchor rect for the popup is computed from the resolved word's
 * first quad mapped back to screen coords, so the popup hovers next to
 * the highlighted word rather than at the raw touch point (which may be
 * slightly off-letter).
 */
private fun handleLongPressAt(
    pdfView: PDFView,
    touchX: Float,
    touchY: Float,
    viewModel: PdfReaderViewModel,
) {
    val totalPages = pdfPageCount(pdfView)
    val point = PdfPageCoordinates.screenToPdf(
        pdfView = pdfView,
        touchX = touchX,
        touchY = touchY,
        getPageWidthPoints = { i -> viewModel.pdfPageDimensionsPoints(i)?.first ?: 0.0 },
        getPageHeightPoints = { i -> viewModel.pdfPageDimensionsPoints(i)?.second ?: 0.0 },
        pageCount = totalPages,
    ) ?: return
    // Compute a tentative anchor near the touch — pre-resolution, since we
    // don't have the word's quad yet. ViewModel will use this as the popup
    // anchor; the popup positions itself near (not exactly at) this rect.
    val tentativeAnchor = IntRect(
        left = (touchX - 24).toInt(),
        top = (touchY - 24).toInt(),
        right = (touchX + 24).toInt(),
        bottom = (touchY + 24).toInt(),
    )
    val started = viewModel.beginPendingSelection(
        pageIndex = point.pageIndex,
        xPoints = point.xPoints,
        yPoints = point.yPoints,
        anchorOnScreen = tentativeAnchor,
    )
    android.util.Log.i(
        "RifflePdfSel",
        "long-press → beginPendingSelection ($started) at PDF page=${point.pageIndex} " +
            "x=${point.xPoints} y=${point.yPoints}",
    )
    pdfView.invalidate()
}

