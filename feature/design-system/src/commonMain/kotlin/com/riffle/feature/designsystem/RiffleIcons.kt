package com.riffle.feature.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Riffle's one icon registry — one glyph per concept, for both hosts.
 *
 * There used to be three of these and they disagreed:
 *
 *  - `com.riffle.app.ui.theme.RiffleIcons` held the *semantic* concepts (To-Read, Annotations)
 *    against `androidx.compose.material.icons`, which is an Android-only artifact.
 *  - `com.riffle.feature.source.ui.SourceUiIcons` hand-drew ten Material glyphs for the
 *    source-onboarding screens, with a KDoc warning: "Do not grow this into a general icon
 *    library — if a screen needs many icons it belongs in a dedicated shared design-system
 *    module."
 *  - `com.riffle.shared.SharedUiIcons` then became exactly that general library, and **inverted
 *    two concepts**: To-Read was drawn with [Bookmarks], the glyph Android's registry reserves
 *    for Annotations, and Annotations was drawn with a Star that exists nowhere in Android's
 *    design system. Its builder also had no `autoMirror` parameter, so [QueueMusic] and
 *    [FormatListNumbered] were not mirrored in a right-to-left layout.
 *
 * This object is that "dedicated shared design-system module". It has two layers:
 *
 *  - **Artwork** ([Home], [Bookmarks], [Search], …) — Material Symbols path data, drawn by hand
 *    because `androidx.compose.material.icons` is Android-only and
 *    `org.jetbrains.compose.material:material-icons-core` stopped being published after 1.7.3.
 *    Same path data the androidx icons use, so the two platforms render identical artwork.
 *  - **Concepts** ([ToRead], [ToReadFilled], [Annotations], …) — the registry proper. Route every
 *    icon that represents one of these concepts through the concept name; never re-import the
 *    underlying artwork at a call site, on either platform. The Annotations concept owns the
 *    `Bookmark*` family; do not use it for anything else.
 *
 * `autoMirror` is declared per glyph, so the icons Material mirrors in RTL (the back arrow, the
 * two playlist glyphs, the numbered list, the queue) are mirrored on both platforms.
 */
object RiffleIcons {

    // ── Concepts ──────────────────────────────────────────────────────────────────────────────
    // The reading-queue / "save for later" concept: the details-screen toggle and the library
    // nav tab. NOT the Bookmark family — that belongs to [Annotations].

    /** The reading queue, unselected. */
    val ToRead: ImageVector get() = PlaylistAdd

    /** The reading queue, selected. */
    val ToReadFilled: ImageVector get() = PlaylistAddCheck

    /** Highlights + notes + reader-level bookmarks. This concept owns the `Bookmark*` family. */
    val Annotations: ImageVector get() = Bookmarks

    /** Synced-narration (readaloud) badge on a cover. */
    val ReadaloudBadge: ImageVector get() = Headphones

    // ── Artwork ───────────────────────────────────────────────────────────────────────────────

    /** `Icons.AutoMirrored.Filled.ArrowBack` */
    val ArrowBack: ImageVector = materialIcon("ArrowBack", autoMirror = true) {
        listOf("M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z")
    }

    /** `Icons.Filled.ArrowDropDown` */
    val ArrowDropDown: ImageVector = materialIcon("ArrowDropDown") {
        listOf("M7 10l5 5 5-5z")
    }

    /**
     * `Icons.Filled.Bookmarks` — path data transcribed node-for-node from the androidx vector
     * (`RiffleIconsTest` asserts the two are geometrically identical, so Android renders exactly
     * what it always did). `SharedUiIcons` used a different `bookmarks` variant, which is how
     * iOS and Android ended up drawing visibly different artwork for the same concept.
     */
    val Bookmarks: ImageVector = materialIcon("Bookmarks") {
        listOf(
            "M 19 18 l 2 1 V 3 c 0 -1.1 -0.9 -2 -2 -2 H 8.99 C 7.89 1 7 1.9 7 3 h 10 c 1.1 0 2 " +
                "0.9 2 2 v 13 z M 15 5 H 5 c -1.1 0 -2 0.9 -2 2 v 16 l 7 -3 l 7 3 V 7 c 0 -1.1 " +
                "-0.9 -2 -2 -2 z",
        )
    }

    /** `Icons.Filled.Check` */
    val Check: ImageVector = materialIcon("Check") {
        listOf("M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z")
    }

    /** `Icons.Filled.CheckCircle` */
    val CheckCircle: ImageVector = materialIcon("CheckCircle") {
        listOf(
            "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 " +
                "1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z",
        )
    }

    /** `Icons.Filled.Close` */
    val Close: ImageVector = materialIcon("Close") {
        listOf(
            "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 " +
                "17.59 19 19 17.59 13.41 12z",
        )
    }

    /** `Icons.Filled.Folder` */
    val Folder: ImageVector = materialIcon("Folder") {
        listOf("M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z")
    }

    /** `Icons.AutoMirrored.Filled.FormatListNumbered` */
    val FormatListNumbered: ImageVector = materialIcon("FormatListNumbered", autoMirror = true) {
        listOf(
            "M2 17h2v.5H3v1h1v.5H2v1h3v-4H2v1zm1-9h1V4H2v1h1v3zm-1 3h1.8L2 13.1v.9h3v-1H3.2L5 " +
                "10.9V10H2v1zm5-6v2h14V5H7zm0 14h14v-2H7v2zm0-6h14v-2H7v2z",
        )
    }

    /** `Icons.Filled.GridView` */
    val GridView: ImageVector = materialIcon("GridView") {
        listOf("M3 3v8h8V3H3zm6 6H5V5h4v4zm-6 4v8h8v-8H3zm6 6H5v-4h4v4zm4-16v8h8V3h-8zm6 6h-4V5h4v4zm-6 4v8h8v-8h-8zm6 6h-4v-4h4v4z")
    }

    /** `Icons.Filled.Headphones` */
    val Headphones: ImageVector = materialIcon("Headphones") {
        listOf(
            "M12 1c-4.97 0-9 4.03-9 9v7c0 1.66 1.34 3 3 3h3v-8H5v-2c0-3.87 3.13-7 7-7s7 3.13 " +
                "7 7v2h-4v8h3c1.66 0 3-1.34 3-3v-7c0-4.97-4.03-9-9-9z",
        )
    }

    /** `Icons.Filled.Home` */
    val Home: ImageVector = materialIcon("Home") {
        listOf("M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z")
    }

    /** `Icons.Filled.Menu` */
    val Menu: ImageVector = materialIcon("Menu") {
        listOf("M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z")
    }

    /** `Icons.AutoMirrored.Outlined.PlaylistAdd` — artwork for the [ToRead] concept. */
    val PlaylistAdd: ImageVector = materialIcon("PlaylistAdd", autoMirror = true) {
        listOf(
            "M 14 10 H 3 v 2 h 11 V 10 z M 14 6 H 3 v 2 h 11 V 6 z M 18 14 v -4 h -2 v 4 h -4 " +
                "v 2 h 4 v 4 h 2 v -4 h 4 v -2 H 18 z M 3 16 h 7 v -2 H 3 V 16 z",
        )
    }

    /** `Icons.AutoMirrored.Filled.PlaylistAddCheck` — artwork for the [ToReadFilled] concept. */
    val PlaylistAddCheck: ImageVector = materialIcon("PlaylistAddCheck", autoMirror = true) {
        listOf(
            "M 3 10 h 11 v 2 h -11 z M 3 6 h 11 v 2 h -11 z M 3 14 h 7 v 2 h -7 z M 20.59 11.93 " +
                "l -4.25 4.24 l -2.12 -2.12 l -1.41 1.41 l 3.53 3.54 l 5.66 -5.66 z",
        )
    }

    /** `Icons.AutoMirrored.Filled.QueueMusic` */
    val QueueMusic: ImageVector = materialIcon("QueueMusic", autoMirror = true) {
        listOf("M15 6H3v2h12V6zm0 4H3v2h12v-2zM3 16h8v-2H3v2zM17 6v8.18c-.31-.11-.65-.18-1-.18-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3V8h3V6h-5z")
    }

    /** `Icons.Filled.Schedule` */
    val Schedule: ImageVector = materialIcon("Schedule") {
        listOf(
            "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 " +
                "20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8z",
            "M12.5 7H11v6l5.25 3.15.75-1.23-4.5-2.67z",
        )
    }

    /** `Icons.Filled.Search` */
    val Search: ImageVector = materialIcon("Search") {
        listOf(
            "M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 " +
                "9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 " +
                "0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z",
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
