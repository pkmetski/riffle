package com.riffle.app.feature.reader

import android.webkit.JavascriptInterface

// JS bridge that intercepts taps on in-document anchors before the browser
// scrolls to the fragment. Works around Readium 3.0.0's broken footnote popup:
// R2BasicWebView.handleFootnote uses `select("#$id")`, which Jsoup parses as
// "id AND class" when the id contains a dot — common in O'Reilly-style EPUBs
// (id="ftn.ch01fn01"). The selector matches nothing, the popup is skipped,
// and the WebView falls back to its default same-document scroll.
//
// The registry holds a single Volatile handler swapped in by the active reader.
// Multiple readers can't be open at once in this app, so collision is moot.
internal object FootnoteAnchorBridge {
    const val JS_NAME: String = "RiffleFootnoteBridge"

    @Volatile
    private var handler: ((String) -> Boolean)? = null

    fun setHandler(h: ((String) -> Boolean)?) {
        handler = h
    }

    val bridge: Bridge = Bridge()

    class Bridge {
        // Returns true when the anchor was a footnote and the popup will show
        // (so the caller should preventDefault); false otherwise.
        @JavascriptInterface
        fun onAnchorTap(fragmentId: String): Boolean =
            handler?.invoke(fragmentId) ?: false
    }

    // Injected once per loaded resource — idempotent thanks to the window flag.
    //
    // Two accepted same-doc href shapes, matching Continuous mode's
    // [com.riffle.app.feature.reader.ContinuousScriptInjector.SAME_DOC_ANCHOR_LISTENER_JS]:
    //  1. bare '#id' — cheap to detect, skip URL parsing on the hot path.
    //  2. path-prefixed same-chapter reference ('part0007.xhtml#a2C8' clicked from part0007.xhtml —
    //     a common EPUB convention). Resolve against document.location and compare pathname; without
    //     this the guard would skip these and the WebView's default fragment scroll runs, so an
    //     in-view tap still recentres and (worse) the "no-op if already visible" contract silently
    //     bypasses the [FootnoteAnchorBridge] → snapToElement gate that gives that contract to bare
    //     '#id' taps in paged and vertical modes.
    // Truly cross-resource links (a different chapter's pathname) fall through and are handled by
    // Readium's shouldFollowInternalLink → EpubReaderViewModel.followInternalLink.
    val INSTALL_SCRIPT: String = """
        (function(){
          if (window.__riffleFootnoteInstalled) return 'already';
          window.__riffleFootnoteInstalled = true;
          var isAnchor = function(el) {
            return !!el && el.nodeType === 1 && el.tagName &&
              el.tagName.toLowerCase() === 'a';
          };
          document.addEventListener('click', function(e) {
            var t = e.target;
            while (t && t.nodeType === 1 && !isAnchor(t)) t = t.parentNode;
            if (!isAnchor(t)) return;
            var href = t.getAttribute('href');
            if (!href) return;
            var id;
            if (href.charAt(0) === '#') {
              id = href.substring(1);
            } else {
              var resolved;
              try { resolved = new URL(href, document.location.href); }
              catch (err) { return; }
              var sameDoc = resolved.origin === document.location.origin &&
                resolved.pathname === document.location.pathname;
              if (!sameDoc) return;
              id = resolved.hash ? resolved.hash.substring(1) : '';
              // resolved.hash preserves percent-encoding (e.g. '#figure%201'); decode so
              // native's document.getElementById() matches the raw id in the DOM ('figure 1').
              // getAttribute-driven bare '#id' hrefs are already raw.
              try { id = decodeURIComponent(id); } catch (err) {}
            }
            if (!id) return;
            try {
              if (window.$JS_NAME.onAnchorTap(id)) {
                e.preventDefault();
                e.stopPropagation();
              }
            } catch (err) {}
          }, true);
          return 'installed';
        })();
    """.trimIndent()
}
