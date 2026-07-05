package com.riffle.app.feature.reader.cadence

/**
 * The JavaScript that Cadence injects into the reader WebView to (a) feature-detect
 * `window.Intl.Segmenter`, and (b) tokenise the current chapter DOM into per-sentence
 * `<span id="cd-N">` wrappers plus the paired fragment→quote map.
 *
 * The Kotlin-side [DomSentenceSource] consumes the returned JSON via
 * `WebView.evaluateJavascript(...)`; the actual bridging call lives in the reader's script-injector
 * layer (mirroring `ContinuousStyleInjector`'s Readaloud highlight injection).
 *
 * Kept in one place so the JS contract is stable and inspectable. The two entry points are:
 *  - [FEATURE_DETECT_JS] — evaluates to `true` when `Intl.Segmenter` is present. Cadence hides its
 *    top-bar toggle when this is `false`, matching the "no fallback" WebView gate from issue #403.
 *  - [tokeniseChapterJs] — returns a JSON string with two maps:
 *      { "quotes": { "cd-N": { "before": …, "highlight": …, "after": … } },
 *        "chapterHrefs": { "cd-N": "chapter-href" } }
 *    The `cd-N` ids match those the JS wraps around every sentence, matching the
 *    `FragmentRef = "href#spanId"` contract in [com.riffle.core.domain.sentence.SentenceSource].
 */
internal object CadenceDomScript {

    /** Evaluates to `"true"` / `"false"` (JSON) — Cadence's WebView-availability gate. */
    const val FEATURE_DETECT_JS: String =
        "(function(){return typeof (window.Intl && window.Intl.Segmenter) !== 'undefined';})()"

    /**
     * Return the JS that tokenises the current chapter document into `<span id="cd-N">` wrappers.
     *
     * [chapterHref] scopes fragment refs to the currently-rendered resource — the Kotlin side of
     * Cadence uses this key to route auto-follow to the right chapter.
     *
     * [localeTag] is the EPUB's `xml:lang` (or `document.documentElement.lang`); missing values
     * fall through to the WebView's default locale so `Intl.Segmenter` still segments sensibly.
     */
    fun tokeniseChapterJs(chapterHref: String, localeTag: String?): String {
        val localeArg = if (localeTag.isNullOrBlank()) "undefined" else "'${jsEscape(localeTag)}'"
        val hrefLit = "'${jsEscape(chapterHref)}'"
        // language=js
        return """
        (function(){
          try {
            if (typeof (window.Intl && window.Intl.Segmenter) === 'undefined') {
              return JSON.stringify({ quotes: {}, chapterHrefs: {}, supported: false });
            }
            const chapterHref = $hrefLit;
            // Idempotency: paginationListener.onPageLoaded fires several times per chapter
            // (initial paint, reflow after typography, backward-turn re-lay). Running the walk
            // again would wrap ALREADY-WRAPPED text — creating nested spans, duplicate ids, and
            // zero-sized bounding rects that break the "first visible span" probe. Bail out and
            // just re-read the existing spans if any are present.
            const existing = document.querySelectorAll('span.riffle-cd');
            if (existing.length > 0) {
              const quotes = {};
              const chapterHrefs = {};
              existing.forEach(function(el) {
                if (!el.id) return;
                const ref = chapterHref + '#' + el.id;
                quotes[ref] = { before: '', highlight: (el.textContent || '').trim(), after: '' };
                chapterHrefs[ref] = chapterHref;
              });
              return JSON.stringify({ quotes: quotes, chapterHrefs: chapterHrefs, supported: true, cached: true });
            }
            const seg = new Intl.Segmenter($localeArg, { granularity: 'sentence' });
            const quotes = {};
            const chapterHrefs = {};
            // Walk in reading order so cd-0, cd-1, cd-2 line up with visible text.
            const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
            let idx = 0;
            const textNodes = [];
            let n = walker.nextNode();
            while (n) { textNodes.push(n); n = walker.nextNode(); }
            for (const node of textNodes) {
              const parent = node.parentNode;
              if (!parent) continue;
              // Skip empty / whitespace-only nodes — they carry no sentence and produce noise.
              if (!/\S/.test(node.nodeValue)) continue;
              const parts = [];
              for (const s of seg.segment(node.nodeValue)) {
                if (!s.segment || !/\S/.test(s.segment)) continue;
                parts.push(s.segment);
              }
              if (parts.length === 0) continue;
              const frag = document.createDocumentFragment();
              for (const part of parts) {
                const span = document.createElement('span');
                const id = 'cd-' + idx;
                span.id = id;
                span.className = 'riffle-cd';
                span.textContent = part;
                frag.appendChild(span);
                const ref = chapterHref + '#' + id;
                quotes[ref] = { before: '', highlight: part.trim(), after: '' };
                chapterHrefs[ref] = chapterHref;
                idx++;
              }
              parent.replaceChild(frag, node);
            }
            return JSON.stringify({ quotes: quotes, chapterHrefs: chapterHrefs, supported: true });
          } catch (e) {
            return JSON.stringify({ quotes: {}, chapterHrefs: {}, supported: false, error: String(e) });
          }
        })()
        """.trimIndent()
    }

    private fun jsEscape(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "")

    /**
     * JS that returns the id of the first `<span class="riffle-cd">` currently visible on the page,
     * or an empty string when none is on-screen. Uses the Cadence-injected span ids directly (much
     * cheaper + more reliable than [firstVisibleSentenceJs]'s text-prefix probe, which suffers
     * when the DOM's text nodes are chopped up by the sentence-span wrapping itself).
     *
     * The visibility test mirrors the paginated / vertical semantics of
     * [firstVisibleSentenceJs]: a rect that intersects the viewport within a 24 px tolerance.
     */
    const val FIRST_VISIBLE_SPAN_ID_JS: String = """
    (function(){
      var TOL=24;
      var spans=document.querySelectorAll('span.riffle-cd');
      if (!spans || spans.length===0) return '';
      var iw=window.innerWidth||0, ih=window.innerHeight||0;
      // Paginated / Vertical: single-document scroll. Return the FIRST span in reading order
      // whose bounding rect is currently visible in the viewport (top < ih, bottom > 0). Ordered
      // iteration matches document order matches reading order, so the first hit is the topmost.
      for (var i=0; i<spans.length; i++) {
        var el=spans[i];
        var r=el.getBoundingClientRect();
        if (!r || (r.width===0 && r.height===0)) continue;
        if (r.left >= -TOL && r.right <= iw+TOL && r.top < ih && r.bottom > 0) {
          return el.id;
        }
      }
      return '';
    })()
    """

    /**
     * Continuous-mode variant of [FIRST_VISIBLE_SPAN_ID_JS]. The outer NestedScrollView scrolls
     * a stack of content-sized WebViews; a per-WebView `getBoundingClientRect` doesn't know
     * where the outer viewport sits, so we compute the WebView-relative visible band in Kotlin
     * (`[offsetPx, offsetPx + viewportPx]`) and pass it in. This filter returns the FIRST span
     * whose top-Y sits at or below `offsetPx` — i.e. the topmost sentence visible in the outer
     * viewport — otherwise the last span above the range (a partial sentence being scrolled up
     * off the top of the screen).
     */
    fun firstSpanIdInVerticalBandJs(offsetPx: Int, viewportPx: Int): String {
        return """
        (function(){
          var start=$offsetPx, end=$offsetPx + $viewportPx;
          var spans=document.querySelectorAll('span.riffle-cd');
          if (!spans || spans.length===0) return '';
          var lastAbove=null;
          for (var i=0; i<spans.length; i++) {
            var el=spans[i];
            var r=el.getBoundingClientRect();
            if (!r || (r.width===0 && r.height===0)) continue;
            var top = r.top + (window.scrollY||0);
            var bottom = r.bottom + (window.scrollY||0);
            if (top >= start && top < end) return el.id;
            if (bottom > start && top < start) return el.id;
            if (top < start) lastAbove = el;
          }
          return lastAbove ? lastAbove.id : '';
        })()
        """.trimIndent()
    }

}
