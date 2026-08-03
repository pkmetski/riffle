package com.riffle.app.feature.reader

import android.graphics.Color
import android.os.Parcel
import android.os.Parcelable
import androidx.annotation.ColorInt
import org.readium.r2.navigator.Decoration
import java.util.Locale
import org.readium.r2.navigator.html.HtmlDecorationTemplate
import org.readium.r2.navigator.html.toCss

// Readium's default highlight template (Style.Highlight) bakes a FIXED alpha (0.3) into the CSS and
// ignores the tint's own alpha channel. Both readaloud "now speaking" and persisted annotations
// route through this OWN style + template so the tint's own alpha channel (already baked into
// [HighlightColor.argb], the single source of colour AND opacity) is honoured verbatim.

/** Shared decoration style for tinted highlights (readaloud "now speaking" + persisted annotations). */
class HighlightTintStyle(
    @ColorInt override val tint: Int,
) : Decoration.Style, Decoration.Style.Tinted {

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(tint)
    }

    // Value semantics so Readium's decoration diff treats an unchanged tint as unchanged.
    override fun equals(other: Any?): Boolean =
        this === other || (other is HighlightTintStyle && other.tint == tint)

    override fun hashCode(): Int = tint

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<HighlightTintStyle> =
            object : Parcelable.Creator<HighlightTintStyle> {
                override fun createFromParcel(source: Parcel) = HighlightTintStyle(source.readInt())
                override fun newArray(size: Int): Array<HighlightTintStyle?> = arrayOfNulls(size)
            }
    }
}

private const val HIGHLIGHT_TINT_CLASS = "riffle-highlight-tint"

// Every Riffle highlight paint — paginated decoration template AND every continuous-mode
// <mark> the JS emitters inject (annotations, search, readaloud sentence) — must use this
// exact suffix on its inline `background:` declaration. ReadiumCSS night mode injects
// `background-color: transparent !important` on every `:not(a)` descendant of :root; without
// the inline `!important` the highlight is nuked on Dark / DarkDim. Author-sheet `!important`
// competes with inline `!important` on specificity; inline (1,0,0,0) beats the night selector
// (0,2,1), so inline wins. `color:inherit` is a defensive echo of the same rule's `color`
// clobber so highlighted text keeps the theme foreground.
internal const val HIGHLIGHT_INLINE_STYLE_SUFFIX = " !important;color:inherit;"

/** Inline `style` block for a Riffle highlight background. See [HIGHLIGHT_INLINE_STYLE_SUFFIX]. */
internal fun highlightInlineStyle(cssColor: String): String =
    "background:$cssColor$HIGHLIGHT_INLINE_STYLE_SUFFIX"

/**
 * Installs the two geometry functions used by the Readium-overlay and continuous-`<mark>` paths.
 *
 * Android's native selection paint covers the full CSS line box. DOM range rectangles and inline
 * backgrounds only cover the text box, leaving the line-leading transparent. The missing leading
 * is `computed line-height - painted rect height`; splitting it above and below makes adjacent
 * highlight bands meet exactly without changing text layout.
 */
internal val HIGHLIGHT_LEADING_ADJUSTMENT_HELPER_JS: String = """
    (function() {
      var lineHeightOf = function(element) {
        var target = element || document.body || document.documentElement;
        var style = window.getComputedStyle(target);
        var lineHeight = parseFloat(style.lineHeight);
        if (!isFinite(lineHeight)) {
          var fontSize = parseFloat(style.fontSize);
          lineHeight = (isFinite(fontSize) ? fontSize : 16) * 1.2;
        }
        return lineHeight;
      };

      window.__riffleFillInlineHighlightLeading = function(mark) {
        if (!mark) return;
        // Existing marks are recoloured by replacing cssText. Always remeasure from an unpadded
        // inline box so repeat applications cannot compound the previous adjustment.
        mark.style.removeProperty('padding-top');
        mark.style.removeProperty('padding-bottom');
        var rects = mark.getClientRects();
        if (!rects || rects.length === 0) return;
        var leading = Math.max(0, lineHeightOf(mark) - rects[0].height);
        var half = leading / 2;
        mark.style.setProperty('padding-top', half + 'px', 'important');
        mark.style.setProperty('padding-bottom', half + 'px', 'important');
        mark.style.setProperty('-webkit-box-decoration-break', 'clone');
        mark.style.setProperty('box-decoration-break', 'clone');
      };

      window.__riffleFillReadiumHighlightLeading = function() {
        var boxes = document.querySelectorAll('.$HIGHLIGHT_TINT_CLASS');
        Array.prototype.forEach.call(boxes, function(box) {
          var baseTop = parseFloat(box.getAttribute('data-riffle-base-top'));
          var baseHeight = parseFloat(box.getAttribute('data-riffle-base-height'));
          if (!isFinite(baseTop) || !isFinite(baseHeight)) {
            baseTop = parseFloat(box.style.top);
            baseHeight = parseFloat(box.style.height);
            if (!isFinite(baseTop) || !isFinite(baseHeight)) return;
            box.setAttribute('data-riffle-base-top', baseTop);
            box.setAttribute('data-riffle-base-height', baseHeight);
          }

          // Reset before probing so a repeat apply measures the original Readium range box.
          box.style.top = baseTop + 'px';
          box.style.height = baseHeight + 'px';
          var rect = box.getBoundingClientRect();
          var source = null;
          var x = rect.left + Math.min(Math.max(rect.width / 2, 1), Math.max(rect.width - 1, 1));
          var y = rect.top + rect.height / 2;
          if (x >= 0 && x < window.innerWidth && y >= 0 && y < window.innerHeight) {
            // Decoration containers are pointer-events:none, so this resolves the source text
            // underneath the overlay rather than the overlay itself.
            source = document.elementFromPoint(x, y);
          }
          var lineHeight = lineHeightOf(source);
          if (lineHeight <= baseHeight && box.parentNode) {
            // A short final line's centre can fall beyond the paragraph's painted content, making
            // elementFromPoint return body/html. Infer its line-height from the nearest sibling
            // box's original top instead. The 1.5x ceiling excludes paragraph/page breaks.
            var nearestAdvance = Infinity;
            var siblings = box.parentNode.querySelectorAll('.$HIGHLIGHT_TINT_CLASS');
            Array.prototype.forEach.call(siblings, function(sibling) {
              if (sibling === box) return;
              var siblingTop = parseFloat(sibling.getAttribute('data-riffle-base-top'));
              if (!isFinite(siblingTop)) siblingTop = parseFloat(sibling.style.top);
              var advance = Math.abs(siblingTop - baseTop);
              if (advance > 0.5 && advance <= baseHeight * 1.5 && advance < nearestAdvance) {
                nearestAdvance = advance;
              }
            });
            if (isFinite(nearestAdvance)) lineHeight = nearestAdvance;
          }
          var leading = Math.max(0, lineHeight - baseHeight);
          var half = leading / 2;
          box.style.top = (baseTop - half) + 'px';
          box.style.height = (baseHeight + leading) + 'px';
        });
        return boxes.length;
      };
    })();
""".trimIndent()

/** Runs after Readium's own requestAnimationFrame has materialised the fixed decoration boxes. */
internal fun readiumHighlightLeadingAdjustmentJs(): String = """
    $HIGHLIGHT_LEADING_ADJUSTMENT_HELPER_JS
    (function() {
      var attempts = 0;
      var adjust = function() {
        var count = window.__riffleFillReadiumHighlightLeading();
        if (count === 0 && attempts++ < 3) window.setTimeout(adjust, 16);
      };
      if (window.requestAnimationFrame) window.requestAnimationFrame(adjust);
      else window.setTimeout(adjust, 0);
    })();
""".trimIndent()

/**
 * Template for [HighlightTintStyle]. Readium creates one fixed box per DOM range rectangle; the
 * post-apply [readiumHighlightLeadingAdjustmentJs] expands those boxes to the full line-height.
 * Fill opacity comes from the tint's alpha channel rather than Readium's fixed value.
 */
fun highlightTintTemplate(): HtmlDecorationTemplate =
    HtmlDecorationTemplate(
        layout = HtmlDecorationTemplate.Layout.BOXES,
        element = { decoration ->
            val tint = (decoration.style as? Decoration.Style.Tinted)?.tint ?: Color.YELLOW
            """<div class="$HIGHLIGHT_TINT_CLASS" style="${highlightInlineStyle(tint.toCss())}"/>"""
        },
        stylesheet = """
            .$HIGHLIGHT_TINT_CLASS {
                margin: 0 -1px 0 0;
                padding: 0 2px 0 0;
                border-radius: 0;
                box-sizing: border-box;
            }
        """.trimIndent(),
    )

/** ADR 0046: strike-through overlay style. BOXES layout means one div per line; a `::after`
 *  pseudo-element at 50% height paints a horizontal line across the middle of the box, which
 *  coincides with the text's x-height — visually a real strikethrough. Bold/italic can't
 *  reflow via overlays (they need DOM wrapping); this is the only emphasis style beyond
 *  underline that renders correctly with pure decorations. */
class EmphasisStrikeStyle(
    @ColorInt override val tint: Int,
) : Decoration.Style, Decoration.Style.Tinted {
    override fun describeContents(): Int = 0
    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(tint)
    }
    override fun equals(other: Any?): Boolean =
        this === other || (other is EmphasisStrikeStyle && other.tint == tint)
    override fun hashCode(): Int = tint

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<EmphasisStrikeStyle> = object : Parcelable.Creator<EmphasisStrikeStyle> {
            override fun createFromParcel(source: Parcel) = EmphasisStrikeStyle(source.readInt())
            override fun newArray(size: Int): Array<EmphasisStrikeStyle?> = arrayOfNulls(size)
        }
    }
}

private const val EMPHASIS_STRIKE_CLASS = "riffle-emphasis-strike"

fun emphasisStrikeTemplate(): HtmlDecorationTemplate =
    HtmlDecorationTemplate(
        layout = HtmlDecorationTemplate.Layout.BOXES,
        element = { decoration ->
            val tint = (decoration.style as? Decoration.Style.Tinted)?.tint ?: Color.RED
            """<div class="$EMPHASIS_STRIKE_CLASS" style="--riffle-strike-color: ${tint.toCss()};"/>"""
        },
        stylesheet = """
            .$EMPHASIS_STRIKE_CLASS {
                position: absolute;
                pointer-events: none;
            }
            .$EMPHASIS_STRIKE_CLASS::after {
                content: "";
                position: absolute;
                left: 0;
                right: 0;
                top: 50%;
                height: 0;
                border-top: 2px solid var(--riffle-strike-color);
            }
        """.trimIndent(),
    )

/** Convert an ARGB color int to a CSS rgba() string for injection into WebView JS. */
fun @receiver:ColorInt Int.toCssRgba(): String {
    val a = (this ushr 24 and 0xFF) / 255.0
    val r = this ushr 16 and 0xFF
    val g = this ushr 8 and 0xFF
    val b = this and 0xFF
    return "rgba($r,$g,$b,${"%.2f".format(Locale.US, a)})"
}

/** Convert an ARGB color int to a CSS rgba() string, overriding the alpha with [alpha] (0.0–1.0). */
fun @receiver:ColorInt Int.toCssRgbaWithAlpha(alpha: Double): String {
    val r = this ushr 16 and 0xFF
    val g = this ushr 8 and 0xFF
    val b = this and 0xFF
    return "rgba($r,$g,$b,${"%.2f".format(Locale.US, alpha)})"
}
