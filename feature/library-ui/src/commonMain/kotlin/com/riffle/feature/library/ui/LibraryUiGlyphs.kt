package com.riffle.feature.library.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The handful of Material Symbols this module's screens draw.
 *
 * `androidx.compose.material.icons` is an Android-only artifact and
 * `org.jetbrains.compose.material:material-icons-core` stopped being published after 1.7.3, so a
 * module that both `:app` and `:shared` render cannot depend on either — the same constraint that
 * produced [com.riffle.feature.source.ui.SourceUiIcons],
 * `com.riffle.feature.reader.ui.ReaderGlyphs` and `com.riffle.feature.player.ui.PlayerGlyphs`.
 *
 * The path data is copied verbatim from the Material `material-icons-*` sources, so Android
 * renders exactly the artwork it rendered when these screens still imported `Icons.Filled.*`, and
 * iOS renders the same. Do not hand-edit the coordinates.
 *
 * These are private to the library-browsing surfaces. Do not grow this into a general icon
 * library.
 */
internal object LibraryUiGlyphs {

    /** `Icons.AutoMirrored.Filled.ArrowBack` */
    val ArrowBack: ImageVector = glyph("ArrowBack", autoMirror = true) {
        listOf("M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z")
    }

    /** `Icons.AutoMirrored.Filled.QueueMusic` */
    val QueueMusic: ImageVector = glyph("QueueMusic", autoMirror = true) {
        listOf(
            "M15 6H3v2h12V6zm0 4H3v2h12v-2zM3 16h8v-2H3v2zm14-10v8.18c-.31-.11-.65-.18-1-.18-1.66 " +
                "0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3V8h3V6h-5z",
        )
    }

    /** `Icons.AutoMirrored.Filled.KeyboardArrowRight` — the row's drill-in chevron. */
    val ChevronRight: ImageVector = glyph("ChevronRight", autoMirror = true) {
        listOf("M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z")
    }

    /** `Icons.Filled.PlayArrow` */
    val PlayArrow: ImageVector = glyph("PlayArrow") {
        listOf("M8 5v14l11-7z")
    }

    /** `Icons.Filled.RemoveCircleOutline` */
    val RemoveCircleOutline: ImageVector = glyph("RemoveCircleOutline") {
        listOf(
            "M7 11v2h10v-2H7zm5-9C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 " +
                "18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z",
        )
    }

    /** `Icons.Filled.Add` */
    val Add: ImageVector = glyph("Add") {
        listOf("M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")
    }

    /** `Icons.Filled.Bookmark` */
    val Bookmark: ImageVector = glyph("Bookmark") {
        listOf("M17 3H7c-1.1 0-1.99.9-1.99 2L5 21l7-3 7 3V5c0-1.1-.9-2-2-2z")
    }

    /** `Icons.Filled.Search` */
    val Search: ImageVector = glyph("Search") {
        listOf(
            "M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 " +
                "9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 " +
                "0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z",
        )
    }

    /** `Icons.Filled.Check` */
    val Check: ImageVector = glyph("Check") {
        listOf("M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z")
    }

    /** `Icons.Filled.Delete` */
    val Delete: ImageVector = glyph("Delete") {
        listOf("M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z")
    }

    /** `Icons.AutoMirrored.Filled.MenuBook` */
    val MenuBook: ImageVector = glyph("MenuBook", autoMirror = true) {
        listOf(
            "M21 5c-1.11-.35-2.33-.5-3.5-.5-1.95 0-4.05.4-5.5 1.5-1.45-1.1-3.55-1.5-5.5-1.5S2.45 " +
                "4.9 1 6v14.65c0 .25.25.5.5.5.1 0 .15-.05.25-.05C3.1 20.45 5.05 20 6.5 20c1.95 0 " +
                "3.75.4 5 1.5 1.35-.85 3.8-1.5 5.5-1.5 1.65 0 3.35.3 4.75 1.05.1.05.15.05.25.05" +
                ".25 0 .5-.25.5-.5V6c-.6-.45-1.25-.75-2-1zm0 13.5c-1.1-.35-2.3-.5-3.5-.5-1.7 0-4.15" +
                ".65-5.5 1.5V8c1.35-.85 3.8-1.5 5.5-1.5 1.2 0 2.4.15 3.5.5v11.5z",
        )
    }

    /** `Icons.Filled.PictureAsPdf` */
    val PictureAsPdf: ImageVector = glyph("PictureAsPdf") {
        listOf(
            "M20 2H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-8.5 " +
                "7.5c0 .83-.67 1.5-1.5 1.5H9v2H7.5V7H10c.83 0 1.5.67 1.5 1.5v1zm5 2c0 .83-.67 " +
                "1.5-1.5 1.5h-2.5V7H15c.83 0 1.5.67 1.5 1.5v3zm4-3H19v1h1.5V11H19v2h-1.5V7h3v1.5zM9" +
                " 9.5h1v-1H9v1zM4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6zm10 5.5h1v-3h-1v3z",
        )
    }

    /** `Icons.Filled.GridView` */
    val GridView: ImageVector = glyph("GridView") {
        listOf(
            "M3 3v8h8V3H3zm6 6H5V5h4v4zm-6 4v8h8v-8H3zm6 6H5v-4h4v4zm4-16v8h8V3h-8zm6 6h-4V5h4v4zm" +
                "-6 4v8h8v-8h-8zm6 6h-4v-4h4v4z",
        )
    }

    /** `Icons.Filled.GraphicEq` */
    val GraphicEq: ImageVector = glyph("GraphicEq") {
        listOf("M7 18h2V6H7v12zm4 4h2V2h-2v20zm-8-8h2v-4H3v4zm12 4h2V6h-2v12zm4-8v4h2v-4h-2z")
    }
}

/**
 * Builds a 24dp Material icon whose paths are filled with the current content colour
 * ([Color.Black] is replaced by `Icon`'s tint, exactly as with the androidx icons).
 */
private fun glyph(
    name: String,
    autoMirror: Boolean = false,
    pathData: () -> List<String>,
): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = autoMirror,
    ).apply {
        pathData().forEach { path ->
            addPath(
                pathData = PathParser().parsePathString(path).toNodes(),
                fill = SolidColor(Color.Black),
            )
        }
    }.build()
