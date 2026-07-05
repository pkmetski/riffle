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
}
