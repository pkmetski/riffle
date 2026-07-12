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
    rasterMarks: List<FigureBorderDecoration.RasterMark> = emptyList(),
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
        "{\"fp\":\"$escFp\",\"color\":\"$escColor\",\"note\":${if (m.hasNote) 1 else 0}}"
    }
    val rasterJson = rasterMarks.joinToString(",", prefix = "[", postfix = "]") { m ->
        val escFn = m.filename.replace("\\", "\\\\").replace("\"", "\\\"")
        val escColor = m.color.replace("\"", "\\\"")
        "{\"fn\":\"$escFn\",\"color\":\"$escColor\",\"note\":${if (m.hasNote) 1 else 0}}"
    }
    // Percent-encoded SVG of the same note-alt icon used by NoteGlyphDecoration. The literal
    // single quotes inside the SVG attributes are percent-encoded (%27) — otherwise Kotlin's
    // string interpolation drops them into the surrounding JS single-quoted string and closes
    // it early, breaking the whole injection script.
    val noteIconDataUri =
        "data:image/svg+xml," +
            "%3Csvg xmlns=%27http://www.w3.org/2000/svg%27 viewBox=%270 0 24 24%27%3E" +
            "%3Cpath d=%27M22,10l-6,-6H4C2.9,4,2,4.9,2,6v12c0,1.1,0.9,2,2,2h16c1.1,0,2,-0.9,2,-2V10Z" +
            "M16,4l4,4h-4V4ZM13,18H7v-2h6V18ZM17,14H7v-2h10V14ZM17,10H7V8h10V10Z%27/%3E" +
            "%3C/svg%3E"
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
          // Ensure the note-badge CSS is in place. Positioned OUTSIDE the figure's border by using
          // negative offsets on an absolute-positioned child inside a position:relative wrapper.
          if (!document.getElementById('$FIGURE_BORDER_STYLE_ID-notes')) {
            var noteStyle = document.createElement('style');
            noteStyle.id = '$FIGURE_BORDER_STYLE_ID-notes';
            noteStyle.textContent =
              '.riffle-fig-wrapper { position: relative; display: inline-block; }' +
              '.riffle-fig-note-badge { position: absolute; top: -8px; right: -8px; width: 22px; height: 22px; ' +
              '  border-radius: 50%; background: #ffffff; display: flex; align-items: center; justify-content: center; ' +
              '  box-shadow: 0 1px 3px rgba(0,0,0,0.35); pointer-events: none; z-index: 2; }' +
              '.riffle-fig-note-badge > span { display: block; width: 14px; height: 14px; ' +
              '  -webkit-mask-image: url("$noteIconDataUri"); -webkit-mask-size: contain; ' +
              '  -webkit-mask-repeat: no-repeat; background-color: currentColor; opacity: 0.80; }';
            document.head.appendChild(noteStyle);
          }
          function clearNoteBadgeAround(el) {
            var w = el && el.parentNode;
            if (w && w.classList && w.classList.contains('riffle-fig-wrapper')) {
              var badges = w.querySelectorAll('.riffle-fig-note-badge');
              for (var b = 0; b < badges.length; b++) w.removeChild(badges[b]);
            }
          }
          function ensureWrap(el) {
            var p = el && el.parentNode;
            if (p && p.classList && p.classList.contains('riffle-fig-wrapper')) return p;
            if (!p) return null;
            var span = document.createElement('span');
            span.className = 'riffle-fig-wrapper';
            p.insertBefore(span, el);
            span.appendChild(el);
            return span;
          }
          function addNoteBadge(el, color) {
            var wrap = ensureWrap(el);
            if (!wrap) return;
            clearNoteBadgeAround(el);
            var badge = document.createElement('div');
            badge.className = 'riffle-fig-note-badge';
            badge.style.color = color;
            badge.innerHTML = '<span></span>';
            wrap.appendChild(badge);
          }
          function clearAllFigcaptionTints() {
            var stale = document.querySelectorAll('[data-riffle-fig-tint]');
            for (var k = 0; k < stale.length; k++) {
              // Match how we set it (setProperty with 'important'); plain assignment can leave
              // the important-flagged declaration in place.
              stale[k].style.removeProperty('background-color');
              stale[k].removeAttribute('data-riffle-fig-tint');
            }
          }
          // Fallback for EPUBs that don't use semantic <figure>/<figcaption>: LaTeX/Kotobee/Vellum
          // exports typically wrap the image and its caption in generic <div>s with obfuscated
          // class names ("class_s4", "class_s5"), so we can't key off markup. The one deterministic
          // signal is the caption's leading text: "Figure 5.1:", "Fig. 2", "Table 3", etc. Walk up
          // to 3 ancestors and scan for the nearest block after the image whose text starts with
          // that prefix. Bounded and content-anchored — won't grab body prose.
          var CAPTION_PREFIX_RX = /^\s*(Figure|Fig\.?|Table|Chart)\s+\d/i;
          function nearestCaptionBlock(img) {
            var el = img;
            for (var hops = 0; hops < 3; hops++) {
              var parent = el.parentElement;
              if (!parent) return null;
              var blocks = parent.querySelectorAll('p, div');
              for (var i = 0; i < blocks.length; i++) {
                var b = blocks[i];
                if (b === img || b.contains(img)) continue;
                var pos = img.compareDocumentPosition(b);
                if (!(pos & 4)) continue;
                if (CAPTION_PREFIX_RX.test(b.textContent || '')) return b;
              }
              el = parent;
            }
            return null;
          }
          function tintCaptionFor(el, color) {
            if (!el) return;
            var cap = null;
            var fig = el.closest && el.closest('figure, [role="figure"]');
            // Unscoped 'figcaption' (any depth) mirrors FigureCaptionWalker.resolveCaption, so an
            // annotation whose textSnippet captures a nested <figure><div><figcaption> also gets
            // a matching tint. HTML5 restricts figcaption to first/last child of figure, but real
            // EPUBs nest it inside wrappers.
            if (fig) cap = fig.querySelector('figcaption');
            if (!cap) cap = nearestCaptionBlock(el);
            if (!cap) return;
            // Use setProperty with 'important' so publisher CSS resets (Wiley et al.) don't win.
            cap.style.setProperty('background-color', color, 'important');
            cap.setAttribute('data-riffle-fig-tint', '1');
          }
          clearAllFigcaptionTints();
          try {
            // Raster: iterate the matched images and add/remove note badges. Border itself comes
            // from the CSS style block above; badge lives on the wrapper we insert here.
            var rasters = $rasterJson;
            for (var ri = 0; ri < rasters.length; ri++) {
              var rf = rasters[ri];
              var imgs = document.querySelectorAll('img[src\$="' + rf.fn + '"]');
              for (var ii = 0; ii < imgs.length; ii++) {
                var img = imgs[ii];
                tintCaptionFor(img, rf.color);
                if (rf.note) {
                  var col = (window.getComputedStyle && window.getComputedStyle(img).outlineColor) || 'currentColor';
                  addNoteBadge(img, col);
                } else {
                  clearNoteBadgeAround(img);
                }
              }
            }
          } catch (er) {}
          try {
            var matches = $svgJson;
            var ser = null;
            try { ser = new XMLSerializer(); } catch (e2) {}
            var svgs = document.querySelectorAll('svg');
            for (var i = 0; i < svgs.length; i++) {
              var s = svgs[i];
              if (s.__riffleBorderApplied) {
                s.style.removeProperty('outline');
                s.style.removeProperty('outline-offset');
                s.__riffleBorderApplied = false;
              }
              clearNoteBadgeAround(s);
              var outer = ser ? ser.serializeToString(s) : (s.outerHTML || '');
              var inner = s.innerHTML || '';
              for (var j = 0; j < matches.length; j++) {
                var fp = matches[j].fp;
                if (outer.indexOf(fp) === 0 || (inner.length && fp.indexOf(inner.slice(0, 40)) !== -1)) {
                  s.style.setProperty('outline', '2px solid ' + matches[j].color, 'important');
                  s.style.setProperty('outline-offset', '2px', 'important');
                  s.__riffleBorderApplied = true;
                  tintCaptionFor(s, matches[j].color);
                  if (matches[j].note) addNoteBadge(s, matches[j].color);
                  break;
                }
              }
            }
          } catch (e) {}
        })();
    """.trimIndent()
}
