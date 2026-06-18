@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.feature.reader

import android.os.Parcel
import android.os.Parcelable
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.html.HtmlDecorationTemplate

// SVG path from Icons.AutoMirrored.Outlined.StickyNote2 (Apache 2.0).
// Percent-encoded so it can be embedded directly in a CSS url() without base64.
// %3C/%3E = < / >
internal const val NOTE_GLYPH_SVG_DATA_URI =
    "data:image/svg+xml," +
    "%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E" +
    "%3Cpath d='M19,5v9l-5,0,0,5H5V5H19M19,3H5C3.9,3,3,3.9,3,5v14" +
    "c0,1.1,.9,2,2,2h10l6,-6V5C21,3.9,20.1,3,19,3Z" +
    "M12,14H7v-2h5V14ZM17,10H7V8h10V10Z'/%3E" +
    "%3C/svg%3E"

private const val NOTE_GLYPH_CLASS = "riffle-note-glyph"
private const val NOTE_GLYPH_ICON_CLASS = "riffle-note-glyph-icon"

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
 * an inner div child is absolutely positioned 24px to the left of the selection's left edge,
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
                left: -24px;
                top: 2px;
                width: 24px;
                height: 24px;
                -webkit-mask-image: url("$NOTE_GLYPH_SVG_DATA_URI");
                -webkit-mask-size: contain;
                -webkit-mask-repeat: no-repeat;
                background-color: currentColor;
                opacity: 0.55;
            }
        """.trimIndent(),
    )
