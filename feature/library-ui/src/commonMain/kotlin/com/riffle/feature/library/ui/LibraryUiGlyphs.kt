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
