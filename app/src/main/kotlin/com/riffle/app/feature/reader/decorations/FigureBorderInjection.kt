package com.riffle.app.feature.reader.decorations

/**
 * JS glue that pushes [FigureBorderDecoration.buildCssRules] output into a live document as a
 * single `<style>` element, mirroring the install/apply split used by the readaloud reserve
 * (`ReadaloudReserve.kt`): [figureBorderInjectionJs] idempotently creates the `<style>` tag once
 * per document (safe to call on every page load), and [figureBorderApplyJs] replaces its content
 * — unlike the reserve's fixed CSS, the border rules change whenever the annotation set changes,
 * so the content must always be rewritten, not skipped when already present.
 */

/** Stable element id for the injected `<style>` so repeated injections don't accumulate. */
private const val FIGURE_BORDER_STYLE_ID = "riffle-figure-borders"

/**
 * Idempotently ensures a `<style id="riffle-figure-borders">` element exists in `document.head`.
 * Safe to call on every page load; a no-op if the element is already present.
 */
internal fun figureBorderInjectionJs(): String =
    """
    (function() {
      var id = '$FIGURE_BORDER_STYLE_ID';
      if (document.getElementById(id)) return;
      var style = document.createElement('style');
      style.id = id;
      document.head.appendChild(style);
    })();
    """.trimIndent()

/**
 * Replaces the content of the `<style id="riffle-figure-borders">` element with [cssRules]
 * (already-built rule strings, e.g. from [FigureBorderDecoration.buildCssRules]). Creates the
 * element first if [figureBorderInjectionJs] hasn't run yet on this document. Call whenever the
 * underlying annotation set changes, and once per page load so a freshly served resource picks
 * up the current rules immediately.
 */
internal fun figureBorderApplyJs(cssRules: List<String>): String {
    val css = cssRules.joinToString("\n")
        .replace("\\", "\\\\")
        .replace("`", "\\`")
        .replace("\$", "\\\$")
    return """
        (function() {
          var id = '$FIGURE_BORDER_STYLE_ID';
          var style = document.getElementById(id);
          if (!style) {
            style = document.createElement('style');
            style.id = id;
            document.head.appendChild(style);
          }
          style.textContent = `$css`;
        })();
    """.trimIndent()
}
