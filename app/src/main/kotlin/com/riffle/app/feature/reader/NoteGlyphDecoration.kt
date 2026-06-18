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
 * Decoration template for [NoteGlyphStyle]. A single transparent BOUNDS div sits over
 * the selection; its ::before pseudo-element is absolutely positioned to the left of the
 * div (right: 100%), placing the note icon in the left gutter alongside the text.
 * overflow: visible on the host div prevents the gutter icon from being clipped.
 * Uses CSS masking (`-webkit-mask-image`) so the icon inherits `currentColor` — readable
 * on both light and dark reading themes.
 */
fun noteGlyphTemplate(): HtmlDecorationTemplate =
    HtmlDecorationTemplate(
        layout = HtmlDecorationTemplate.Layout.BOUNDS,
        element = { _ -> """<div class="$NOTE_GLYPH_CLASS"/>""" },
        stylesheet = """
            .$NOTE_GLYPH_CLASS {
                background: none;
                overflow: visible;
                position: relative;
            }
            .$NOTE_GLYPH_CLASS::before {
                content: "";
                position: absolute;
                right: 100%;
                top: 0;
                margin-right: 4px;
                width: 16px;
                height: 16px;
                -webkit-mask-image: url("$NOTE_GLYPH_SVG_DATA_URI");
                -webkit-mask-size: contain;
                -webkit-mask-repeat: no-repeat;
                background-color: currentColor;
                opacity: 0.55;
                pointer-events: none;
            }
        """.trimIndent(),
    )
