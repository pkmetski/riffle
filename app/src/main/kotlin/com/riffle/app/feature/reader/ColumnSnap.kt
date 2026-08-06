package com.riffle.app.feature.reader

import java.net.URLDecoder
import org.json.JSONObject

/**
 * JS-builder primitives that drive paginated column-snapping in the EPUB reader. The
 * fragment-driving entry points were moved to
 * [com.riffle.app.feature.reader.renderer.RendererBridge] in #331 — call sites now go through the
 * bridge, which is the only place [org.readium.r2.navigator.epub.EpubNavigatorFragment.evaluateJavascript]
 * is allowed to be invoked.
 *
 * The builders below remain in this file because the unit tests
 * (`NarratedColumnsResultParserTest`, the various JS-shape tests) target them directly without a
 * live WebView. Anything new that needs the page to rest flush on the column grid — new nav
 * route, new readaloud follow rule — adds a JS builder here and a typed method on the bridge.
 *
 * All snapping holds because the reader is sized so `window.innerWidth` equals Readium's
 * page-snap pitch (see ReaderViewportAlignment) — `floor(x / innerWidth) * innerWidth` is
 * therefore exactly a column boundary. The rAF-based operations share `window.__riffleSnapGen` so
 * a later snap supersedes an in-flight one instead of fighting it.
 */
internal object ColumnSnap {

    /**
     * A fixed-duration ease-out-cubic vertical scroll, in JS. Duration matches Continuous mode's
     * [android.widget.NestedScrollView.smoothScrollTo] default (~250 ms) so both modes feel the
     * same on internal-link taps — Chromium's `behavior:'smooth'` picks a distance-proportional
     * duration that reads as sluggish on longer jumps and doesn't match the continuous curve.
     *
     * The emitted snippet expects the caller to have already declared these locals in the enclosing
     * IIFE: `se` (scrolling element), `startV` (Number, the source scrollTop), and `targetV`
     * (Number, the destination scrollTop). Callers do the pre-land (hard `se.scrollTop = pre`)
     * first when the tail should ride only the last half-viewport under a nav cover; a same-doc
     * link tap skips the pre-land and animates the full distance from where the user is.
     *
     * Uses `window.__riffleVsmoothGen` as a supersede counter so a later smooth scroll cancels the
     * previous animation instead of fighting it — mirroring the `__riffleSnapGen` policy the
     * paginated column-snap tracker already uses.
     */
    /**
     * Stash the current scroll position and document URL on `window` so the post-`go(locator)`
     * smooth-tail can decide whether the jump was same-doc (animate FROM the stashed origin, no
     * visible pre-land) or cross-doc (stash is gone with the old document, pre-land under the nav
     * cover). Called by [com.riffle.app.feature.reader.renderer.DefaultRendererBridge.snapAfterGoTo]
     * BEFORE `frag.go(locator)`. Skipping this on a cross-doc jump is fine — the new document
     * doesn't inherit the stash, and the JS reads `undefined`, which triggers the pre-land branch.
     */
    const val STASH_VERTICAL_ORIGIN_JS: String =
        "(function(){var se=document.scrollingElement||document.documentElement;" +
            "if(!se)return;" +
            "window.__riffleOriginY=se.scrollTop;" +
            // Store the document identity WITHOUT the fragment. Readium's `go(locator)` may
            // append the target `#anchor` to `location.href` on same-doc jumps (browser-standard
            // hash update on scroll-to-id), which would silently fail an `===` compare against
            // the stashed value and drop the caller into the cross-doc pre-land branch. Same-doc
            // is a document-identity question, not a URL-with-anchor one — strip the fragment on
            // both sides so a hash change on the SAME document still matches.
            "window.__riffleOriginHref=location.href.split('#')[0];})()"

    private const val VERTICAL_SMOOTH_TAIL_JS: String =
        "var _dur=250,_t0=performance.now();" +
            "var _gen=(window.__riffleVsmoothGen=(window.__riffleVsmoothGen||0)+1);" +
            "var _delta=targetV-startV;" +
            "function _step(now){if(_gen!==window.__riffleVsmoothGen)return;" +
            "var _t=Math.min(1,(now-_t0)/_dur);" +
            "var _e=1-Math.pow(1-_t,3);" + // ease-out cubic
            "se.scrollTop=startV+_delta*_e;" +
            "if(_t<1)requestAnimationFrame(_step);}" +
            "requestAnimationFrame(_step);"

    /**
     * The element id a TOC/search/resume locator points at (its href fragment), or null for a
     * jump to a resource start. Drives [snapToTargetColumnJs] so the landing snaps to the column
     * the target itself occupies — robust to where go() landed and to the post-load reflow.
     */
    fun navTargetFragmentId(href: String): String? =
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
    fun autoFollowSnapJs(text: String): String {
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
          // #428 guard: chapter injections can fire before document.body is populated
          // (e.g. right after a sandboxed WebView restart); createTreeWalker throws
          // "parameter 1 is not of type 'Node'" and wedges follow-up injections.
          if(!document.body) return "off";
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
          // #428 guard: see autoFollowSnapJs.
          if(!document.body) return "off";
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
     * Returns a JSON array of the narrated sentence's cumulative per-column width fractions
     * (last ≈ 1.0), or the bare strings "off"/"scroll". The bridge wraps this into a typed call —
     * [com.riffle.app.feature.reader.renderer.RendererBridge.measureNarratedColumns] — and parses
     * the result via [parseNarratedColumnsResult].
     */
    fun measureNarratedColumnsJs(text: String): String =
        """
        (function(){
        ${narratedColumnsPreludeJs(text)}
          var cum=0, fr=[];
          for(var j=0;j<order.length;j++){ cum+=wmap[order[j]]; fr.push(cum/total); }
          return JSON.stringify(fr);
        })()
        """.trimIndent()

    // Id-based version of the locate-and-group prelude, for Cadence. Cadence's `cd-N` ids ARE in
    // the tokenised DOM and are chapter-unique, so `document.getElementById` is authoritative
    // and avoids the "text-search hits an earlier occurrence" bug that mispainted the sentence
    // highlight. Same output contract as [narratedColumnsPreludeJs] — leaves `se`, `order`,
    // `wmap`, `total` in scope on success; returns "off" (id absent) or "scroll" (vertical mode).
    private fun cadenceColumnsPreludeJs(fragmentId: String): String {
        val idLit = JSONObject.quote(fragmentId)
        return """
          var el=document.getElementById($idLit); if(!el) return "off";
          var se=document.scrollingElement||document.documentElement;
          if(se && se.scrollHeight > window.innerHeight + 4) return "scroll";
          var iw=window.innerWidth; if(!(iw>0)) return "off";
          var rng=document.createRange();
          try { rng.selectNodeContents(el); } catch(e) { return "off"; }
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

    /** Id-based analogue of [measureNarratedColumnsJs] for Cadence's `cd-N` spans. */
    fun measureCadenceColumnsJs(fragmentId: String): String =
        """
        (function(){
        ${cadenceColumnsPreludeJs(fragmentId)}
          var cum=0, fr=[];
          for(var j=0;j<order.length;j++){ cum+=wmap[order[j]]; fr.push(cum/total); }
          return JSON.stringify(fr);
        })()
        """.trimIndent()

    /** Id-based analogue of [snapNarratedColumnJs] for Cadence's `cd-N` spans. */
    fun snapCadenceColumnJs(fragmentId: String, columnIndex: Int): String =
        """
        (function(){
        ${cadenceColumnsPreludeJs(fragmentId)}
          var idx=$columnIndex; if(idx<0) idx=0; if(idx>order.length-1) idx=order.length-1;
          se.scrollLeft=order[idx];
          return "on";
        })()
        """.trimIndent()

    /**
     * Snaps scrollLeft to the [columnIndex]-th column the narrated sentence occupies (clamped),
     * landing flush on the grid. Returns "on", or "off"/"scroll" when there's nothing to snap.
     */
    fun snapNarratedColumnJs(text: String, columnIndex: Int): String =
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
    fun snapToEndColumnJs(): String =
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
    // (scrollWidth > innerWidth); "false" otherwise. Pairs with the bridge's `snapToEnd` in onPageLoaded.
    val LANDED_AT_END_JS: String =
        "(function(){var se=document.scrollingElement;if(!se)return 'false';" +
            "var iw=window.innerWidth;if(!(iw>0))return 'false';" +
            "if(!(se.scrollWidth > iw + 4))return 'false';" +
            "return (se.scrollLeft + iw >= se.scrollWidth - 4)?'true':'false';})()"

    // Brings the element [fragmentId] onto the page for an in-document cross-reference tap ("Figure
    // 4.1"). Mode-aware, because the reader paginates horizontally OR scrolls vertically:
    //  - paginated: FLOOR scrollLeft to the column the element starts in (go(cssSelector) lands flush to
    //    the box → a sliver of the neighbour shows; flooring to innerWidth lands on the column boundary).
    //  - scroll mode (scrollHeight > innerHeight): there is no column grid, so scroll VERTICALLY to bring
    //    the element to the VIEWPORT MIDPOINT — matching Continuous mode's `alignToTop=false` cross-
    //    reference landing (see [com.riffle.app.feature.reader.ContinuousPositionTracker.anchorLandingScrollY]).
    //    Landing at the top with an 8-px margin (the earlier behaviour) puts the anchor's own line at Y=0,
    //    which for anchors placed on a caption BELOW a figure image pushes the image off-screen — the
    //    "wrong location" bug when tapping a "Figure X.Y" link in Vertical. Midpoint preserves context
    //    above (usually where the referenced diagram / heading lives) and below.
    //    An element already fully on screen isn't moved.
    // Returns 'moved' when the snap changed the visible page (target was off-page → offer a return),
    // 'same' when it was already visible, or 'absent' when the id isn't in this document.
    /**
     * @param animated `true` for user-facing internal-link taps (return-to-position card,
     * cross-reference "Figure X.Y") — animate the vertical scroll on a fixed 250 ms ease-out.
     * `false` for readaloud cadence follow (`snapCadenceSpan`) which invokes this on every
     * sentence tick and needs instant snap to stay in sync with the audio — a 250 ms tween
     * per tick would visually drift and the supersede counter would cancel in-flight animations
     * before they land.
     */
    fun scrollToColumnJs(fragmentId: String, animated: Boolean = true): String {
        val verticalScroll = if (animated) {
            "var startV=beforeTop;" + VERTICAL_SMOOTH_TAIL_JS
        } else {
            "se.scrollTop=targetV;"
        }
        return "(function(){var el=document.getElementById(${JSONObject.quote(fragmentId)});" +
            "if(!el)return 'absent';" +
            "var se=document.scrollingElement||document.documentElement;" +
            "var r=el.getBoundingClientRect();" +
            "if(se.scrollHeight > window.innerHeight + 4){" + // scroll (vertical) mode → no column grid
            "if(r.top>=0 && r.bottom<=window.innerHeight)return 'same';" + // already fully visible
            "var beforeTop=se.scrollTop;" +
            "var targetV=Math.max(0, r.top + se.scrollTop - Math.floor(window.innerHeight/2));" +
            "if(Math.abs(targetV-beforeTop)<=1)return 'same';" +
            verticalScroll +
            "return 'moved';}" +
            "var iw=window.innerWidth;" +
            "var before=se.scrollLeft;" +
            "var abs=r.left+se.scrollLeft;" +
            "var target=Math.floor(abs/iw)*iw;se.scrollLeft=target;" +
            "return (Math.abs(target-before)>1)?'moved':'same';})()"
    }

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
    //
    // ALSO schedules a one-shot install-time fallback: a programmatic landing (Readium's resume `go()` on
    // book open) doesn't fire a scroll event, so if the listener were the only line of defence the page
    // could rest off-grid for the entire session — the "page slightly turned to next" book-open bug. The
    // fallback fires once ~200ms after install and only if `__riffleSnapGen` is unchanged (i.e. no
    // navigation snap took over) — so it can't clobber an in-flight [snapToTargetColumnJs] tracker.
    val SETTLE_SNAP_INSTALL_JS: String =
        """
        (function(){
          if(window.__riffleSettleSnapInstalled) return;
          window.__riffleSettleSnapInstalled=true;
          // Undo Readium's post-selection scrollLeft nudge. On selection end, readium-reflowable.js
          // calls N(), which writes `body.scrollLeft = R(scrollX + b/2)`, where `b` is
          // `viewportWidth / devicePixelRatio`. On devices with non-integer dpr (e.g. 2.625 on our
          // harness emulator), `b` is fractional and the returned floor-modulo lands on a
          // sub-pixel that the WebView setter rounds up to 1 CSS px — a visible 1-CSS-px drift
          // that the existing 120ms scroll-idle backstop only corrects a fraction of a second
          // later, producing the page-jog the user sees when Play-from-here opens the bar.
          //
          // Snap synchronously in a selectionchange listener registered AFTER Readium's (both
          // register on document.addEventListener, and 'load'-time registrations fire in
          // insertion order — Readium's is inside window.addEventListener('load', …), and this
          // block runs after Readium's script has already installed its listener). When the
          // selection has just collapsed, re-snap scrollLeft to the REAL column pitch (integer
          // window.innerWidth), which is what the CSS multicol layout actually uses.
          try {
            var lastCollapsed = true;
            function registerSelectionSnap() {
              document.addEventListener('selectionchange', function() {
                try {
                  var sel = window.getSelection();
                  var collapsed = !sel || sel.isCollapsed;
                  if (collapsed && !lastCollapsed) {
                    // Selection just ended — undo any sub-px scrollLeft drift Readium's N() writes.
                    var se = document.scrollingElement || document.documentElement;
                    if (se && se.scrollHeight <= window.innerHeight + 4) { // paginated only
                      var iw = window.innerWidth;
                      if (iw > 0) {
                        // Defer one frame so Readium's own selectionchange handler runs first and
                        // does its (fractional) write; then we correct it on the next tick.
                        requestAnimationFrame(function() {
                          var nearest = Math.round(se.scrollLeft / iw) * iw;
                          if (se.scrollLeft !== nearest) se.scrollLeft = nearest;
                        });
                      }
                    }
                  }
                  lastCollapsed = collapsed;
                } catch(e) {}
              });
            }
            // Readium registers its selectionchange handler inside window.load. Register mine after
            // window.load has fired so both are in place, and selection-collapse fires ours after
            // theirs — we correct the drift they introduce rather than being pre-empted.
            if (document.readyState === 'complete') registerSelectionSnap();
            else window.addEventListener('load', registerSelectionSnap, false);
          } catch(e) {}
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
            // During an active text selection the user may be dragging a handle across a column
            // boundary, which scrolls the WebView to a fractional column position. Use a very
            // short debounce (~2 frames) so the snap fires quickly and the off-grid layout is
            // imperceptible, rather than the normal 120ms which produces a visible jump after
            // the user releases the handle.
            var delay = 120;
            try { var s=window.getSelection(); if(s&&!s.isCollapsed) delay=32; } catch(e3) {}
            t=setTimeout(settle, delay);
          }, true);
          // Install-time fallback for programmatic landings that fire no scroll event (Readium's resume
          // go() on book open). Skip if a navigation snap has run/started since install — its rAF tracker
          // owns the grid math while it's active, and will leave the page flush on exit.
          var installGen=(window.__riffleSnapGen||0);
          setTimeout(function(){
            if((window.__riffleSnapGen||0) !== installGen) return;
            settle();
          }, 200);
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
    //
    // [locatorJson] is the preferred target for text-anchored navigation. Readium resolves its
    // TextQuote to the exact DOM Range on every tracked frame, so reflow cannot move the target
    // away from the landing column. [locatorProgression] remains the fallback when the quote is
    // absent or stale. It is re-evaluated against scrollWidth on each frame, which is stable
    // across typography reflow but only approximate for visual layout.
    fun snapToTargetColumnJs(
        fragmentId: String?,
        landAtStartWhenNoTarget: Boolean = true,
        locatorProgression: Double? = null,
        locatorJson: String? = null,
        snapProgressionToNearestColumn: Boolean = false,
        focusAnnotationId: String? = null,
    ): String {
        val idLiteral = if (fragmentId.isNullOrEmpty()) "null" else JSONObject.quote(fragmentId)
        val locatorLiteral = locatorJson
            ?.takeIf { it.isNotBlank() }
            ?.let { "JSON.parse(${JSONObject.quote(it)})" }
            ?: "null"
        val noTargetSnap = when {
            landAtStartWhenNoTarget -> "se.scrollLeft=0;"
            locatorProgression != null && snapProgressionToNearestColumn ->
                "se.scrollLeft=Math.round($locatorProgression*se.scrollWidth/iw)*iw;"
            locatorProgression != null -> "se.scrollLeft=Math.floor($locatorProgression*se.scrollWidth/iw)*iw;"
            else -> "se.scrollLeft=Math.round(se.scrollLeft/iw)*iw;"
        }
        val noteGroupLiteral = JSONObject.quote(NOTE_GLYPH_DECORATION_GROUP)
        val focusAnnotationLiteral = focusAnnotationId
            ?.takeIf { it.isNotBlank() }
            ?.let(JSONObject::quote)
            ?: "null"
        // For all annotation navigation (landAtStartWhenNoTarget=false), skip the vertical
        // smooth-tail block entirely. The snap JS runs immediately after go(locator), before
        // Readium has applied CSS multicol. At that moment scrollHeight > innerHeight (the natural
        // page height), so the vertical check fires and returns early — the paginated rAF loop
        // never runs. Then Readium applies multicol and resets scrollLeft to 0, always landing on
        // page 1. By setting _skipV=true we fall straight into the rAF loop, which tracks
        // scrollWidth each frame: it starts at innerWidth (pre-multicol → snap() is a near-no-op),
        // then grows when multicol is applied, and on each subsequent frame the loop either follows
        // the element id (new-style bookmark) or recomputes progression*scrollWidth/iw (legacy).
        // The loop exits only once scrollWidth has held steady for ≥3 frames, so
        // typography-injection reflows (which transiently reset scrollLeft to 0) are absorbed.
        val skipVertical = !landAtStartWhenNoTarget
        return "(function(){var id=$idLiteral;" +
            "var loc=$locatorLiteral;" +
            "var focusAnnotationId=$focusAnnotationLiteral;" +
            "window.$NOTE_GLYPH_FOCUS_ID_JS_KEY=focusAnnotationId;" +
            "var se=document.scrollingElement;" +
            "var _skipV=$skipVertical;" +
            // Vertical (scroll-mode) smooth tail. Readium's `go(locator)` already teleported us to
            // the target, so we compute `targetV` from either the fragment element's rect or the
            // current scrollTop. The animation START point depends on whether the jump crossed
            // documents: same-doc keeps the stashed pre-go scrollTop (no visible backward flash —
            // return-to-position card + same-chapter TOC entries have no nav cover to hide a
            // pre-land); cross-doc lost the stash with the old document, so we pre-land half a
            // viewport short of target — the nav cover hides the pre-land, and the reveal shows
            // the tail. Consuming the stash (setting to null) is important: a later same-doc
            // background sync must not reuse a stale origin from an unrelated navigation.
            // Skips the paginated rAF column-snap loop below — it only writes scrollLeft, which
            // is a no-op in vertical, and the smooth animation is one-shot rather than a
            // reflow-tracking loop. Skipped entirely for annotation focus (_skipV=true) — see above.
            "if(!_skipV && se && se.scrollHeight > window.innerHeight + 4){" +
            "var targetV;" +
            "if(id){var elV=document.getElementById(id);" +
            "if(elV){var rV=elV.getBoundingClientRect();" +
            "targetV=Math.max(0, rV.top + se.scrollTop - Math.floor(window.innerHeight/2));}" +
            "else{targetV=se.scrollTop;}}" +
            "else{targetV=se.scrollTop;}" +
            "var _sameDoc=(typeof window.__riffleOriginY==='number')&&" +
            "(window.__riffleOriginHref===location.href.split('#')[0]);" +
            "var startV;" +
            "if(_sameDoc){startV=window.__riffleOriginY;}" +
            "else{startV=Math.max(0, targetV - Math.floor(window.innerHeight/2));}" +
            "window.__riffleOriginY=null;window.__riffleOriginHref=null;" +
            "if(Math.abs(targetV-startV)<=1)return;" + // origin already at target → no motion
            "se.scrollTop=startV;" +
            VERTICAL_SMOOTH_TAIL_JS +
            "return;}" +
            "var gen=(window.__riffleSnapGen=(window.__riffleSnapGen||0)+1);" +
            "var lastW=-1,stable=0,frames=0,rangeMatched=false,rangeStable=0;" +
            "function snap(){var iw=window.innerWidth;" +
            // On older WebViews Readium's scrollToLocator(TextQuote) can settle exactly one
            // column after the quote. Once the note decoration exists, Readium has already
            // resolved that same quote to a live Range. Prefer its first client rect: this is
            // both more direct and uses the same geometry the glyph itself follows. The group
            // may not exist on early ticks, so the normal locator/progression fallbacks remain.
            "if((focusAnnotationId||(loc&&loc.text&&loc.text.highlight))&&se.scrollWidth>iw+4&&" +
            "window.readium&&typeof window.readium.getDecorations==='function'){" +
            "try{var notes=window.readium.getDecorations($noteGroupLiteral);" +
            "var items=notes&&notes.items?notes.items:[];" +
            "for(var ni=0;ni<items.length;ni++){" +
            "var item=items[ni],dl=item.decoration&&item.decoration.locator;" +
            "if(focusAnnotationId){" +
            "if(!item.decoration||item.decoration.id!==focusAnnotationId)continue;" +
            "}else{" +
            "if(!dl||!dl.text||dl.text.highlight!==loc.text.highlight)continue;" +
            "if(loc.text.before&&dl.text.before&&dl.text.before!==loc.text.before)continue;" +
            "if(loc.text.after&&dl.text.after&&dl.text.after!==loc.text.after)continue;}" +
            "var rects=item.range.getClientRects();" +
            "var nr=rects&&rects.length?rects[0]:item.range.getBoundingClientRect();" +
            "if(nr){var noteColumn=Math.floor((nr.left+se.scrollLeft)/iw)*iw;" +
            "if(Math.abs(se.scrollLeft-noteColumn)>1)rangeStable=0;else rangeStable++;" +
            "se.scrollLeft=noteColumn;rangeMatched=true;return;}" +
            "}}catch(e){}}" +
            // Readium's own locator resolver uses text.highlight + before/after to reconstruct
            // the exact DOM Range. Re-run it on every tracked frame so a typography reflow
            // cannot reset the landing to column 0. This is strictly more precise than mapping
            // character progression onto scrollWidth; progression remains the fallback when a
            // legacy/stale quote no longer matches the publication.
            "if(loc&&window.readium&&typeof window.readium.scrollToLocator==='function'){" +
            "try{if(window.readium.scrollToLocator(loc))return;}catch(e){}}" +
            "if(id){var el=document.getElementById(id);" +
            "if(el){se.scrollLeft=Math.floor((el.getBoundingClientRect().left+se.scrollLeft)/iw)*iw;}" +
            "else{se.scrollLeft=Math.round(se.scrollLeft/iw)*iw;}}" +
            "else{$noTargetSnap}}" +
            "function tick(){if(gen!==window.__riffleSnapGen)return;" + // a newer jump superseded us
            "var w=se.scrollWidth;if(w===lastW)stable++;else{stable=0;lastW=w;}" +
            "snap();" +
            // A note Range can resolve before Readium's final API-25 layout write. Keep pinning it
            // for a full second of correct frames; consuming focus on the first match lets that
            // later write restore the stale one-column-ahead position. Focused jumps also wait
            // longer for a late decoration publication, while ordinary navigation keeps the
            // existing short settle cap.
            "var rangeDone=rangeMatched&&rangeStable>=60;" +
            "var ordinaryDone=!focusAnnotationId&&!rangeMatched&&stable>=3&&frames>=2;" +
            "var cap=focusAnnotationId?600:72;" +
            "if(rangeDone||ordinaryDone||frames++>cap){" +
            "snap();window.$NOTE_GLYPH_FOCUS_ID_JS_KEY=null;return;}" +
            "requestAnimationFrame(tick);}" +
            "tick();})()"
    }

    /**
     * JS expression that finds the first paragraph-level element with an `id` attribute that is
     * visible in the current paginated column (viewport). Returns a JSON-encoded string (the id)
     * or `null` when not in paginated mode or no named element is visible.
     *
     * A single `querySelectorAll` with a priority-ordered selector list is used so the DOM is
     * traversed once. Block-text types (p, h1–h6, li, blockquote) appear before generic containers
     * (div, section, article) so paragraph-level ids are preferred over section wrappers. The first
     * match in the current viewport wins. `getBoundingClientRect().left` in [0, innerWidth) means
     * the element starts in the current column.
     *
     * The result is wrapped by `evaluateJavascript` in outer JSON quotes, so the caller must
     * call `raw.trim('"')` and then check for the literal string `"null"` before using.
     *
     * This expression is constant — evaluated once and reused across all bookmark-creation calls.
     */
    val CAPTURE_PAGE_FRAGMENT_ANCHOR_JS: String =
        "(function(){" +
            "var se=document.scrollingElement;" +
            "if(!se||se.scrollHeight>window.innerHeight+4)return null;" +
            "var iw=window.innerWidth;" +
            "var els=document.querySelectorAll(" +
            "'p[id],h1[id],h2[id],h3[id],h4[id],h5[id],h6[id],li[id],blockquote[id],div[id],section[id],article[id]');" +
            "for(var i=0;i<els.length;i++){" +
            "var r=els[i].getBoundingClientRect();" +
            "if(r.height>0&&r.left>=0&&r.left<iw)return els[i].id;}" +
            "return null;})()"

    /**
     * Parse the raw string [measureNarratedColumnsJs] returned through `evaluateJavascript`.
     * `evaluateJavascript` wraps a returned string in JSON quotes (the inner JSON number array
     * has no quotes to escape) and returns `null` when the page is gone. Protocol:
     *
     * - `null` → page gone → `[]`
     * - `"off"` → sentence not on this resource → `[]`
     * - `"scroll"` → vertical scroll mode → `[]`
     * - `"[…]"` → JSON number array of cumulative width fractions (last ≈ 1.0)
     * - anything else (malformed) → `[]` (defensive — never crash the playback loop)
     */
    fun parseNarratedColumnsResult(raw: String?): List<Double> {
        val trimmed = raw?.trim('"')?.trim() ?: return emptyList()
        if (trimmed == "off" || trimmed == "scroll" || !trimmed.startsWith("[")) return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(trimmed)
            List(arr.length()) { arr.getDouble(it) }
        }.getOrDefault(emptyList())
    }
}
