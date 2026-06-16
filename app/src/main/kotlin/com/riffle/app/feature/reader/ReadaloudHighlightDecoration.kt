package com.riffle.app.feature.reader

import android.graphics.Color
import android.os.Parcel
import android.os.Parcelable
import androidx.annotation.ColorInt
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.ReadaloudHighlightColor
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.html.HtmlDecorationTemplate
import org.readium.r2.navigator.html.toCss

// Readium's default highlight template (Style.Highlight) bakes a FIXED alpha (0.3) into the CSS and
// ignores the tint's own alpha channel. That makes the readaloud highlight too faint on the Dark
// reading theme — 0.3 of a colour over a black page is barely visible behind the white body text.
//
// We give the readaloud highlight its OWN decoration style + template so we can vary the alpha per
// theme without touching persisted/search highlights (which keep the shared Style.Highlight). The
// template below honours the tint's alpha channel (`toCss()` with no override), so the caller picks
// the strength by baking an alpha byte into the tint — see readerTint().

/** Dedicated decoration style for the readaloud synced highlight. */
class ReadaloudHighlightStyle(
    @ColorInt override val tint: Int,
) : Decoration.Style, Decoration.Style.Tinted {

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(tint)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<ReadaloudHighlightStyle> =
            object : Parcelable.Creator<ReadaloudHighlightStyle> {
                override fun createFromParcel(source: Parcel) = ReadaloudHighlightStyle(source.readInt())
                override fun newArray(size: Int): Array<ReadaloudHighlightStyle?> = arrayOfNulls(size)
            }
    }
}

private const val READALOUD_HIGHLIGHT_CLASS = "riffle-readaloud-highlight"

/**
 * Highlight template for [ReadaloudHighlightStyle]. Geometry mirrors Readium's built-in highlight
 * (BOXES layout, 1px horizontal padding, 3px corner radius) so positioning is unchanged; the only
 * difference is that the fill opacity comes from the tint's alpha channel rather than a fixed value.
 */
fun readaloudHighlightTemplate(): HtmlDecorationTemplate =
    HtmlDecorationTemplate(
        layout = HtmlDecorationTemplate.Layout.BOXES,
        element = { decoration ->
            val tint = (decoration.style as? Decoration.Style.Tinted)?.tint ?: Color.YELLOW
            """<div class="$READALOUD_HIGHLIGHT_CLASS" style="background-color: ${tint.toCss()} !important;"/>"""
        },
        stylesheet = """
            .$READALOUD_HIGHLIGHT_CLASS {
                margin: 0px -1px 0 0;
                padding: 0 2px 0 0;
                border-radius: 3px;
                box-sizing: border-box;
            }
        """.trimIndent(),
    )

/**
 * The tint to paint the readaloud highlight with on the given reading [theme]. Dark/DarkDim pages
 * use a stronger alpha so the highlight reads as a clear "selection box" behind the white body text;
 * light and sepia pages use Readium's usual ~30% so the dark body text stays legible.
 */
@ColorInt
fun ReadaloudHighlightColor.readerTint(theme: ReaderTheme): Int {
    val alpha = when (theme) {
        ReaderTheme.Dark, ReaderTheme.DarkDim -> 0x73 // ~45%
        else -> 0x4D // ~30%
    }
    return (argb and 0x00FFFFFF) or (alpha shl 24)
}
