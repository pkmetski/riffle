package com.riffle.app.feature.reader

import android.graphics.Rect
import androidx.compose.ui.unit.IntRect
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

/**
 * The subset of [ChapterWebView]'s callback surface that [ChapterWebViewBinder] wires. Extracted
 * as an interface (rather than operating on [ChapterWebView] directly) so tests can drive the
 * binder against a hand-written fake instead of spinning up a live [android.webkit.WebView].
 */
internal interface ChapterWebViewLike {
    val chapterHref: String
    fun evaluateJavascript(script: String, resultCallback: ((String?) -> Unit)?)
    var onTap: (() -> Unit)?
    var onRenderGone: (() -> Unit)?
    var onInternalLink: ((String) -> Unit)?
    var onExternalLink: ((String) -> Unit)?
    var annotationsAvailable: Boolean
    var readaloudAvailable: Boolean
    var onSelectionActiveChanged: ((Boolean) -> Unit)?
    var onHighlight: ((String, Double, Rect, String, String) -> Unit)?
    var onAnnotationTap: ((String, Rect) -> Unit)?
    var onAnnotationNoteTap: ((String, Rect) -> Unit)?
    var onFootnoteContent: ((FootnoteContent) -> Unit)?
    var onCrossReferenceTap: ((String) -> Unit)?
    var onFigureTap: ((String) -> Unit)?
    var onFigureLongPress: ((FigureLongPressPayload) -> Unit)?
    /**
     * Last text-selection action-mode rect converted to screen coordinates, captured in
     * [ChapterWebView.onGetContentRect] after the native selection geometry is known and scroll
     * has stabilised. Used by [ChapterWebViewBinder] to anchor the annotation popup at the
     * correct screen position, bypassing the getBoundingClientRect one-frame lag (see
     * ContinuousPositionTracker.chapterWebViewWindowOffset KDoc for the lag explanation).
     * Null until the first action mode fires, or in test fakes.
     */
    val lastActionModeScreenRect: IntRect?
}

/**
 * Returns the screen-space rect to anchor the annotation popup to.
 *
 * Prefers [ChapterWebViewLike.lastActionModeScreenRect] (captured from [android.view.ActionMode.Callback2.onGetContentRect]
 * after Chromium's scroll stabilises) over the CSS-derived [screenRectOf] fallback, which lags by
 * one Chromium frame relative to the sliding-window [android.view.View.setTranslationY] in
 * continuous mode (see ContinuousPositionTracker.chapterWebViewWindowOffset KDoc).
 */
internal fun resolveHighlightAnchor(
    wv: ChapterWebViewLike,
    rect: Rect,
    screenRectOf: (ChapterWebViewLike, Rect) -> Rect,
): IntRect = wv.lastActionModeScreenRect
    ?: screenRectOf(wv, rect).let { s -> IntRect(s.left, s.top, s.right, s.bottom) }

/**
 * Wires every event callback on a [ChapterWebViewLike] to the three sink interfaces, folding the
 * screen-coordinate transform for tap rects, the render-recovery hook, and the selection-active
 * counter update into one place.
 *
 * Introduced to dedup the identical wire-up previously inlined in
 * [ContinuousReaderView.appendChapter] and [ContinuousReaderView.prependChapter], and to give the
 * annotation-tap coordinate transform a JVM-testable seam that doesn't need a live WebView.
 *
 * Does NOT resolve `onInternalLink` itself and does NOT wire `onPlayFromHere`:
 * - Internal-link resolution needs `publication.readingOrder` + the latest [Locator] + a
 *   same-document navigateTo fallback — state the binder does not have. The raw href is instead
 *   forwarded to [onInternalLink], a View-owned callback (mirrors [onRenderGone]) that the
 *   coordinator supplies with that resolution logic.
 * - Sentence-scoped play-from-here needs publication state
 *   ([ContinuousReaderCoordinator]'s `sentenceQuotesProvider` / `sentenceChaptersProvider`) that
 *   this binder does not have. [ContinuousReaderView] retains that wiring directly.
 */
internal class ChapterWebViewBinder(
    private val navigation: ContinuousNavigationSink,
    private val links: ContinuousLinkSink,
    private val annotations: ContinuousAnnotationSink,
    private val screenRectOf: (ChapterWebViewLike, Rect) -> Rect,
    private val onRenderGone: () -> Unit,
    private val onInternalLink: (href: String) -> Unit,
    private val onCrossReference: (chapterHref: String, fragmentId: String) -> Unit,
    private val onSelectionActiveChanged: (Boolean) -> Unit,
    private val onFigureTap: (payload: String) -> Unit = {},
    private val onFigureLongPress: (payload: FigureLongPressPayload, anchorRect: IntRect) -> Unit = { _, _ -> },
    private val densityOf: (ChapterWebViewLike) -> Float = {
        (it as? android.view.View)?.resources?.displayMetrics?.density ?: 1f
    },
) {
    fun bind(wv: ChapterWebViewLike, annotationsAvailable: Boolean, readaloudAvailable: Boolean) {
        wv.onTap = { navigation.onTap() }
        wv.onRenderGone = { onRenderGone() }
        wv.onInternalLink = { onInternalLink(it) }
        wv.onCrossReferenceTap = { id -> onCrossReference(wv.chapterHref, id) }
        wv.onExternalLink = { links.onExternalLink(it) }
        wv.annotationsAvailable = annotationsAvailable
        wv.onSelectionActiveChanged = onSelectionActiveChanged
        wv.onHighlight = { text, prog, rect, before, after ->
            val anchorIntRect = resolveHighlightAnchor(wv, rect, screenRectOf)
            val locator = Locator.fromJSON(
                JSONObject()
                    .put("href", wv.chapterHref)
                    .put("type", "application/xhtml+xml")
                    .put("locations", JSONObject().put("progression", prog))
                    .put("text", JSONObject()
                        .put("before", before)
                        .put("highlight", text)
                        .put("after", after)),
            )
            if (locator != null) {
                annotations.onHighlight(locator, anchorIntRect)
            }
        }
        wv.onAnnotationTap = { id, rect ->
            val s = screenRectOf(wv, rect)
            annotations.onAnnotationTap(id, IntRect(s.left, s.top, s.right, s.bottom))
        }
        wv.onAnnotationNoteTap = { id, rect ->
            val s = screenRectOf(wv, rect)
            annotations.onAnnotationNoteTap(id, IntRect(s.left, s.top, s.right, s.bottom))
        }
        wv.readaloudAvailable = readaloudAvailable
        wv.onFootnoteContent = { links.onFootnote(it) }
        wv.onFigureTap = { payload -> onFigureTap(payload) }
        wv.onFigureLongPress = { payload ->
            val density = densityOf(wv)
            // Built via the no-arg constructor + field assignment rather than Rect(l,t,r,b):
            // that 4-arg constructor is a no-op under the plain-JVM android.jar unit-test stub
            // (see ChapterWebViewBinderTest's rectOf helper), so this form is the only one that
            // behaves identically under JVM tests and on a real device.
            val local = Rect().apply {
                left = (payload.rectX * density).toInt()
                top = (payload.rectY * density).toInt()
                right = ((payload.rectX + payload.rectW) * density).toInt()
                bottom = ((payload.rectY + payload.rectH) * density).toInt()
            }
            val screen = screenRectOf(wv, local)
            onFigureLongPress(payload, IntRect(screen.left, screen.top, screen.right, screen.bottom))
        }
    }
}
