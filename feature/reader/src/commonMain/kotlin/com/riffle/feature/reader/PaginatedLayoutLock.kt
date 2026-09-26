package com.riffle.feature.reader

/**
 * CSS injected in paginated mode to prevent hostile publisher stylesheets from breaking
 * Readium's column-grid layout.
 *
 * ReadiumCSS-after.css sets the paginated layout properties (`height: 100vh`, column rules)
 * WITHOUT `!important`, so an EPUB whose bundled CSS uses `height: auto !important` wins the
 * cascade, collapses the multicol container, and lets the page scroll vertically instead of
 * turning horizontally. (O'Reilly EPUB3 books are a confirmed instance.)
 *
 * Strategy: inject a `<style>` element after page load with the same layout properties using
 * `!important`. The `:not([style*="readium-scroll-on"])` guard limits the rule to paginated
 * mode — Readium adds that attribute to `:root` in scroll mode, so this rule becomes a no-op
 * there and vertical/continuous modes are unaffected.
 */
object PaginatedLayoutLock {

    private val CSS: String = """
        :root:not([style*="readium-scroll-on"]) {
          height: 100vh !important;
          max-height: 100vh !important;
          min-height: 100vh !important;
          overflow-y: hidden !important;
        }
    """.trimIndent()

    val INSTALL_SCRIPT: String = run {
        val css = CSS
            .replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("$", "\\\$")
        """
            (function() {
              var id = 'riffle-paginated-layout-lock';
              if (document.getElementById(id)) return;
              var style = document.createElement('style');
              style.id = id;
              style.textContent = `$css`;
              document.head.appendChild(style);
            })();
        """.trimIndent()
    }
}
