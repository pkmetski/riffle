package com.riffle.app.feature.reader

import java.net.URLDecoder
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator

/**
 * The single owner of paginated column-snapping for the EPUB reader. Every place that needs the page to
 * rest flush on the column grid goes through this object — navigation routes (TOC / search / resume),
 * in-document cross-references, the read-aloud follow, and the at-rest backstop — so a NEW navigation
 * route gets correct snapping just by calling [goAndSnap]; it never re-implements the grid math, the
 * reflow-tracking rAF loop, or the `innerWidth == page-snap pitch` assumption.
 *
 * All snapping holds because the reader is sized so `window.innerWidth` equals Readium's page-snap pitch
 * (see ReaderViewportAlignment) — `floor(x / innerWidth) * innerWidth` is therefore exactly a column
 * boundary. The rAF-based operation [goAndSnap] shares `window.__riffleSnapGen` so a later snap
 * supersedes an in-flight one instead of fighting it.
 *
 * The Kotlin entry points are the API; the `…Js` builders below are the implementation primitives
 * (internal so the script tests can exercise them directly).
 */
internal object ColumnSnap {

    // ── Fragment-aware API — the only thing call sites touch ────────────────────────────────

    /**
     * Installs the at-rest snap backstop on a freshly loaded page (idempotent). Once horizontal scrolling
     * settles off-grid in paginated mode it rounds to the nearest column, so the page can never REST
     * between two pages no matter what moved it. Defers to the rAF trackers via its scroll-idle debounce.
     */
    suspend fun installBackstop(fragment: EpubNavigatorFragment) {
        fragment.evaluateJavascript(SETTLE_SNAP_INSTALL_JS)
    }

    /**
     * Navigates to [link] and snaps the landing onto the target element's column, tracking it through the
     * new chapter's async typography reflow. THE entry point for navigation routes — call this instead of
     * `go()` + a hand-rolled snap, and the route gets correct, drift-proof snapping for free.
     */
    suspend fun goAndSnap(fragment: EpubNavigatorFragment, link: Link) {
        fragment.go(link)
        fragment.evaluateJavascript(snapToTargetColumnJs(navTargetFragmentId(link.href.toString())))
    }

    /**
     * [goAndSnap] for a [Locator] target (search hits, resume/peer-sync positions).
     *
     * [landAtStartWhenNoTarget] controls the no-DOM-fragment case: a chapter-level jump (TOC/search) lands
     * at the chapter top (true, the default), but a background sync (audiobook/peer) whose locator is a
     * within-chapter progression with no #fragment must instead round to the column grid where go() landed
     * (false) — otherwise the post-go snap yanks the reader to the chapter top, off the actual synced page.
     */
    suspend fun goAndSnap(
        fragment: EpubNavigatorFragment,
        locator: Locator,
        landAtStartWhenNoTarget: Boolean = true,
    ) {
        fragment.go(locator)
        fragment.evaluateJavascript(
            snapToTargetColumnJs(navTargetFragmentId(locator.href.toString()), landAtStartWhenNoTarget),
        )
    }

    /**
     * Re-pins the current page to its LAST column, tracking the chapter's async typography reflow — for
     * a backward cross-resource page turn that Readium handled itself (paginated mode), which our nav
     * handlers never see. Call from `onPageLoaded` when [landedAtEnd] reports the resource was placed at
     * its end, so the imminent reflow can't strand the page several columns short of the true end.
     */
    suspend fun snapToEnd(fragment: EpubNavigatorFragment) {
        fragment.evaluateJavascript(snapToEndColumnJs())
    }

    /**
     * "true" iff the freshly loaded paginated page is resting on its LAST column — i.e. Readium
     * positioned this resource at its end, which is what a backward cross-resource swipe does. "false"
     * in scroll mode, for a single-page resource, or anywhere but the last column (a forward turn lands
     * at column 0; a TOC jump lands at the chapter top). Read at `onPageLoaded`, BEFORE the typography
     * injection reflows the page, so the check sees Readium's landing position against the base width.
     */
    suspend fun landedAtEnd(fragment: EpubNavigatorFragment): Boolean =
        fragment.evaluateJavascript(LANDED_AT_END_JS)?.trim('"') == "true"

    /**
     * Snaps the current resource so the column containing [fragmentId] sits flush — for an in-document
     * cross-reference tap ("Figure 4.1"), where the element is already in this document (no go()).
     *
     * Returns true iff the snap actually moved the page to a different column — i.e. the target was
     * off-screen. A target already on the visible page snaps to the same column (returns false), so the
     * caller can suppress a "return here" affordance there is nothing to come back from.
     */
    suspend fun snapToElementColumn(fragment: EpubNavigatorFragment, fragmentId: String): Boolean =
        fragment.evaluateJavascript(scrollToColumnJs(fragmentId))?.trim('"') == "moved"

    /**
     * Follows the narrated sentence [text] to its column on a sentence change. Returns "on" (followed, or
     * already on-page) or "off" — "off" means the sentence isn't on the current resource, so the caller
     * go()s to the chapter that holds it.
     */
    suspend fun followNarratedSentence(fragment: EpubNavigatorFragment, text: String): String? =
        fragment.evaluateJavascript(autoFollowSnapJs(text))?.trim('"')

    /**
     * Measures how the narrated sentence [text] is laid out across paginated columns: the CUMULATIVE
     * fraction of its rendered width that falls in each column it spans, in reading order (e.g.
     * `[0.62, 1.0]` for a sentence whose first 62% of width is on the current page and the rest on the
     * next). Drives [NarratedColumnProgression] so intra-sentence page turns track the narration.
     *
     * Empty when the sentence fits a single column, isn't on this resource, or the reader is in scroll
     * mode (vertical follow handles that) — all of which mean "don't turn within the sentence".
     */
    suspend fun measureNarratedColumns(fragment: EpubNavigatorFragment, text: String): List<Double> =
        parseNarratedColumnsResult(fragment.evaluateJavascript(measureNarratedColumnsJs(text)))

    /**
     * Parse the raw string [measureNarratedColumnsJs] returned through `evaluateJavascript`.
     * `evaluateJavascript` wraps a returned string in JSON quotes (the inner JSON number array
     * has no quotes to escape) and returns `null` when the page is gone. The protocol the JS
     * defines (see [measureNarratedColumnsJs]):
     *
     * - `null` → page gone → `[]`
     * - `"off"` → sentence not on this resource → `[]`
     * - `"scroll"` → vertical scroll mode → `[]`
     * - `"[…]"` → JSON number array of cumulative width fractions (last ≈ 1.0)
     * - anything else (malformed) → `[]` (defensive — never crash the playback loop)
     *
     * Extracted from [measureNarratedColumns] so the contract can be JVM-unit-tested without
     * a WebView; see `NarratedColumnsResultParserTest`.
     */
    internal fun parseNarratedColumnsResult(raw: String?): List<Double> {
        val trimmed = raw?.trim('"')?.trim() ?: return emptyList()
        if (trimmed == "off" || trimmed == "scroll" || !trimmed.startsWith("[")) return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(trimmed)
            List(arr.length()) { arr.getDouble(it) }
        }.getOrDefault(emptyList())
    }

    /**
     * Snaps the page to the [columnIndex]-th column the narrated sentence [text] spans (clamped to the
     * range it actually occupies), landing flush on the column grid. The companion to
     * [measureNarratedColumns]: the progression decides the index, this performs the turn. Re-locates
     * the sentence each call so it is robust to a reflow having moved the columns since measurement.
     */
    suspend fun snapNarratedColumn(fragment: EpubNavigatorFragment, text: String, columnIndex: Int) {
        fragment.evaluateJavascript(snapNarratedColumnJs(text, columnIndex))
    }

    // ── Snapping primitives (JS builders) — implementation, internal for the script tests ────

    // The element id a TOC/search/resume locator points at (its href fragment), or null for a jump to a
    // resource start. Drives [snapToTargetColumnJs] so the landing snaps to the column the target itself
    // occupies — robust to where go() landed and to the post-load reflow.
    private fun navTargetFragmentId(href: String): String? =
        href.substringAfter('#', "").ifEmpty { null }
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }

    /**
     * The page-follow probe run on each narrated-sentence change. Locates the narrated sentence by its
     * [text] (Readium strips the media-overlay sentence spans, so getElementById would miss). In scroll
     * mode it centres the sentence vertically. In paginated mode it FLOORS scrollLeft to the column that
     * contains the sentence's start — symmetrically, on every change: a no-op when the sentence is already
     * on the current page, a drift-correction when the page is the right column but off-grid, and a real
     * page move (forward OR back) when narration has crossed into another column. Returns "on", or "off"
     * only when the text isn't on the current resource (another chapter) → the caller go()s to load it.
     */
    internal fun autoFollowSnapJs(text: String): String {
        // Locate the narrated sentence by its TEXT (Readium strips the media-overlay span ids, so
        // getElementById can't find it). We match the WHOLE sentence, not a short prefix: chapter-opening
        // sentences in some books are recurring datelines — "LOG ENTRY: SOL 38", "LOG ENTRY: SOL 37", … —
        // whose first 12 chars are identical, so a prefix match finds the PREVIOUS chapter's dateline still
        // on the outgoing resource, reports the sentence as already on-page, and SUPPRESSES the caller's
        // cross-resource go(). The reader then hangs on the old chapter until a later, distinctive sentence
        // plays — the "shows the old chapter, corrects on the 2nd sentence" bug.
        //
        // Whole-sentence matching is robust only if it survives the two reasons a short prefix was used
        // before: (a) inline markup splits a sentence across text nodes ("Once we got " then an italic
        // "<em>Hermes</em>"), and (b) the served ABS prose diverges slightly from the bundle's sentence text
        // — smart vs straight quotes, en/em dashes, runs of whitespace. So we match over a CANONICAL
        // concatenation of the body's text nodes (whitespace collapsed to single spaces; curly quotes and
        // dashes folded to ASCII), keeping an index map from each canonical char back to its (node, offset)
        // so the hit still yields a real DOM range for the column/scroll math. Not found on this resource →
        // "off", and the caller go()s to the chapter that holds the sentence. This is the same text-fidelity
        // contract the decoration highlight's TextQuoteAnchor already relies on. Empty text → "off".
        return """
        (function(){
          function isWs(c){return c===32||c===9||c===10||c===13||c===12||c===160;}
          function canon(ch){var c=ch.charCodeAt(0);
            if(c===0x2018||c===0x2019||c===0x201A||c===0x2032||c===0x60||c===0xB4) return "'";
            if(c===0x201C||c===0x201D||c===0x201E||c===0x2033) return '"';
            if(c===0x2013||c===0x2014||c===0x2212) return '-';
            return ch;}
          var raw=${JSONObject.quote(text)};
          var needle="", sp=false;
          for(var a=0;a<raw.length;a++){
            if(isWs(raw.charCodeAt(a))){ if(!sp && needle.length){ needle+=" "; sp=true; } continue; }
            sp=false; needle+=canon(raw[a]);
          }
          needle=needle.replace(/ ${'$'}/,"");
          if(!needle) return "off";
          var w=document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false), n;
          var buf="", bn=[], bo=[], bsp=false;
          while(n=w.nextNode()){
            var v=n.nodeValue;
            for(var j=0;j<v.length;j++){
              if(isWs(v.charCodeAt(j))){ if(bsp) continue; buf+=" "; bsp=true; bn.push(n); bo.push(j); continue; }
              bsp=false; buf+=canon(v[j]); bn.push(n); bo.push(j);
            }
          }
          var pos=buf.indexOf(needle);
          if(pos<0) return "off";
          var node=bn[pos], off=bo[pos];
          var g=document.createRange(); g.setStart(node,off); g.setEnd(node, Math.min(node.nodeValue.length, off+1));
          var r=g.getBoundingClientRect();
          if(!r) return "off";
          var se=document.scrollingElement||document.documentElement;
          if(se && se.scrollHeight > window.innerHeight + 4){
            // KEEP-VISIBLE follow, not re-centre-every-sentence. Only scroll when the narrated sentence
            // has drifted OUT of a central comfort band (the middle half of the viewport), then recentre
            // it. Two adjacent sentences both inside the band — which is what a small audio-position
            // jitter that flaps the active sentence back and forth across a clip boundary produces —
            // move the page by nothing, so it no longer jiggles a line up-and-down on each change. This
            // mirrors the deliberate keep-visible policy paginated mode already uses to avoid the same
            // jitter. Forward reading still scrolls (in calm half-viewport steps) as the sentence nears
            // an edge, and an off-screen sentence (e.g. after a seek) still recentres.
            var h=window.innerHeight, mid=(r.top+r.bottom)/2, band=h*0.25;
            if(mid < band || mid > h - band) window.scrollBy(0, Math.round(mid - h/2));
            return "on";
          }
          var iw=window.innerWidth;
          // Follow the narrated sentence's COLUMN on every sentence change, SYMMETRICALLY: floor scrollLeft to
          // the column that contains the sentence's start. One rule, the same in both directions:
          //  - a NO-OP when that column is already the page on screen (consecutive same-page sentences don't
          //    jitter the page),
          //  - a DRIFT-CORRECTION when the page is the right column but resting off-grid (the "shifted left,
          //    sliver of the next page showing" readaloud bug — flooring to the column lands flush on the grid),
          //  - a real PAGE MOVE, forward OR back, when narration has moved to another column — so the view
          //    always returns to the highlight when it changes, identically whichever way the reader had paged.
          // The player floats over the page and never reflows it, so opening it doesn't move the
          // narrated column. Scroll mode keeps centring (handled above).
          var absX=r.left + se.scrollLeft;
          se.scrollLeft=Math.floor(absX / iw) * iw;
          return "on";
        })()
        """.trimIndent()
    }

    // Locate-and-group prelude shared by [measureNarratedColumnsJs] and [snapNarratedColumnJs]. Runs
    // inside an IIFE and leaves in scope, on success: `se` (scrolling element), `order` (the ascending
    // list of column-boundary scrollLeft values the sentence spans, each a multiple of innerWidth),
    // `wmap` (boundary → summed rect width), and `total` (their sum). It locates the sentence by a
    // 12-char prefix of [text] (Readium strips the media-overlay span ids, so getElementById misses),
    // extends a Range across the sentence's characters — walking forward through text nodes so inline
    // children (italics, etc.) don't truncate it — and buckets the Range's client rects into columns by
    // the SAME floor(x / innerWidth) math the rest of ColumnSnap trusts. Early-returns "off" (sentence
    // not on this resource) or "scroll" (vertical mode → no column grid; the caller centres instead).
    private fun narratedColumnsPreludeJs(text: String): String {
        val full = JSONObject.quote(text.trim())
        return """
          var full=$full; if(!full) return "off";
          var key=full.slice(0,12);
          var w=document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false), n, sn=null, so=0;
          while(n=w.nextNode()){ var i=n.nodeValue.indexOf(key); if(i>=0){ sn=n; so=i; break; } }
          if(!sn) return "off";
          var se=document.scrollingElement||document.documentElement;
          if(se && se.scrollHeight > window.innerHeight + 4) return "scroll";
          var iw=window.innerWidth; if(!(iw>0)) return "off";
          var rng=document.createRange(); rng.setStart(sn, so);
          var endNode=sn, endOff=Math.min(sn.nodeValue.length, so + full.length), remaining=full.length - (endOff - so);
          if(remaining > 0){
            var w2=document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false), m;
            while(m=w2.nextNode()){ if(m===sn) break; }
            var node;
            while(remaining > 0 && (node=w2.nextNode())){
              var len=node.nodeValue.length;
              if(len >= remaining){ endNode=node; endOff=remaining; remaining=0; }
              else { remaining-=len; endNode=node; endOff=len; }
            }
          }
          rng.setEnd(endNode, endOff);
          var rects=rng.getClientRects(), order=[], wmap={};
          for(var k=0;k<rects.length;k++){
            var rc=rects[k]; if(rc.width<=0 || rc.height<=0) continue;
            var b=Math.floor((rc.left + se.scrollLeft) / iw) * iw;
            if(!(b in wmap)){ wmap[b]=0; order.push(b); }
            wmap[b]+=rc.width;
          }
          if(order.length===0) return "off";
          order.sort(function(a,b){return a-b;});
          var total=0; for(var j=0;j<order.length;j++) total+=wmap[order[j]];
          if(!(total>0)) return "off";
        """.trimIndent()
    }

    /**
     * Returns a JSON array of the narrated sentence's cumulative per-column width fractions (last ≈ 1.0),
     * or the bare strings "off"/"scroll". See [measureNarratedColumns] for the Kotlin-side contract.
     */
    internal fun measureNarratedColumnsJs(text: String): String =
        """
        (function(){
        ${narratedColumnsPreludeJs(text)}
          var cum=0, fr=[];
          for(var j=0;j<order.length;j++){ cum+=wmap[order[j]]; fr.push(cum/total); }
          return JSON.stringify(fr);
        })()
        """.trimIndent()

    /**
     * Snaps scrollLeft to the [columnIndex]-th column the narrated sentence occupies (clamped), landing
     * flush on the grid. Returns "on", or "off"/"scroll" when there's nothing to snap. See
     * [snapNarratedColumn].
     */
    internal fun snapNarratedColumnJs(text: String, columnIndex: Int): String =
        """
        (function(){
        ${narratedColumnsPreludeJs(text)}
          var idx=$columnIndex; if(idx<0) idx=0; if(idx>order.length-1) idx=order.length-1;
          se.scrollLeft=order[idx];
          return "on";
        })()
        """.trimIndent()

    // Lands a backward chapter turn on the LAST column of the freshly loaded resource and KEEPS it there
    // until that chapter's typography reflow settles — the end-of-resource counterpart to
    // [snapToTargetColumnJs]. A requestAnimationFrame loop that, every frame, pins scrollLeft to the last
    // column boundary (`floor((scrollWidth - innerWidth) / innerWidth) * innerWidth`), so as reflow grows
    // the content and adds columns the page tracks the moving end instead of being stranded several
    // columns short — the "previous chapter overshoots 4-5 pages back" bug, which a raw `go()` to a
    // `progression = 1.0` locator produces because it resolves 1.0 against the pre-reflow column count.
    // Stops once scrollWidth has held steady for a few frames or a safety cap elapses. Shares
    // `window.__riffleSnapGen` so a later jump supersedes an in-flight end-snap. The first snap runs
    // synchronously (before the first rAF) so a non-reflowing page lands immediately. Vertical (scroll)
    // mode pins scrollTop to the bottom instead; a page that fits the viewport is left untouched.
    internal fun snapToEndColumnJs(): String =
        "(function(){var se=document.scrollingElement;if(!se)return;" +
            "var gen=(window.__riffleSnapGen=(window.__riffleSnapGen||0)+1);" +
            "var lastW=-1,lastH=-1,stable=0,frames=0;" +
            "function snap(){var iw=window.innerWidth;if(!(iw>0))return;" +
            "if(se.scrollWidth > iw + 4){" + // paginated → last column
            "se.scrollLeft=Math.max(0,Math.floor((se.scrollWidth - iw)/iw)*iw);}" +
            "else if(se.scrollHeight > window.innerHeight + 4){" + // scroll mode → bottom
            "se.scrollTop=Math.max(0,se.scrollHeight - window.innerHeight);}}" +
            "function tick(){if(gen!==window.__riffleSnapGen)return;" + // a newer jump superseded us
            "var w=se.scrollWidth,h=se.scrollHeight;" +
            "if(w===lastW&&h===lastH)stable++;else{stable=0;lastW=w;lastH=h;}" +
            "snap();" +
            "if((stable>=3&&frames>=2)||frames++>72){snap();return;}" + // settled, or ~1.2s safety cap
            "requestAnimationFrame(tick);}" +
            "tick();})()"

    // Reports whether the freshly loaded paginated page is resting on its LAST column — the signature of
    // a backward cross-resource turn (Readium positions the previous resource at its end). Paginated only
    // (scrollWidth > innerWidth); "false" otherwise. Pairs with [snapToEnd] in onPageLoaded.
    internal val LANDED_AT_END_JS: String =
        "(function(){var se=document.scrollingElement;if(!se)return 'false';" +
            "var iw=window.innerWidth;if(!(iw>0))return 'false';" +
            "if(!(se.scrollWidth > iw + 4))return 'false';" +
            "return (se.scrollLeft + iw >= se.scrollWidth - 4)?'true':'false';})()"

    // Brings the element [fragmentId] onto the page for an in-document cross-reference tap ("Figure
    // 4.1"). Mode-aware, because the reader paginates horizontally OR scrolls vertically:
    //  - paginated: FLOOR scrollLeft to the column the element starts in (go(cssSelector) lands flush to
    //    the box → a sliver of the neighbour shows; flooring to innerWidth lands on the column boundary).
    //  - scroll mode (scrollHeight > innerHeight): there is no column grid, so scroll VERTICALLY to bring
    //    the element to the top of the viewport; an element already fully on screen isn't moved.
    // Returns 'moved' when the snap changed the visible page (target was off-page → offer a return),
    // 'same' when it was already visible, or 'absent' when the id isn't in this document.
    internal fun scrollToColumnJs(fragmentId: String): String =
        "(function(){var el=document.getElementById(${JSONObject.quote(fragmentId)});" +
            "if(!el)return 'absent';" +
            "var se=document.scrollingElement||document.documentElement;" +
            "var r=el.getBoundingClientRect();" +
            "if(se.scrollHeight > window.innerHeight + 4){" + // scroll (vertical) mode → no column grid
            "if(r.top>=0 && r.bottom<=window.innerHeight)return 'same';" + // already fully visible
            "var beforeTop=se.scrollTop;" +
            "se.scrollTop=Math.max(0, r.top + se.scrollTop - 8);" +
            "return (Math.abs(se.scrollTop-beforeTop)>1)?'moved':'same';}" +
            "var iw=window.innerWidth;" +
            "var before=se.scrollLeft;" +
            "var abs=r.left+se.scrollLeft;" +
            "var target=Math.floor(abs/iw)*iw;se.scrollLeft=target;" +
            "return (Math.abs(target-before)>1)?'moved':'same';})()"

    // The AT-REST backstop: a debounced scroll-idle listener that, once horizontal scrolling has gone quiet
    // in paginated mode, rounds scrollLeft to the NEAREST column boundary so the page can never come to REST
    // between two columns — regardless of what moved it. Installed once per page (idempotent via the guard).
    //
    // NEAREST (not floor) because at rest no target is known: a few px of drift rounds back to the SAME
    // column (imperceptible) while a half-turned page rounds to the closest clean page. It does NOT fight the
    // rAF tracker [snapToTargetColumnJs]: it scrolls every frame while a reflow
    // settles, re-arming this debounce, so it never fires mid-track; once a tracker finishes it has left the
    // page on the grid, so the debounce then rounds a no-op. Vertical (scroll) mode is skipped. Re-setting
    // scrollLeft re-fires 'scroll', but the next settle finds the page on-grid → one harmless no-op, no loop.
    internal val SETTLE_SNAP_INSTALL_JS: String =
        """
        (function(){
          if(window.__riffleSettleSnapInstalled) return;
          window.__riffleSettleSnapInstalled=true;
          var t=null;
          function settle(){
            var se=document.scrollingElement||document.documentElement; if(!se) return;
            if(se.scrollHeight > window.innerHeight + 4) return; // vertical/scroll mode → no column grid
            var iw=window.innerWidth; if(!(iw>0)) return;
            var cur=se.scrollLeft, nearest=Math.round(cur/iw)*iw;
            if(Math.abs(cur-nearest) > 1) se.scrollLeft=nearest;
          }
          window.addEventListener('scroll', function(){
            if(t) clearTimeout(t);
            t=setTimeout(settle, 120);
          }, true);
        })()
        """.trimIndent()

    // Lands a go()-based jump (TOC/search/resume) on the column grid and KEEPS it there until the freshly
    // loaded chapter's typography reflow settles. A requestAnimationFrame loop that, every frame, re-locates
    // the target by its [fragmentId] and FLOORS scrollLeft to the column the target currently occupies — so
    // it tracks the target as the reflow moves it (the fix for the "TOC is hit-and-miss" bug) — stopping once
    // scrollWidth has held steady for a few frames or a safety cap elapses. Anchoring to the element (not the
    // current scroll) makes it robust to where go() landed. [fragmentId] null/empty → see
    // [landAtStartWhenNoTarget]; an id that can't be found → best-effort round of the current position.
    //
    // [landAtStartWhenNoTarget] decides the no-DOM-target landing: a chapter-level jump (TOC/search) floors
    // to column 0 (true, the default); a position-based jump (a background sync that already go()'d to a
    // within-chapter progression) rounds the current scroll to the column grid instead (false), preserving
    // where go() landed rather than yanking to the chapter top.
    internal fun snapToTargetColumnJs(fragmentId: String?, landAtStartWhenNoTarget: Boolean = true): String {
        val idLiteral = if (fragmentId.isNullOrEmpty()) "null" else JSONObject.quote(fragmentId)
        val noTargetSnap = if (landAtStartWhenNoTarget) "se.scrollLeft=0;" else "se.scrollLeft=Math.round(se.scrollLeft/iw)*iw;"
        return "(function(){var id=$idLiteral;" +
            "var se=document.scrollingElement;" +
            "var gen=(window.__riffleSnapGen=(window.__riffleSnapGen||0)+1);" +
            "var lastW=-1,stable=0,frames=0;" +
            "function snap(){var iw=window.innerWidth;" +
            "if(id){var el=document.getElementById(id);" +
            "if(el){se.scrollLeft=Math.floor((el.getBoundingClientRect().left+se.scrollLeft)/iw)*iw;}" +
            "else{se.scrollLeft=Math.round(se.scrollLeft/iw)*iw;}}" +
            "else{$noTargetSnap}}" +
            "function tick(){if(gen!==window.__riffleSnapGen)return;" + // a newer jump superseded us
            "var w=se.scrollWidth;if(w===lastW)stable++;else{stable=0;lastW=w;}" +
            "snap();" +
            "if((stable>=3&&frames>=2)||frames++>72){snap();return;}" + // settled, or ~1.2s safety cap
            "requestAnimationFrame(tick);}" +
            "tick();})()"
    }
}
