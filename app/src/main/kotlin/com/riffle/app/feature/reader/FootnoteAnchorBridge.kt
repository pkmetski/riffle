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
            if (!href || href.charAt(0) !== '#') return;
            var id = href.substring(1);
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
