package com.riffle.app.feature.reader

import java.net.URLDecoder
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator

/**
 * The single owner of paginated column-snapping for the EPUB reader. Every place that needs the page to
 * rest flush on the column grid goes through this object — navigation routes (TOC / search / resume),
 * in-document cross-references, the read-aloud follow, the player-open reflow, and the at-rest backstop —
 * so a NEW navigation route gets correct snapping just by calling [goAndSnap]; it never re-implements the
 * grid math, the reflow-tracking rAF loop, or the `innerWidth == page-snap pitch` assumption.
 *
 * All snapping holds because the reader is sized so `window.innerWidth` equals Readium's page-snap pitch
 * (see ReaderViewportAlignment) — `floor(x / innerWidth) * innerWidth` is therefore exactly a column
 * boundary. The rAF-based operations ([goAndSnap], [reflowReSnapScript]) share `window.__riffleSnapGen`
 * so a later snap supersedes an in-flight one instead of fighting it.
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
     * Snaps the current resource so the column containing [fragmentId] sits flush — for an in-document
     * cross-reference tap ("Figure 4.1"), where the element is already in this document (no go()).
     */
    suspend fun snapToElementColumn(fragment: EpubNavigatorFragment, fragmentId: String) {
        fragment.evaluateJavascript(scrollToColumnJs(fragmentId))
    }

    /**
     * Captures a prefix of the top-of-page line BEFORE a read-aloud-reserve reflow, so [reflowReSnapScript]
     * can pin that same line back as the columns re-paginate. Returns "" when nothing on-page is found.
     */
    suspend fun captureReflowAnchor(fragment: EpubNavigatorFragment): String =
        fragment.evaluateJavascript(reflowAnchorCaptureJs())?.trim('"').orEmpty()

    /**
     * The JS that re-pins [anchor] (from [captureReflowAnchor]) through the reserve reflow. Returned as a
     * snippet so the caller can append it to the reserve-apply evaluation — running both in ONE
     * evaluateJavascript so no frame paints between the re-pagination and the first re-snap. "" → no-op.
     */
    fun reflowReSnapScript(anchor: String): String =
        if (anchor.isNotEmpty()) "\n;" + reflowAnchorSnapJs(anchor) else ""

    /**
     * Follows the narrated sentence [text] to its column on a sentence change. Returns "on" (followed, or
     * already on-page) or "off" — "off" means the sentence isn't on the current resource, so the caller
     * go()s to the chapter that holds it.
     */
    suspend fun followNarratedSentence(fragment: EpubNavigatorFragment, text: String): String? =
        fragment.evaluateJavascript(autoFollowSnapJs(text))?.trim('"')

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
        // A short, near-unique prefix of the sentence; matched within a single text node (sentence starts
        // almost never split across nodes). Empty text disables the lookup → "off" (caller's go() fallback).
        val probe = text.trim().take(24)
        return """
        (function(){
          var needle=${JSONObject.quote(probe)};
          if(!needle) return "off";
          var key=needle.slice(0,12);
          var w=document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false), n, r=null;
          while(n=w.nextNode()){
            var i=n.nodeValue.indexOf(key);
            if(i>=0){ var g=document.createRange(); g.setStart(n,i); g.setEnd(n, Math.min(n.nodeValue.length, i+1)); r=g.getBoundingClientRect(); break; }
          }
          if(!r) return "off";
          var se=document.scrollingElement||document.documentElement;
          if(se && se.scrollHeight > window.innerHeight + 4){
            var delta=Math.round((r.top+r.bottom)/2 - window.innerHeight/2);
            if(Math.abs(delta) > 8) window.scrollBy(0, delta);
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
          // Player-open reflow is handled elsewhere (this probe deliberately does not re-run on the reserve
          // reflow; reflowAnchorSnap pins the top line there). Scroll mode keeps centring (handled above).
          var absX=r.left + se.scrollLeft;
          se.scrollLeft=Math.floor(absX / iw) * iw;
          return "on";
        })()
        """.trimIndent()
    }

    // Scrolls the current resource so the column CONTAINING [fragmentId] sits flush at the viewport's
    // left edge — for an in-document cross-reference tap ("Figure 4.1"). go(cssSelector) lands flush to
    // the element's box (a little inside its column → a sliver of the neighbour shows); flooring
    // scrollLeft to a multiple of innerWidth lands on the column boundary.
    internal fun scrollToColumnJs(fragmentId: String): String =
        "(function(){var el=document.getElementById(${JSONObject.quote(fragmentId)});" +
            "if(!el)return;var se=document.scrollingElement;var iw=window.innerWidth;" +
            "var abs=el.getBoundingClientRect().left+se.scrollLeft;" +
            "se.scrollLeft=Math.floor(abs/iw)*iw;})()"

    // The AT-REST backstop: a debounced scroll-idle listener that, once horizontal scrolling has gone quiet
    // in paginated mode, rounds scrollLeft to the NEAREST column boundary so the page can never come to REST
    // between two columns — regardless of what moved it. Installed once per page (idempotent via the guard).
    //
    // NEAREST (not floor) because at rest no target is known: a few px of drift rounds back to the SAME
    // column (imperceptible) while a half-turned page rounds to the closest clean page. It does NOT fight the
    // rAF trackers ([snapToTargetColumnJs], [reflowAnchorSnapJs]): those scroll every frame while a reflow
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

    // Captures a short text prefix of the line at the TOP of the current paginated page — read before the
    // read-aloud reserve re-paginates the columns, so [reflowAnchorSnapJs] can pin that same line back
    // afterwards (else the page lands on different content — the "line jumps when the player opens" bug).
    // Returns "" when nothing on-page is found.
    internal fun reflowAnchorCaptureJs(): String =
        """
        (function(){
          var iw=window.innerWidth, ih=window.innerHeight;
          var w=document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false), n;
          while(n=w.nextNode()){
            var t=n.nodeValue; if(t.replace(/\s/g,'').length < 4) continue;
            var g=document.createRange(); g.setStart(n,0); g.setEnd(n, Math.min(t.length,1));
            var r=g.getBoundingClientRect();
            if(r.left >= 0 && r.left < iw && r.bottom > 0 && r.top < ih) return t.replace(/^\s+/, '').slice(0,24);
          }
          return "";
        })()
        """.trimIndent()

    // Re-pins the page to [anchorPrefix] (from [reflowAnchorCaptureJs]) while the read-aloud reserve
    // re-paginates: a requestAnimationFrame loop that re-locates the prefix each frame and floors scrollLeft
    // to the column holding it, until scrollWidth holds steady or a safety cap. Shares window.__riffleSnapGen
    // with [snapToTargetColumnJs], so a TOC/search jump and this re-anchor supersede each other instead of
    // fighting. No-op for an empty prefix.
    internal fun reflowAnchorSnapJs(anchorPrefix: String): String {
        if (anchorPrefix.isEmpty()) return "(function(){})()"
        val needle = JSONObject.quote(anchorPrefix)
        return "(function(){var needle=$needle;" +
            "var se=document.scrollingElement;" +
            "var gen=(window.__riffleSnapGen=(window.__riffleSnapGen||0)+1);" +
            "var lastW=-1,stable=0,frames=0;" +
            "function locate(){var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null,false),n;" +
            "while(n=w.nextNode()){var i=n.nodeValue.indexOf(needle);" +
            "if(i>=0){var g=document.createRange();g.setStart(n,i);g.setEnd(n,Math.min(n.nodeValue.length,i+1));return g.getBoundingClientRect();}}return null;}" +
            "function snap(){var iw=window.innerWidth;var r=locate();if(r)se.scrollLeft=Math.floor((r.left+se.scrollLeft)/iw)*iw;}" +
            "function tick(){if(gen!==window.__riffleSnapGen)return;" +
            "var w=se.scrollWidth;if(w===lastW)stable++;else{stable=0;lastW=w;}" +
            "snap();" +
            "if((stable>=3&&frames>=2)||frames++>72){snap();return;}" +
            "requestAnimationFrame(tick);}" +
            "tick();})()"
    }
}
