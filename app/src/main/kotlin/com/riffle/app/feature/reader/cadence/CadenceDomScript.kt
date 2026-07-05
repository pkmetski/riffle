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
            // Some EPUBs (including O'Reilly-style "Philosophy of Software Design") embed a
            // TOC / landmarks <nav> at the top of the reading resource. Its link labels are
            // real text nodes but they are NOT part of the reading flow — tokenising them
            // would make Cadence highlight "3.4 Startups and investment" (a TOC label) instead
            // of the paragraph the user is looking at, and Readium's decoration engine would
            // snap-scroll to that TOC label. Reject any text node whose ancestry contains a
            // <nav>, an epub:type of toc/landmarks/page-list, or role='doc-toc'.
            function inNavAncestry(el) {
              for (var p = el; p; p = p.parentNode) {
                if (!p || p.nodeType !== 1) continue;
                var tag = (p.tagName || '').toLowerCase();
                if (tag === 'nav') return true;
                var t = p.getAttribute && (p.getAttribute('epub:type') || p.getAttribute('role') || '');
                if (t && /toc|landmarks|page-list|doc-toc/i.test(t)) return true;
              }
              return false;
            }
            for (const node of textNodes) {
              const parent = node.parentNode;
              if (!parent) continue;
              // Skip empty / whitespace-only nodes — they carry no sentence and produce noise.
              if (!/\S/.test(node.nodeValue)) continue;
              // Skip TOC / navigation labels.
              if (inNavAncestry(parent)) continue;
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
     * Returns the id (e.g. `"cd-7"`) of the first `<span class="riffle-cd">` currently visible in
     * the paginated/vertical viewport, or an empty string when none is on-screen. Uses Cadence's
     * own injected span ids — far more reliable than the 12-char text-prefix probe
     * ([com.riffle.app.feature.reader.firstVisibleSentenceJs]) which false-positives when
     * different Cadence-tokenised sentences share a common opening ("The problem…", "The
     * solution…"). Reading-order iteration guarantees the topmost visible span wins.
     */
    const val FIRST_VISIBLE_CADENCE_SPAN_ID_JS: String = """
    (function(){
      var spans=document.querySelectorAll('span.riffle-cd');
      if (!spans || spans.length===0) return '';
      var iw=window.innerWidth||0, ih=window.innerHeight||0;
      for (var i=0; i<spans.length; i++) {
        var el=spans[i];
        var r=el.getBoundingClientRect();
        if (!r) continue;
        if (r.width===0 && r.height===0) continue;
        if (r.top < ih && r.bottom > 0 && r.left < iw && r.right > 0) return el.id;
      }
      return '';
    })()
    """
}
