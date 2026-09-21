package com.riffle.feature.source.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Material Symbols used by the screens this module hosts for *both* platforms (the settings
 * Sources list and the per-format download controls).
 *
 * Same constraint as the source-onboarding glyphs: `androidx.compose.material.icons` is an
 * Android-only artifact, so a composable that Android and iOS both render cannot reference it.
 * Each glyph below carries the exact path data of the androidx icon it replaces, so lifting a
 * composable out of `:app` into this module does not change a single pixel on Android.
 *
 * This is a holding pen, not a design system: when the shared design-system module lands these
 * move there wholesale. Until then, keep it to glyphs that a *shared* composable in this module
 * actually draws.
 */
internal object MaterialGlyphs {

    /** `Icons.Filled.ArrowDownward` */
    val ArrowDownward: ImageVector = glyph("ArrowDownward") {
        listOf("M20 12l-1.41-1.41L13 16.17V4h-2v12.17l-5.58-5.59L4 12l8 8 8-8z")
    }

    /** `Icons.Filled.Add` */
    val Add: ImageVector = glyph("Add") {
        listOf("M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")
    }

    /** `Icons.Filled.Delete` */
    val Delete: ImageVector = glyph("Delete") {
        listOf(
            "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z",
        )
    }

    /** `Icons.AutoMirrored.Filled.KeyboardArrowRight` */
    val KeyboardArrowRight: ImageVector = glyph("KeyboardArrowRight", autoMirror = true) {
        listOf("M8.59 16.59L13.17 12 8.59 7.41 10 6l6 6-6 6-1.41-1.41z")
    }

    /** `Icons.Filled.KeyboardArrowUp` */
    val KeyboardArrowUp: ImageVector = glyph("KeyboardArrowUp") {
        listOf("M7.41 15.41L12 10.83l4.59 4.58L18 14l-6-6-6 6z")
    }

    /** `Icons.Filled.KeyboardArrowDown` */
    val KeyboardArrowDown: ImageVector = glyph("KeyboardArrowDown") {
        listOf("M7.41 8.59L12 13.17l4.59-4.58L18 10l-6 6-6-6z")
    }
}

/**
 * Builds a 24dp Material glyph whose paths are filled with the current content colour
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
