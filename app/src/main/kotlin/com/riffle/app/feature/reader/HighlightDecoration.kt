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

/**
 * Template for [HighlightTintStyle]. Geometry mirrors Readium's built-in highlight (BOXES layout,
 * 1px horizontal padding, 3px corner radius) so positioning is unchanged; the only difference is the
 * fill opacity comes from the tint's alpha channel rather than a fixed value.
 */
fun highlightTintTemplate(): HtmlDecorationTemplate =
    HtmlDecorationTemplate(
        layout = HtmlDecorationTemplate.Layout.BOXES,
        element = { decoration ->
            val tint = (decoration.style as? Decoration.Style.Tinted)?.tint ?: Color.YELLOW
            """<div class="$HIGHLIGHT_TINT_CLASS" style="background-color: ${tint.toCss()} !important;"/>"""
        },
        stylesheet = """
            .$HIGHLIGHT_TINT_CLASS {
                margin: 0px -1px 0 0;
                padding: 0 2px 0 0;
                border-radius: 3px;
                box-sizing: border-box;
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
