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
internal fun figureBorderApplyJs(
    cssRules: List<String>,
    svgMatches: List<FigureBorderDecoration.SvgMatch> = emptyList(),
): String {
    val css = cssRules.joinToString("\n")
        .replace("\\", "\\\\")
        .replace("`", "\\`")
        .replace("\$", "\\\$")
    val svgJson = svgMatches.joinToString(",", prefix = "[", postfix = "]") { m ->
        val escFp = m.fingerprint
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
            .replace("\t", " ")
        val escColor = m.color.replace("\"", "\\\"")
        "{\"fp\":\"$escFp\",\"color\":\"$escColor\"}"
    }
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
          // Match annotated SVGs and apply an inline outline. Clears any stale outlines we set
          // previously so a delete/recolor reflects immediately.
          //
          // Fingerprint comparison uses the SAME serialiser the annotation was stored with
          // (XMLSerializer, from FigureCaptionWalker.SVG_SERIALIZER_JS's `serializeSvg`).
          // Earlier attempts compared the stored value against `element.outerHTML` — subtly
          // different (different attribute order, namespace handling) — so the prefix never
          // matched. To further tolerate re-serialisation drift on reload, we also match on
          // element.innerHTML prefix as a fallback.
          try {
            var matches = $svgJson;
            var ser = null;
            try { ser = new XMLSerializer(); } catch (e2) {}
            var svgs = document.querySelectorAll('svg');
            // Diag flash removed — raster figures now match via filename suffix in CSS, no JS
            // needed for them; SVG matching stays JS-based.
            for (var i = 0; i < svgs.length; i++) {
              var s = svgs[i];
              if (s.__riffleBorderApplied) {
                s.style.outline = '';
                s.style.outlineOffset = '';
                s.__riffleBorderApplied = false;
              }
              var outer = ser ? ser.serializeToString(s) : (s.outerHTML || '');
              var inner = s.innerHTML || '';
              for (var j = 0; j < matches.length; j++) {
                var fp = matches[j].fp;
                if (outer.indexOf(fp) === 0 || (inner.length && fp.indexOf(inner.slice(0, 40)) !== -1)) {
                  s.style.outline = '2px solid ' + matches[j].color;
                  s.style.outlineOffset = '2px';
                  s.__riffleBorderApplied = true;
                  break;
                }
              }
            }
          } catch (e) {}
        })();
    """.trimIndent()
}
