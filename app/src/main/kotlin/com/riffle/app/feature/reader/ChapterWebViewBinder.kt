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
}

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
    // Default no-op is only exercised by callers that don't care (e.g. some test doubles);
    // the real handler is ContinuousReaderView.onFigureLongPress → EpubReaderViewModel.onFigureLongPress.
    private val onFigureLongPress: (payload: FigureLongPressPayload) -> Unit = {},
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
            val screen = screenRectOf(wv, rect)
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
                annotations.onHighlight(
                    locator,
                    IntRect(screen.left, screen.top, screen.right, screen.bottom),
                )
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
        wv.onFigureLongPress = { payload -> onFigureLongPress(payload) }
    }
}
