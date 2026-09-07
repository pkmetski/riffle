package com.riffle.feature.source.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The handful of Material Symbols this module's screens draw.
 *
 * `androidx.compose.material.icons` is an Android-only artifact — Compose Multiplatform's
 * material3 does not ship it and `org.jetbrains.compose.material:material-icons-core` stopped
 * being published after 1.7.3. Rather than pin a stale icon artifact (or downgrade material3),
 * the six glyphs used here are declared as [ImageVector]s from the same Material path data the
 * androidx icons use, so Android and iOS render identical artwork.
 *
 * These are private to the source-onboarding surface. Do not grow this into a general icon
 * library — if a screen needs many icons it belongs in a dedicated shared design-system module.
 */
internal object SourceUiIcons {

    /** `Icons.AutoMirrored.Filled.ArrowBack` */
    val ArrowBack: ImageVector = materialIcon("ArrowBack", autoMirror = true) {
        listOf("M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z")
    }

    /** `Icons.Filled.ArrowDropDown` */
    val ArrowDropDown: ImageVector = materialIcon("ArrowDropDown") {
        listOf("M7 10l5 5 5-5z")
    }

    /** `Icons.Filled.CheckCircle` */
    val CheckCircle: ImageVector = materialIcon("CheckCircle") {
        listOf(
            "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 " +
                "1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z",
        )
    }

    /** `Icons.Filled.Folder` */
    val Folder: ImageVector = materialIcon("Folder") {
        listOf("M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z")
    }

    /** `Icons.Filled.Schedule` */
    val Schedule: ImageVector = materialIcon("Schedule") {
        listOf(
            "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 " +
                "20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8z",
            "M12.5 7H11v6l5.25 3.15.75-1.23-4.5-2.67z",
        )
    }

    /** `Icons.Filled.Warning` */
    val Warning: ImageVector = materialIcon("Warning") {
        listOf("M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z")
    }
}

/**
 * Builds a 24dp Material icon whose paths are filled with the current content colour
 * ([Color.Black] is replaced by `Icon`'s tint, exactly as with the androidx icons).
 */
private fun materialIcon(
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
