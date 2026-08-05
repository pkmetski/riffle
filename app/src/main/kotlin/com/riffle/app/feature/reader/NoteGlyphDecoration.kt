@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.feature.reader

import android.os.Parcel
import android.os.Parcelable
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.html.HtmlDecorationTemplate

internal const val NOTE_GLYPH_DECORATION_GROUP = "annotation-notes"
internal const val NOTE_GLYPH_FOCUS_ID_JS_KEY = "__riffleFocusAnnotationId"
internal const val NOTE_GLYPH_VIEWPORT_INSET_PX = 12

/**
 * Completes an annotation jump when the column tracker ran before Readium published the note
 * decoration Range. Readium applies decoration diffs on its own animation frame, so this queues
 * behind that work and consumes the one-shot focus id left by [ColumnSnap.snapToTargetColumnJs].
 */
internal fun noteGlyphFocusAfterApplyJs(): String {
    val group = org.json.JSONObject.quote(NOTE_GLYPH_DECORATION_GROUP)
    return """
        (function(){
          var frames = 0;
          var matchedFrames = 0;
          function tick(){
            try {
              var focusId = window.$NOTE_GLYPH_FOCUS_ID_JS_KEY;
              if (!focusId || !window.readium ||
                  typeof window.readium.getDecorations !== 'function') return;
              var se = document.scrollingElement || document.documentElement;
              var iw = window.innerWidth;
              if (se && iw > 0 && se.scrollWidth > iw + 4) {
                var notes = window.readium.getDecorations($group);
                var items = notes && notes.items ? notes.items : [];
                for (var i = 0; i < items.length; i++) {
                  var item = items[i];
                  if (!item.decoration || item.decoration.id !== focusId) continue;
                  var rects = item.range.getClientRects();
                  var rect = rects && rects.length
                    ? rects[0]
                    : item.range.getBoundingClientRect();
                  if (!rect) return;
                  var target = Math.floor((rect.left + se.scrollLeft) / iw) * iw;
                  if (Math.abs(se.scrollLeft - target) > 1) matchedFrames = 0;
                  else matchedFrames++;
                  se.scrollLeft = target;
                  if (matchedFrames >= 60) {
                    window.$NOTE_GLYPH_FOCUS_ID_JS_KEY = null;
                    return;
                  }
                }
              }
            } catch (e) {}
            if (frames++ < 600) requestAnimationFrame(tick);
            else window.$NOTE_GLYPH_FOCUS_ID_JS_KEY = null;
          }
          requestAnimationFrame(tick);
        })()
    """.trimIndent()
}

/**
 * Keeps each margin glyph inside the left edge of its paginated spread (or the viewport in scroll
 * mode). The decoration remains 28px left of its text whenever the page margin has room; only the
 * portion that would cross the page edge is shifted inward.
 *
 * Readium publishes decoration DOM asynchronously, so retry for a bounded number of frames. Using
 * the decoration parent to identify the spread is important: clamping every offscreen glyph to the
 * current viewport would pull notes from adjacent columns onto the visible page.
 */
internal fun noteGlyphViewportClampAfterApplyJs(): String = """
    (function(){
      var frames = 0;
      var seenFrames = 0;
      function clamp(){
        try {
          var se = document.scrollingElement || document.documentElement;
          var iw = window.innerWidth;
          if (!se || iw <= 0) return;
          var icons = document.querySelectorAll('.$NOTE_GLYPH_ICON_CLASS');
          for (var i = 0; i < icons.length; i++) {
            var icon = icons[i];
            var bounds = icon.parentElement;
            if (!bounds) continue;
            icon.style.webkitTransform = '';
            icon.style.transform = '';
            var boundsRect = bounds.getBoundingClientRect();
            var iconRect = icon.getBoundingClientRect();
            var boundsDocumentLeft = boundsRect.left + se.scrollLeft;
            var spreadLeft = Math.floor(Math.max(0, boundsDocumentLeft) / iw) * iw;
            var iconDocumentLeft = iconRect.left + se.scrollLeft;
            var shift = Math.max(0, spreadLeft + $NOTE_GLYPH_VIEWPORT_INSET_PX - iconDocumentLeft);
            if (shift > 0) {
              var transform = 'translateX(' + shift + 'px)';
              icon.style.webkitTransform = transform;
              icon.style.transform = transform;
            }
          }
          if (icons.length > 0) seenFrames++;
        } catch (e) {}
        if (seenFrames < 4 && frames++ < 72) {
          requestAnimationFrame(clamp);
        }
      }
      clamp();
    })()
""".trimIndent()

// SVG path from Icons.Outlined.NoteAlt (Apache 2.0): document page with ruled lines.
// Percent-encoded so it can be embedded directly in a CSS url() without base64.
// %3C/%3E = < / >
internal const val NOTE_GLYPH_SVG_DATA_URI =
    "data:image/svg+xml," +
    "%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E" +
    "%3Cpath d='M22,10l-6,-6H4C2.9,4,2,4.9,2,6v12c0,1.1,0.9,2,2,2h16c1.1,0,2,-0.9,2,-2V10Z" +
    "M16,4l4,4h-4V4ZM13,18H7v-2h6V18ZM17,14H7v-2h10V14ZM17,10H7V8h10V10Z'/%3E" +
    "%3C/svg%3E"

internal const val NOTE_GLYPH_CLASS = "riffle-note-glyph"
internal const val NOTE_GLYPH_ICON_CLASS = "riffle-note-glyph-icon"

// data-activable="1" tells Readium to use THIS element's rect for hit-testing rather than
// falling back to D.children (the outer BOUNDS div, which only covers the text selection).
// Without it, taps on the gutter icon miss Readium's rect-based activation check.
internal const val NOTE_GLYPH_ELEMENT_HTML =
    """<div class="$NOTE_GLYPH_CLASS"><div class="$NOTE_GLYPH_ICON_CLASS" data-activable="1"></div></div>"""

/**
 * Marker decoration style for noted highlights. No tint — the glyph is monochrome.
 * All noted highlights share the same icon regardless of highlight colour or theme.
 */
class NoteGlyphStyle : Decoration.Style, Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) = Unit

    override fun equals(other: Any?): Boolean = other is NoteGlyphStyle

    override fun hashCode(): Int = NoteGlyphStyle::class.hashCode()

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<NoteGlyphStyle> =
            object : Parcelable.Creator<NoteGlyphStyle> {
                override fun createFromParcel(source: Parcel) = NoteGlyphStyle()
                override fun newArray(size: Int): Array<NoteGlyphStyle?> = arrayOfNulls(size)
            }
    }
}

/**
 * Decoration template for [NoteGlyphStyle]. A transparent BOUNDS div covers the selection;
 * an inner div child is absolutely positioned 28px to the left of the selection's left edge,
 * landing in the left gutter. Using a real DOM child (not ::before) means tap events bubble
 * up through the inner div → outer div → Readium's decoration listener, so glyph taps are
 * reliably detected. Uses CSS masking so the icon inherits `currentColor` — readable on
 * both light and dark reading themes.
 */
fun noteGlyphTemplate(): HtmlDecorationTemplate =
    HtmlDecorationTemplate(
        layout = HtmlDecorationTemplate.Layout.BOUNDS,
        element = { _ -> NOTE_GLYPH_ELEMENT_HTML },
        stylesheet = """
            .$NOTE_GLYPH_CLASS {
                background: none;
                overflow: visible;
                position: relative;
            }
            .$NOTE_GLYPH_ICON_CLASS {
                position: absolute;
                left: -28px;
                top: 2px;
                width: 28px;
                height: 28px;
                -webkit-mask-image: url("$NOTE_GLYPH_SVG_DATA_URI");
                -webkit-mask-size: contain;
                -webkit-mask-repeat: no-repeat;
                background-color: currentColor;
                opacity: 0.40;
            }
        """.trimIndent(),
    )
