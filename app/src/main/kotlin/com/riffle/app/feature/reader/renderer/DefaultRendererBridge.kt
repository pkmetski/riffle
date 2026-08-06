package com.riffle.app.feature.reader.renderer

import com.riffle.app.feature.reader.ColumnSnap
import com.riffle.app.feature.reader.FigureTapBridge
import com.riffle.app.feature.reader.FigureTapScript
import com.riffle.app.feature.reader.FootnoteAnchorBridge
import com.riffle.app.feature.reader.RECT_TO_JSON_POLYFILL_JS
import com.riffle.app.feature.reader.SELECTION_SPAN_TRACKER_JS
import com.riffle.app.feature.reader.decorations.figureBorderApplyJs
import com.riffle.app.feature.reader.decorations.figureBorderInjectionJs
import com.riffle.app.feature.reader.firstVisibleSentenceJs
import com.riffle.app.feature.reader.readaloudReserveApplyJs
import com.riffle.app.feature.reader.readaloudReserveInjectionJs
import com.riffle.app.feature.reader.readiumDecorationTemplatesRegisterJs
import com.riffle.app.feature.reader.resolveSelectionSentenceJs
import com.riffle.app.feature.reader.typographyOverrideInjectionJs
import java.net.URLDecoder
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator

/**
 * The production [RendererBridge]: wraps the currently attached [EpubNavigatorFragment]. Every
 * `evaluateJavascript(` call in paged/vertical mode goes through here — the lint rule enforces
 * the rest of the package keeps its hands off the WebView's JS world.
 *
 * The fragment is supplied by a lambda (not held by reference) so that rotation — which recreates
 * the fragment — doesn't require a swap on the bridge: callers update the [fragmentProvider]'s
 * source-of-truth (e.g. a `mutableStateOf<EpubNavigatorFragment?>(null)`) and the bridge picks
 * the new fragment up on the next call. A null fragment means "no document is currently driven";
 * every method short-circuits to a sensible default (typically a no-op or null).
 *
 * The capability registry is declared once in the companion object: rect polyfill → selection
 * tracker, footnote bridge (both depend on the polyfill); typography override; readaloud reserve;
 * settle backstop. The dependency graph is the contract — the topo sort places dependents after
 * their deps in [installPageCapabilities].
 */
internal class DefaultRendererBridge(
    private val fragmentProvider: () -> EpubNavigatorFragment?,
    private val readaloudReserveProvider: () -> Int,
    override val capabilities: List<RendererCapability> = defaultCapabilities(readaloudReserveProvider),
) : RendererBridge {

    private val installOrder: List<RendererCapability> = topoSortCapabilities(capabilities)

    private val fragment: EpubNavigatorFragment? get() = fragmentProvider()

    override suspend fun installPageCapabilities(): List<CapabilityId> {
        val frag = fragment ?: return emptyList()
        val attempted = ArrayList<CapabilityId>(installOrder.size)
        for (cap in installOrder.filter { it.scope == CapabilityScope.PageLoad }) {
            frag.evaluateJavascript(cap.installScript())
            attempted += cap.id
        }
        return attempted
    }

    override suspend fun applyTypographyOverride() {
        fragment?.evaluateJavascript(typographyOverrideInjectionJs())
    }

    override suspend fun applyReadaloudReserve(reservePx: Int) {
        val frag = fragment ?: return
        frag.evaluateJavascript(readaloudReserveInjectionJs())
        frag.evaluateJavascript(readaloudReserveApplyJs(reservePx))
    }

    override suspend fun applyFigureBorders(
        cssRules: List<String>,
        svgMatches: List<com.riffle.app.feature.reader.decorations.FigureBorderDecoration.SvgMatch>,
        rasterMarks: List<com.riffle.app.feature.reader.decorations.FigureBorderDecoration.RasterMark>,
    ) {
        val frag = fragment ?: return
        frag.evaluateJavascript(figureBorderInjectionJs())
        frag.evaluateJavascript(figureBorderApplyJs(cssRules, svgMatches, rasterMarks))
    }

    override suspend fun installScrollSettleBackstop() {
        fragment?.evaluateJavascript(ColumnSnap.SETTLE_SNAP_INSTALL_JS)
    }

    override suspend fun snapAfterGoTo(link: Link) {
        val frag = fragment ?: return
        frag.evaluateJavascript(ColumnSnap.STASH_VERTICAL_ORIGIN_JS)
        frag.go(link)
        frag.evaluateJavascript(ColumnSnap.snapToTargetColumnJs(navTargetFragmentId(link.href.toString())))
    }

    override suspend fun snapAfterGoTo(
        locator: Locator,
        landAtStartWhenNoTarget: Boolean,
        snapProgressionToNearestColumn: Boolean,
        focusAnnotationId: String?,
    ) {
        val frag = fragment ?: return
        // For TOC/search/resume navigation (landAtStartWhenNoTarget=true), use the fragment id
        // from locations.fragments or the href anchor to snap the JS to the exact target element.
        //
        // For annotation navigation (landAtStartWhenNoTarget=false), skip CFI-derived section-level
        // fragments (the extractAnchorFromCfi ancestor is typically a <section id="ch01"> spanning
        // the whole chapter; getBoundingClientRect().left is 0 for it → always column 0). Instead,
        // use fragments only when withAnnotationNavigationAnchor placed a paragraph-level id there
        // (i.e. a bookmark with a fragmentAnchor captured at creation time from the live page). For
        // highlights the TextQuote is the precise DOM target; for legacy bookmarks the persisted
        // live-reader progression is used as fallback via the else branch below.
        val fragmentId = when {
            landAtStartWhenNoTarget ->
                locator.locations.fragments.firstOrNull()
                    ?: navTargetFragmentId(locator.href.toString())
            locator.locations.fragments.isNotEmpty() ->
                // New-style bookmark: a paragraph-level element id was captured at creation time.
                locator.locations.fragments.firstOrNull()
            else -> null
        }
        // Always pass progression for annotation navigation (landAtStartWhenNoTarget=false) so
        // that if getElementById fails (e.g. element id changed in a revised EPUB), the JS
        // fallback can still snap to the right column using the persisted progression ratio.
        // With Fix 2 ensuring legacy bookmarks have cleared fragments, new-style bookmarks have
        // both fragments AND progression set; the JS tries element first, falls back to progression.
        // For TOC/resume navigation (landAtStartWhenNoTarget=true) we don't provide progression
        // because those jumps always land at the chapter start or a specific element anchor.
        val progression = if (!landAtStartWhenNoTarget) locator.locations.progression else null
        // A TextQuote locator lets Readium resolve the exact DOM range. Pass the complete locator
        // into the reflow tracker so it can repeat that exact resolution after each scrollWidth
        // change; progression is only a fallback when the quote is absent/stale.
        val exactLocatorJson = locator.text.highlight
            ?.takeIf { it.isNotBlank() }
            ?.let { locator.toJSON().toString() }
        // Stash the pre-go scroll origin so the post-go smooth-tail can start from where the
        // user actually was on same-doc jumps (return-to-position card, same-chapter TOC entry)
        // instead of pre-landing half a viewport short — which flashes as "goes back a bit and
        // then returns" because there is no nav cover to hide it on same-doc. Cross-doc jumps
        // wipe the stash (new document, new JS context) and fall back to pre-land, which the
        // cover hides.
        frag.evaluateJavascript(ColumnSnap.STASH_VERTICAL_ORIGIN_JS)
        frag.go(locator)
        frag.evaluateJavascript(
            ColumnSnap.snapToTargetColumnJs(
                fragmentId = fragmentId,
                landAtStartWhenNoTarget = landAtStartWhenNoTarget,
                locatorProgression = progression,
                locatorJson = exactLocatorJson,
                snapProgressionToNearestColumn = snapProgressionToNearestColumn,
                focusAnnotationId = focusAnnotationId,
            ),
        )
    }

    override suspend fun snapToEnd() {
        fragment?.evaluateJavascript(ColumnSnap.snapToEndColumnJs())
    }

    override suspend fun snapToElement(fragmentId: String): Boolean =
        fragment?.evaluateJavascript(ColumnSnap.scrollToColumnJs(fragmentId))?.trim('"') == "moved"

    override suspend fun snapCadenceSpan(fragmentId: String): String? =
        // Readaloud follow ticks every sentence and needs the highlight to stay locked to the
        // audio; a 250 ms tween per tick would visually drift because the supersede counter
        // cancels in-flight animations before they land. Instant snap here (animated=false);
        // internal-link taps still animate via [snapToElement].
        fragment?.evaluateJavascript(ColumnSnap.scrollToColumnJs(fragmentId, animated = false))?.trim('"')

    override suspend fun landedAtEnd(): Boolean =
        fragment?.evaluateJavascript(ColumnSnap.LANDED_AT_END_JS)?.trim('"') == "true"

    override suspend fun followNarratedSentence(text: String): String? =
        fragment?.evaluateJavascript(ColumnSnap.autoFollowSnapJs(text))?.trim('"')

    override suspend fun measureNarratedColumns(text: String): List<Double> {
        val raw = fragment?.evaluateJavascript(ColumnSnap.measureNarratedColumnsJs(text))
        return ColumnSnap.parseNarratedColumnsResult(raw)
    }

    override suspend fun snapNarratedColumn(text: String, columnIndex: Int) {
        fragment?.evaluateJavascript(ColumnSnap.snapNarratedColumnJs(text, columnIndex))
    }

    override suspend fun measureCadenceColumns(fragmentId: String): List<Double> {
        val raw = fragment?.evaluateJavascript(ColumnSnap.measureCadenceColumnsJs(fragmentId))
        return ColumnSnap.parseNarratedColumnsResult(raw)
    }

    override suspend fun snapCadenceColumn(fragmentId: String, columnIndex: Int) {
        fragment?.evaluateJavascript(ColumnSnap.snapCadenceColumnJs(fragmentId, columnIndex))
    }

    override suspend fun resolveSelectionSentence(sentences: List<Pair<String, String>>): String? {
        val frag = fragment ?: return null
        if (sentences.isEmpty()) return null
        return frag.evaluateJavascript(resolveSelectionSentenceJs(sentences))
            ?.trim('"')?.takeIf { it.isNotEmpty() }
    }

    override suspend fun readSelectionSpanId(): String? =
        fragment?.evaluateJavascript("window.__riffleSelSpan || ''")
            ?.trim('"')?.takeIf { it.isNotEmpty() }

    override suspend fun firstVisibleSentenceIndex(highlights: List<String>): Int? =
        fragment?.evaluateJavascript(firstVisibleSentenceJs(highlights))
            ?.trim('"')?.toIntOrNull()

    override suspend fun scrollByPx(delta: Int): Boolean? {
        // Return a plain integer (1 = moved, 0 = stuck). Returning a JSON object and parsing it
        // as a string is a trap: evaluateJavascript JSON-encodes the JS return value, so a
        // JSON-stringified object comes back as a doubly-encoded string ('"{\"moved\":true}"').
        // Null (here AND from evaluateJavascript) means "page gone" — the caller distinguishes
        // that from "stuck at end" so it doesn't fire end-of-book signals on a transient swap.
        val frag = fragment ?: return null
        val raw = frag.evaluateJavascript(
            """
            (function(){
              var before = window.scrollY;
              window.scrollBy(0, $delta);
              return window.scrollY !== before ? 1 : 0;
            })()
            """.trimIndent(),
        ) ?: return null
        return raw.trim().trim('"') == "1"
    }

    override suspend fun scrollBoundary(): Pair<Boolean, Boolean> {
        val frag = fragment ?: return Pair(false, false)
        // Two separate JS calls so the failure mode is obvious if either ever returns malformed
        // JSON; both are pure reads of scroll state — no writes here.
        val atForward = frag.evaluateJavascript(
            "(window.scrollY + window.innerHeight >= document.body.scrollHeight - 4).toString()",
        )?.trim('"') == "true"
        val atBackward = frag.evaluateJavascript(
            "(window.scrollY <= 4).toString()",
        )?.trim('"') == "true"
        return Pair(atForward, atBackward)
    }

    override suspend fun evaluateBoundaryScroll(js: String) {
        fragment?.evaluateJavascript(js)
    }

    override suspend fun capturePageFragmentAnchor(): String? {
        val raw = fragment?.evaluateJavascript(ColumnSnap.capturePageFragmentAnchorJs()) ?: return null
        val trimmed = raw.trim('"')
        return if (trimmed == "null" || trimmed.isBlank()) null else trimmed
    }

    override suspend fun evaluateCadenceFeatureDetect(): String? =
        fragment?.evaluateJavascript(
            com.riffle.app.feature.reader.cadence.CadenceDomScript.FEATURE_DETECT_JS,
        )

    override suspend fun evaluateCadenceTokenise(chapterHref: String, localeTag: String?): String? =
        fragment?.evaluateJavascript(
            com.riffle.app.feature.reader.cadence.CadenceDomScript.tokeniseChapterJs(
                chapterHref,
                localeTag,
            ),
        )

    override suspend fun cadenceStartSpanId(): String? {
        // Nulls fall through to `window.scrollY` / `innerHeight` — correct for Readium modes
        // where the WebView owns its scroll. See CadenceDomScript.parseCadenceStartId for the
        // JSON diagnostic payload the JS returns.
        val raw = fragment?.evaluateJavascript(
            com.riffle.app.feature.reader.cadence.CadenceDomScript.cadenceStartSpanIdJs(),
        )
        return com.riffle.app.feature.reader.cadence.CadenceDomScript.parseCadenceStartId(raw)
    }

    override suspend fun applyHighlightDomPatch(
        patch: com.riffle.app.feature.reader.highlights.HighlightsDomPatch,
    ) {
        fragment?.evaluateJavascript(patch.applyJs())
    }

    override suspend fun readViewportFraction(): Double? {
        val frag = fragment ?: return null
        val raw = frag.evaluateJavascript(
            """
            (function() {
              var iw = window.innerWidth, sw = document.documentElement.scrollWidth;
              var ih = window.innerHeight, sh = document.documentElement.scrollHeight;
              // Pick the overflow axis. Paginated overflows horizontally; vertical/no-overflow
              // fall through to the height ratio.
              var v = sw > iw ? (iw / sw) : (ih > 0 ? ih / sh : 0);
              return isFinite(v) && v > 0 ? v.toString() : "";
            })()
            """.trimIndent(),
        )?.trim('"') ?: return null
        return raw.toDoubleOrNull()
    }

    companion object {
        /**
         * The capability registry shared by every paged/vertical session. The dependency graph
         * is THE contract: the rect-toJSON polyfill must be installed before either the footnote
         * bridge or the selection tracker (both call getBoundingClientRect().toJSON()).
         */
        fun defaultCapabilities(readaloudReserveProvider: () -> Int): List<RendererCapability> = listOf(
            RendererCapability(
                id = CapabilityId.RectToJsonPolyfill,
                installScript = { RECT_TO_JSON_POLYFILL_JS },
            ),
            RendererCapability(
                id = CapabilityId.SelectionSpanTracker,
                dependsOn = setOf(CapabilityId.RectToJsonPolyfill),
                installScript = { SELECTION_SPAN_TRACKER_JS },
            ),
            RendererCapability(
                id = CapabilityId.TypographyOverride,
                installScript = { typographyOverrideInjectionJs() },
            ),
            RendererCapability(
                id = CapabilityId.FootnoteAnchorBridge,
                dependsOn = setOf(CapabilityId.RectToJsonPolyfill),
                installScript = { FootnoteAnchorBridge.INSTALL_SCRIPT },
            ),
            RendererCapability(
                id = CapabilityId.ReadaloudReserve,
                installScript = {
                    // Install the rule AND apply the current value on the same install pass —
                    // the freshly loaded resource is a new document, so both have to land for the
                    // reserve to take effect on this page.
                    readaloudReserveInjectionJs() + ";" + readaloudReserveApplyJs(readaloudReserveProvider())
                },
            ),
            RendererCapability(
                id = CapabilityId.ScrollSettleBackstop,
                installScript = { ColumnSnap.SETTLE_SNAP_INSTALL_JS },
            ),
            RendererCapability(
                id = CapabilityId.DecorationTemplateReregister,
                dependsOn = setOf(CapabilityId.RectToJsonPolyfill),
                installScript = { readiumDecorationTemplatesRegisterJs() },
            ),
            RendererCapability(
                id = CapabilityId.FigureTapBridge,
                dependsOn = setOf(CapabilityId.RectToJsonPolyfill),
                installScript = { FigureTapScript.installScript(FigureTapBridge.JS_NAME) },
            ),
        )

        private fun navTargetFragmentId(href: String): String? =
            href.substringAfter('#', "").ifEmpty { null }
                ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
    }
}
