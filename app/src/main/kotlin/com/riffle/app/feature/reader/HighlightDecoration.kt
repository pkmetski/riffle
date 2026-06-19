package com.riffle.app.feature.reader

import android.graphics.Color
import android.os.Parcel
import android.os.Parcelable
import androidx.annotation.ColorInt
import com.riffle.core.domain.HighlightColor
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.ReadaloudHighlightColor
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.html.HtmlDecorationTemplate
import org.readium.r2.navigator.html.toCss

// Readium's default highlight template (Style.Highlight) bakes a FIXED alpha (0.3) into the CSS and
// ignores the tint's own alpha channel — too faint on the Dark reading theme (0.3 of a colour over a
// black page is barely visible behind the white body text). Both the readaloud "now speaking"
// highlight and the persisted annotation highlights use this OWN style + template instead, so the
// fill opacity can vary per theme (the template honours the tint's alpha; the caller bakes in the
// strength via tintForTheme()).

// Fill opacity baked into the tint's alpha channel per reading theme. Dark pages need a stronger
// alpha so the highlight reads as a clear selection box behind the white body text.
internal const val HIGHLIGHT_ALPHA_DARK = 0x73 // ~45%
internal const val HIGHLIGHT_ALPHA_LIGHT = 0x4D // ~30%

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

/**
 * Bake the per-[theme] fill opacity into [argb]'s alpha channel. Dark/DarkDim pages use a stronger
 * alpha so the highlight reads as a clear box behind white body text; light/sepia use Readium's
 * usual ~30% so dark body text stays legible.
 */
@ColorInt
fun tintForTheme(@ColorInt argb: Int, theme: ReaderTheme): Int {
    val alpha = when (theme) {
        ReaderTheme.Dark, ReaderTheme.DarkDim -> HIGHLIGHT_ALPHA_DARK
        else -> HIGHLIGHT_ALPHA_LIGHT
    }
    return (argb and 0x00FFFFFF) or (alpha shl 24)
}

/** The tint to paint a persisted-annotation highlight with on the given reading [theme]. */
@ColorInt
fun HighlightColor.readerTint(theme: ReaderTheme): Int = tintForTheme(argb, theme)

/** The tint to paint the readaloud "now speaking" highlight with on the given reading [theme]. */
@ColorInt
fun ReadaloudHighlightColor.readerTint(theme: ReaderTheme): Int = tintForTheme(argb, theme)

/** Convert an ARGB color int to a CSS rgba() string for injection into WebView JS. */
fun @receiver:ColorInt Int.toCssRgba(): String {
    val a = (this ushr 24 and 0xFF) / 255.0
    val r = this ushr 16 and 0xFF
    val g = this ushr 8 and 0xFF
    val b = this and 0xFF
    return "rgba($r,$g,$b,${"%.2f".format(a)})"
}
