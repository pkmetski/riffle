package com.riffle.shared

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Material icon path data for the library shell screens.
 *
 * `androidx.compose.material.icons` is an Android-only artifact; Compose Multiplatform's
 * material3 does not bundle it. These vectors are built from the same Material Design path data
 * the androidx icons use so both platforms render identical artwork.
 */
internal object SharedUiIcons {

    /** `Icons.Filled.Home` */
    val Home: ImageVector = icon("Home") {
        listOf("M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z")
    }

    /** `Icons.Filled.Bookmarks` */
    val Bookmarks: ImageVector = icon("Bookmarks") {
        listOf("M15 7H9v11l3-1.5L15 18V7zm2-4H7c-1.1 0-2 .9-2 2v16l7-3 7 3V5c0-1.1-.9-2-2-2z")
    }

    /** `Icons.Filled.Star` */
    val Star: ImageVector = icon("Star") {
        listOf("M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z")
    }

    /** `Icons.Filled.FormatListNumbered` */
    val FormatListNumbered: ImageVector = icon("FormatListNumbered") {
        listOf(
            "M2 17h2v.5H3v1h1v.5H2v1h3v-4H2v1zm1-9h1V4H2v1h1v3zm-1 3h1.8L2 13.1v.9h3v-1H3.2L5 10.9V10H2v1zm5-6v2h14V5H7zm0 14h14v-2H7v2zm0-6h14v-2H7v2z",
        )
    }

    /** `Icons.Filled.Folder` */
    val Folder: ImageVector = icon("Folder") {
        listOf("M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z")
    }

    /** `Icons.Filled.QueueMusic` */
    val QueueMusic: ImageVector = icon("QueueMusic") {
        listOf("M15 6H3v2h12V6zm0 4H3v2h12v-2zM3 16h8v-2H3v2zM17 6v8.18c-.31-.11-.65-.18-1-.18-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3V8h3V6h-5z")
    }

    /** `Icons.Filled.GridView` */
    val GridView: ImageVector = icon("GridView") {
        listOf("M3 3v8h8V3H3zm6 6H5V5h4v4zm-6 4v8h8v-8H3zm6 6H5v-4h4v4zm4-16v8h8V3h-8zm6 6h-4V5h4v4zm-6 4v8h8v-8h-8zm6 6h-4v-4h4v4z")
    }

    /** `Icons.Filled.Menu` */
    val Menu: ImageVector = icon("Menu") {
        listOf("M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z")
    }
}

private fun icon(name: String, pathData: () -> List<String>): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        pathData().forEach { path ->
            addPath(
                pathData = PathParser().parsePathString(path).toNodes(),
                fill = SolidColor(Color.Black),
            )
        }
    }.build()
