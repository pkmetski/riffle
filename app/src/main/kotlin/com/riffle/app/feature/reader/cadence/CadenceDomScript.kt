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
            // Stamp the chapter href onto the DOCUMENT so the start-position resolver can read it
            // back later. Without this the resolver builds `startRef = readiumLocatorHref + '#' + id`
            // — and Readium's WebView reuses the same DOM across chapter turns, so
            // `wv.chapterHref` (the Kotlin field we passed in as tokenise argument) can lag one
            // chapter behind the currently-rendered content. Tokenise ends up filing cd-N under
            // chapter X-1's href in the merged quotes map, and Start's built ref for chapter X
            // isn't in the ticker's ordered list → goTo silently no-ops → playback falls to cd-0
            // of the first-tokenised chapter (Cover Design credits) and the highlight decoration
            // lands on THAT chapter — off-screen from what the user is looking at.
            // The resolver reads this attribute to build a ref that's guaranteed to exist in the
            // ticker's ordered list.
            document.documentElement.setAttribute('data-riffle-chapter', chapterHref);
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
            // Heading detection for books whose section titles are NOT semantic HTML (no h1..h6,
            // no epub:type, no ARIA — just styled <p>/<div> with obfuscated class names, as in
            // "A Philosophy of Software Design 2nd ed"). We tag heading-like parents with a
            // `.riffle-heading` marker class here so `cadenceStartSpanIdJs`'s selector list can
            // find them regardless of semantic markup. Three heuristics, any one qualifies:
            //   (a) font-size is meaningfully larger than the body baseline (1.15×),
            //   (b) font-weight ≥ 600 on a block-level element (bold heading paragraph),
            //   (c) the text starts with a numbered-section pattern like "3.2 Strategic…".
            const baselineFontSize = parseFloat(getComputedStyle(document.body).fontSize) || 16;
            function parentLooksLikeHeading(parent) {
              if (!parent || parent.nodeType !== 1) return false;
              const cs = getComputedStyle(parent);
              const fs = parseFloat(cs.fontSize) || baselineFontSize;
              if (fs > baselineFontSize * 1.15) return true;
              const fw = parseInt(cs.fontWeight, 10) || 400;
              const disp = cs.display || '';
              if (fw >= 600 && (disp === 'block' || disp === 'inline-block' || disp === 'grid' || disp === 'flex')) return true;
              return false;
            }
            const SECTION_NUMBER_RE = /^\s*(\d+(\.\d+)*|[A-Z])\.?\s+[A-Z]/;
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
              // Heading heuristic (see `parentLooksLikeHeading` docstring). Tag idempotently —
              // multiple text-node children of the same heading all trigger this line.
              if (parentLooksLikeHeading(parent) || SECTION_NUMBER_RE.test(node.nodeValue)) {
                if (parent.classList) parent.classList.add('riffle-heading');
              }
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
     * Parse the diagnostic JSON payload returned by [cadenceStartSpanIdJs] and extract the picked
     * span id — prefixed with the JS-provided chapter href when present, so the caller gets a
     * full fragment ref (`"chapter#id"`) that is guaranteed to match a key in the ticker's
     * ordered fragment list. When the JS payload has no chapter (older resolver, DOM never
     * tokenised, `<html data-riffle-chapter="">`), returns just the bare id and lets the caller
     * fall back to the Readium locator href.
     *
     * Logs the whole payload under [com.riffle.core.logging.LogChannel.Cadence] so a wrong start
     * position can be reasoned about from logcat alone. Returns null when the JS came back
     * empty, unparseable, or with `id = ""` (no rule matched).
     *
     * Why the chapter prefix: Readium reuses the same WebView across chapter turns; its Kotlin
     * `wv.chapterHref` field can trail the DOM by one chapter after a paginated turn. Building
     * the ref off `readiumLocatorHref` therefore produces a ref for the WRONG chapter, missing
     * from the ticker's ordered list, and playback starts at position 0 (Cover Design credits)
     * instead of what the user is looking at. Stamping the tokenised chapter onto `<html>` and
     * echoing it back in the resolver payload makes the ref chapter-authoritative.
     */
    fun parseCadenceStartId(raw: String?): String? {
        if (raw == null) return null
        runCatching {
            android.util.Log.d(com.riffle.core.logging.LogChannel.Cadence.tag, "cadenceStartSpanId raw=$raw")
        }
        // evaluateJavascript double-encodes the return: a JSON object comes back as a JSON-encoded
        // string containing that object. Unwrap one layer of quoting + escape.
        val unwrapped = raw.trim().let {
            if (it.startsWith("\"") && it.endsWith("\"") && it.length >= 2) {
                it.substring(1, it.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            } else it
        }
        return try {
            val obj = org.json.JSONObject(unwrapped)
            val id = obj.optString("id", "").takeIf { it.isNotEmpty() } ?: return null
            val chapter = obj.optString("chapter", "")
            if (chapter.isNotEmpty()) "$chapter#$id" else id
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Returns the id (e.g. `"cd-7"`) of the Cadence sentence Cadence should START from.
     *
     * The choice is section-aware, in priority order:
     *  1. **First heading `h1..h6` currently visible in the viewport** (document order) → return
     *     the id of the first `.riffle-cd` at-or-inside that heading. The user sees a section
     *     title on the page, taps play, Cadence starts by reading that title — deterministic
     *     and matches the reader's mental model of "start of the section I'm looking at".
     *  2. **Nearest preceding heading if none is visible** — the user scrolled past the heading
     *     but is still inside its section. Same handling as (1) — start at the section's first
     *     `.riffle-cd`. This is bounded by the natural upper limit of "the closest heading in
     *     document order" so we don't rewind arbitrarily; sections rarely span many pages.
     *  3. **No heading precedes (front matter, cover, chapter opener before the first heading)**
     *     → fall back to the first `.riffle-cd` currently visible in the viewport. Uses
     *     `document.elementsFromPoint` (plural) so decoration overlays (Readium search highlights,
     *     existing annotation marks — absolutely-positioned `<div>`s stacked above the text)
     *     don't block the parent-walk. Three x-samples (¼/½/¾) route around narrow-column edge
     *     cases.
     *
     * Nav / TOC headings (`<nav>` ancestry or `epub:type` in `toc|landmarks|page-list|doc-toc`,
     * `role='doc-toc'`) are excluded from (1)/(2) with the same guard as
     * [tokeniseChapterJs] — otherwise the "3.4 Startups and investment" TOC label would win
     * over the actual chapter heading below it.
     *
     * Rules (1) and (2) both walk headings in document order and only trigger `firstCdAfter`
     * for a heading NOT inside nav. If a heading has no following `.riffle-cd` (empty section),
     * the search continues.
     *
     * Reading-order iteration guarantees the first-from-top heading wins when several are
     * visible on a long page.
     */
    /**
     * The default (Readium/paginated/vertical) form, evaluated with viewport bounds derived
     * from `window.scrollY` / `window.innerHeight` / `window.innerWidth`. Continuous mode must
     * override those with the parent scroll container's projection — see [cadenceStartSpanIdJs].
     */
    const val CADENCE_START_SPAN_ID_JS: String = ""

    /**
     * Returns the JS that resolves the Cadence start-span id.
     *
     * The three viewport params describe the region of the DOCUMENT (in CSS px) that is
     * currently under the reader's viewport:
     *  - [viewportTopDocPx] is the y-in-document that maps to the top of the reader's visible
     *    area. For Readium (paginated/vertical) this is `window.scrollY`; for continuous
     *    ChapterWebViews the WebView doesn't scroll itself, so it's `parentScrollY - slot.top`.
     *  - [viewportHeightPx] is the reader viewport's height in CSS px.
     *  - [viewportLeftDocPx] / [viewportWidthPx] handle the horizontal analogue for paginated
     *    column layouts (elements laid out horizontally by CSS columns). For continuous this is
     *    `(0, chapterViewportWidth)`.
     *
     * Pass `null` for any bound to fall back to the WebView's own `window` value at eval time.
     * That's the correct choice in Readium modes where the WebView itself owns the scroll.
     */
    fun cadenceStartSpanIdJs(
        viewportTopDocPx: Int? = null,
        viewportHeightPx: Int? = null,
        viewportLeftDocPx: Int? = null,
        viewportWidthPx: Int? = null,
    ): String {
        // Passing null means "let the JS read the WebView's own scroll" — that path yields CSS
        // pixels. Passing a non-null value from the continuous branch yields ANDROID device
        // pixels (they come out of `port.currentScrollY` / `port.viewportHeightPx`). To make
        // both paths comparable inside the JS we scale the null-path CSS values UP to device
        // px by multiplying by `window.devicePixelRatio`. The JS then multiplies every
        // `getBoundingClientRect()` return by dpr in `docRect` so all comparisons land in the
        // same unit. Skipping this scaling was the bug behind Rule 2 firing on the *wrong*
        // section: viewportTop reported in device px was many-x larger than a CSS-px rect
        // and no heading ever tested visible.
        val vt = viewportTopDocPx?.toString() ?: "window.scrollY * (window.devicePixelRatio || 1)"
        val vh = viewportHeightPx?.toString() ?: "window.innerHeight * (window.devicePixelRatio || 1)"
        val vl = viewportLeftDocPx?.toString() ?: "window.scrollX * (window.devicePixelRatio || 1)"
        val vw = viewportWidthPx?.toString() ?: "window.innerWidth * (window.devicePixelRatio || 1)"
        // language=js
        return """
    (function(){
      // Viewport rectangle in DOCUMENT coordinates, always in ANDROID DEVICE PIXELS. See the
      // Kotlin wrapper's docstring for how the bounds are derived and why the unit is fixed
      // to device px in both callers.
      var VT = $vt|0, VH = $vh|0, VL = $vl|0, VW = $vw|0;
      var VB = VT + VH, VR = VL + VW;
      var DPR = window.devicePixelRatio || 1;
      var iw=window.innerWidth||0, ih=window.innerHeight||0;

      function inNav(el) {
        for (var p = el; p; p = p.parentNode) {
          if (!p || p.nodeType !== 1) continue;
          var tag = (p.tagName || '').toLowerCase();
          if (tag === 'nav') return true;
          var t = p.getAttribute && (p.getAttribute('epub:type') || p.getAttribute('role') || '');
          if (t && /toc|landmarks|page-list|doc-toc/i.test(t)) return true;
        }
        return false;
      }

      // getBoundingClientRect returns viewport-relative CSS pixels. Fold in the WebView's own
      // scroll to get document-y (in Readium the WebView scrolls; in continuous window.scrollY
      // is always 0 and this reduces to `rect.top`). Then multiply by devicePixelRatio to lift
      // the result into ANDROID DEVICE PIXELS — matching the unit VT/VH/VL/VW are already in.
      function docRect(r) {
        return { top: (r.top + window.scrollY) * DPR, bottom: (r.bottom + window.scrollY) * DPR,
                 left: (r.left + window.scrollX) * DPR, right: (r.right + window.scrollX) * DPR };
      }
      function isVisible(el) {
        var d = docRect(el.getBoundingClientRect());
        return d.top < VB && d.bottom > VT && d.left < VR && d.right > VL;
      }
      function isPreceding(el) {
        var d = docRect(el.getBoundingClientRect());
        return d.bottom <= VT || d.right <= VL;
      }

      function firstCdAtOrAfter(el) {
        var w = document.createTreeWalker(document.body, NodeFilter.SHOW_ELEMENT, {
          acceptNode: function(n) {
            return (n.classList && n.classList.contains('riffle-cd'))
              ? NodeFilter.FILTER_ACCEPT
              : NodeFilter.FILTER_SKIP;
          }
        });
        w.currentNode = el;
        // TreeWalker.nextNode() descends into el first (its .riffle-cd descendants — heading
        // text is tokenised too), then continues in document order. So the heading's own
        // sentence-span is picked up as the section's opening line, which is what we want.
        var next = w.nextNode();
        return next ? next.id : '';
      }

      // Debug: preferred cd is INSIDE heading (first tokenised span of heading text). If no
      // such descendant exists (heading not tokenised), fall through to the next .riffle-cd
      // in document order (which is normally the section's first BODY sentence). We log both
      // so the "highlight lands mid-section" bug can be reasoned about from logcat alone.
      function cdInsideEl(el) {
        var found = el.querySelector ? el.querySelector('.riffle-cd') : null;
        return found ? found.id : '';
      }

      // Debug channel: return JSON with the picked id AND diagnostic counters so the Kotlin
      // side can log what actually happened per Start. The bridge extracts `.id` and treats
      // everything else as opaque telemetry — see DefaultRendererBridge.cadenceStartSpanId.
      // The chapter this DOM was tokenised for. Read from the attribute the tokeniser stamped
      // onto <html>. Empty when the DOM hasn't been tokenised yet (rare — see the tokenise call
      // rationale). Kotlin uses this to build a ref that is guaranteed to match the ticker's
      // ordered list, avoiding the Readium-locator-href-lag bug.
      var chapter = (document.documentElement.getAttribute('data-riffle-chapter') || '');
      var dbg = { id: '', rule: 0, chapter: chapter, totalHeadings: 0, visibleHeadings: 0, precedingHeadings: 0, firstHeadingText: '', firstHeadingRect: null, iw: iw, ih: ih, vt: VT, vh: VH, vl: VL, vw: VW };

      // Rules (1) + (2) — enumerate heading-like elements in document order; a visible one
      // wins immediately; otherwise remember the last preceding one for the fallback.
      //
      // Selector coverage in priority of confidence:
      //   * Semantic HTML: h1..h6 (obvious)
      //   * ARIA: [role="heading"] (some EPUBs use this on <p>/<div>)
      //   * EPUB 3 structural semantics: attributes like `epub:type="title"` /
      //     "subtitle"/"chapter"/"subchapter"/"part"/"halftitle"/"titlepage" mark
      //     section-opening elements in publisher toolchains that don't use h1..h6.
      //   * Common class conventions across O'Reilly, Manning, Pragmatic, self-published
      //     epubs: `.chapter-title`, `.chapter-heading`, `.section-title`, `.subchapter`,
      //     `.title`, `.heading`, and the `.h1`..`.h6` visual-only class variants.
      //
      // If a book renders section titles with something entirely different (e.g. a bare
      // `<p>` with only inline styles) none of these will match; the resolver falls through
      // to Rule 3 and we log the picked cd's ancestor chain so a follow-up patch can add
      // the missing convention.
      var HEADING_SELECTOR = [
        'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
        '[role="heading"]',
        '[epub\\:type~="title"]',
        '[epub\\:type~="subtitle"]',
        '[epub\\:type~="chapter"]',
        '[epub\\:type~="subchapter"]',
        '[epub\\:type~="part"]',
        '[epub\\:type~="halftitle"]',
        '[epub\\:type~="titlepage"]',
        '.chapter-title', '.chapter-heading', '.chapter-num',
        '.section-title', '.section-heading',
        '.subchapter', '.subchapter-title',
        '.title', '.subtitle', '.heading',
        '.h1', '.h2', '.h3', '.h4', '.h5', '.h6',
        // Marker class added by `tokeniseChapterJs` when the parent looks heading-y (larger
        // font, bold+block, or numbered-section text). This is the ONLY selector that fires on
        // books like "Philosophy of Software Design 2nd ed" whose markup is obfuscated
        // (`p.class_s6k2`, `body.class5` — see the `pickedAncestors` diagnostic from Rule 3).
        '.riffle-heading'
      ].join(', ');
      var headings = document.querySelectorAll(HEADING_SELECTOR);
      dbg.totalHeadings = headings.length;
      var lastPreceding = null;
      var firstVisibleHeading = null;
      for (var i = 0; i < headings.length; i++) {
        var h = headings[i];
        if (inNav(h)) continue;
        if (isVisible(h)) {
          dbg.visibleHeadings++;
          if (firstVisibleHeading === null) firstVisibleHeading = h;
        } else if (isPreceding(h)) {
          dbg.precedingHeadings++;
          lastPreceding = h;
        }
      }
      // Priority for the section-heading pick:
      //   Rule 1a — first visible heading, IF anchored in the top-left corner of the viewport
      //             (top ≥ VT, top < VT+VH/2, left ≥ VL, left < VL+VW/2). "Top-left corner" means
      //             the user has just landed at a new section start; the heading is what they're
      //             seeing right now. In paginated column layouts this fires whenever a chapter
      //             pages onto a section heading; in continuous it fires at chapter/section
      //             openers.
      //   Rule 2  — last preceding heading. User is mid-section, the section's heading has
      //             scrolled off-screen (either up in continuous, or into a previous column in
      //             paginated). Rewind to the section start. No axis-specific distance guard:
      //             `isPreceding` already handled the axis by using OR (bottom ≤ VT OR right ≤ VL),
      //             so the picked preceding heading is the one closest to the current viewport
      //             in reading order.
      //   Rule 1b — first visible heading anywhere in the viewport (e.g. a peek from the lower
      //             half). Reached only when no preceding heading exists (chapter has no
      //             structural markers before this point) — never picked when Rule 2 could win,
      //             which prevents the "4.2 peeks in from the bottom while you're reading 4.1"
      //             regression.
      //   Rule 3  — no heading at all (elementsFromPoint fallback, see below).
      //
      // The bug this fix repairs: an earlier version preferred `lastPreceding` unconditionally
      // with a vertical-only distance guard. In horizontal-column paginated mode, headings
      // across columns share ~identical `top`, so `VT - pr.bottom` was always tiny and Rule 2
      // fired even when the "preceding" heading was one column to the left. Cadence would then
      // start on the previous section's first sentence — off-screen in the previous column —
      // and no highlight would ever be visible. Anchoring Rule 1a to the top-LEFT corner
      // handles this: when a new section starts at the top of the current column, its heading
      // is what the user sees, and Rule 1a fires.
      if (firstVisibleHeading) {
        var vr = docRect(firstVisibleHeading.getBoundingClientRect());
        // Diagnostic: report the visible heading's rect + Rule 1a check outcomes so we can see
        // WHY Rule 1a rejected it when Rule 2 fires instead of it.
        dbg.visibleHeadingText = (firstVisibleHeading.textContent || '').trim().slice(0, 60);
        dbg.visibleHeadingRect = { top: vr.top | 0, left: vr.left | 0, bottom: vr.bottom | 0, right: vr.right | 0 };
        dbg.rule1aChecks = {
          leftGeVL: vr.left >= VL,
          bottomLtSafeBottom: vr.bottom < VT + VH - VH / 8,
          rightLtSafeRight: vr.right < VL + VW,
          safeBottom: (VT + VH - VH / 8) | 0,
          safeRight: (VL + VW) | 0,
        };
        // "Prominently visible" = the heading has BODY SPACE below/right of it inside the
        // viewport, not just its own bounds. Checked as:
        //   - top ≥ VT AND left ≥ VL — hasn't scrolled off the leading edge
        //   - bottom < VB − VH/8 — at least VH/8 (~12%) of body room below the heading
        //   - right < VR — heading doesn't spill into the next column (paginated)
        //
        // Why "space below", not "top is in the upper N%": the previous version used
        // `top < VT + VH * 3/4` (upper 75%). The recording 20260707_124847 shows the "Exchange:
        // The Art of Give and Take…" heading at top=76.6% — just barely below the cutoff —
        // with body text visibly following. The heuristic then falls through to Rule 2 and
        // Cadence rewinds a whole section (the "started on the previous page" complaint).
        // Checking the heading's bottom instead answers the actual question: is there body
        // text visible under the heading, i.e. did we ENTER this section rather than merely
        // seeing its title peek up at the bottom?
        //
        // The 4.2-peek case from the earlier fix still rejects: a heading whose top sits below
        // the viewport bottom (or whose bottom is within VH/8 of the viewport bottom) is at the
        // extreme bottom peek — no body follows in-view — and falls through to Rule 2 as intended.
        // Note: we intentionally do NOT gate on `vr.top >= VT`. A heading whose top edge sits
        // slightly ABOVE the viewport top (isVisible passes because `bottom > VT`) is still
        // "the current section title on screen" — the user has just scrolled the heading up a
        // few pixels and its opening still occupies the top of what they're reading.
        // See recording 20260707_124847 (Case 5 in the verification run): heading top=14737,
        // vt=14759 (22px above) was rejected by `top >= VT` and Cadence rewound to 6.4 when
        // the user was clearly reading 6.5.
        var atTopOfViewport =
          vr.left >= VL &&
          vr.bottom < VT + VH - VH / 8 &&
          vr.right < VL + VW;
        if (atTopOfViewport) {
          dbg.firstHeadingText = (firstVisibleHeading.textContent || '').trim().slice(0, 60);
          dbg.firstHeadingRect = { top: vr.top | 0, left: vr.left | 0, bottom: vr.bottom | 0, right: vr.right | 0 };
          var idA = firstCdAtOrAfter(firstVisibleHeading);
          dbg.headingInnerCd = cdInsideEl(firstVisibleHeading);
          if (idA) {
            var el = document.getElementById(idA);
            dbg.pickedText = el ? (el.textContent || '').trim().slice(0, 80) : '';
            dbg.id = idA; dbg.rule = 1; return JSON.stringify(dbg);
          }
        }
      }
      if (lastPreceding) {
        var pr = docRect(lastPreceding.getBoundingClientRect());
        dbg.firstHeadingText = (lastPreceding.textContent || '').trim().slice(0, 60);
        dbg.firstHeadingRect = { top: pr.top | 0, left: pr.left | 0, bottom: pr.bottom | 0, right: pr.right | 0 };
        var id2 = firstCdAtOrAfter(lastPreceding);
        dbg.headingInnerCd = cdInsideEl(lastPreceding);
        if (id2) {
          var el2 = document.getElementById(id2);
          dbg.pickedText = el2 ? (el2.textContent || '').trim().slice(0, 80) : '';
          dbg.id = id2; dbg.rule = 2; return JSON.stringify(dbg);
        }
      }
      if (firstVisibleHeading) {
        var vr2 = docRect(firstVisibleHeading.getBoundingClientRect());
        dbg.firstHeadingText = (firstVisibleHeading.textContent || '').trim().slice(0, 60);
        dbg.firstHeadingRect = { top: vr2.top | 0, left: vr2.left | 0, bottom: vr2.bottom | 0, right: vr2.right | 0 };
        var idB = firstCdAtOrAfter(firstVisibleHeading);
        dbg.headingInnerCd = cdInsideEl(firstVisibleHeading);
        if (idB) {
          var elB = document.getElementById(idB);
          dbg.pickedText = elB ? (elB.textContent || '').trim().slice(0, 80) : '';
          dbg.id = idB; dbg.rule = 1; return JSON.stringify(dbg);
        }
      }

      // Rule (3) — no heading matched. Fall back to the first visible sentence via the plural
      // `elementsFromPoint` sweep (routes around decoration overlays). When this fires, ALSO
      // walk up the picked span's ancestor chain and record it — that gives the next iteration
      // a look at what element types this book uses for its section titles so the selector
      // above can be extended to catch them.
      //
      // `elementsFromPoint` uses viewport-relative CSS pixels. VT/VB/VL/VR are in device px,
      // so divide by DPR and subtract the WebView's own scroll to get viewport-y/x in CSS px.
      // In continuous the WebView doesn't scroll (`window.scrollY = 0`), so this reduces to
      // simple unit conversion.
      var y0 = Math.max(1, Math.round(VT / DPR - window.scrollY));
      var y1 = Math.max(y0 + 1, Math.round(VB / DPR - window.scrollY));
      var xs = [
        Math.floor((VL + VW * 0.25) / DPR - window.scrollX),
        Math.floor((VL + VW * 0.5) / DPR - window.scrollX),
        Math.floor((VL + VW * 0.75) / DPR - window.scrollX),
      ];
      for (var y = y0; y < y1; y += 6) {
        for (var xi = 0; xi < xs.length; xi++) {
          var stack = document.elementsFromPoint(xs[xi], y);
          if (!stack) continue;
          for (var si = 0; si < stack.length; si++) {
            var e = stack[si];
            while (e && e.nodeType === 1) {
              if (e.classList && e.classList.contains('riffle-cd')) {
                dbg.id = e.id; dbg.rule = 3;
                var chain = [];
                for (var p = e; p && chain.length < 10; p = p.parentElement) {
                  if (!p || p.nodeType !== 1) break;
                  var tag = (p.tagName || '?').toLowerCase();
                  var cls = p.className && typeof p.className === 'string' ? '.' + p.className.trim().split(/\s+/).join('.') : '';
                  var etype = p.getAttribute && (p.getAttribute('epub:type') || '');
                  chain.push(tag + cls + (etype ? '[epub:type=' + etype + ']' : ''));
                }
                dbg.pickedAncestors = chain;
                return JSON.stringify(dbg);
              }
              e = e.parentElement;
            }
          }
        }
      }
      return JSON.stringify(dbg);
    })()
        """
    }
}
