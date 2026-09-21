package com.riffle.feature.player.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Every Material glyph the audiobook player chrome draws.
 *
 * `androidx.compose.material.icons` is an Android-only artifact and
 * `org.jetbrains.compose.material:material-icons-core` stopped being published after 1.7.3, so a
 * module that both `:app` and `:shared` render cannot depend on either — the same constraint that
 * produced [com.riffle.feature.source.ui.SourceUiIcons] and
 * `com.riffle.feature.reader.ui.ReaderGlyphs`.
 *
 * The path data below is copied **verbatim** from `material-icons-core`/`material-icons-extended`
 * 1.7.8, including the builder DSL calls, so Android renders exactly the artwork it rendered when
 * `PlayerSurface` still imported `Icons.Filled.*`, and iOS renders the same. Do not hand-edit the
 * coordinates; re-copy from the androidx sources if a glyph needs to change.
 *
 * These are private to the player surface. Do not grow this into a general icon library.
 */
internal object PlayerGlyphs {

    /** `Icons.Filled.PlayArrow` */
    val PlayArrow: ImageVector = materialIcon("PlayArrow") {
        moveTo(8.0f, 5.0f)
        verticalLineToRelative(14.0f)
        lineToRelative(11.0f, -7.0f)
        close()
    }

    /** `Icons.Filled.Pause` */
    val Pause: ImageVector = materialIcon("Pause") {
        moveTo(6.0f, 19.0f)
        horizontalLineToRelative(4.0f)
        lineTo(10.0f, 5.0f)
        lineTo(6.0f, 5.0f)
        verticalLineToRelative(14.0f)
        close()
        moveTo(14.0f, 5.0f)
        verticalLineToRelative(14.0f)
        horizontalLineToRelative(4.0f)
        lineTo(18.0f, 5.0f)
        horizontalLineToRelative(-4.0f)
        close()
    }

    /** `Icons.Filled.SkipNext` */
    val SkipNext: ImageVector = materialIcon("SkipNext") {
        moveTo(6.0f, 18.0f)
        lineToRelative(8.5f, -6.0f)
        lineTo(6.0f, 6.0f)
        verticalLineToRelative(12.0f)
        close()
        moveTo(16.0f, 6.0f)
        verticalLineToRelative(12.0f)
        horizontalLineToRelative(2.0f)
        verticalLineTo(6.0f)
        horizontalLineToRelative(-2.0f)
        close()
    }

    /** `Icons.Filled.SkipPrevious` */
    val SkipPrevious: ImageVector = materialIcon("SkipPrevious") {
        moveTo(6.0f, 6.0f)
        horizontalLineToRelative(2.0f)
        verticalLineToRelative(12.0f)
        lineTo(6.0f, 18.0f)
        close()
        moveTo(9.5f, 12.0f)
        lineToRelative(8.5f, 6.0f)
        lineTo(18.0f, 6.0f)
        close()
    }

    /** `Icons.Filled.Replay` — the ⟲ loop the skip interval number is drawn inside. */
    val Replay: ImageVector = materialIcon("Replay") {
        moveTo(12.0f, 5.0f)
        verticalLineTo(1.0f)
        lineTo(7.0f, 6.0f)
        lineToRelative(5.0f, 5.0f)
        verticalLineTo(7.0f)
        curveToRelative(3.31f, 0.0f, 6.0f, 2.69f, 6.0f, 6.0f)
        reflectiveCurveToRelative(-2.69f, 6.0f, -6.0f, 6.0f)
        reflectiveCurveToRelative(-6.0f, -2.69f, -6.0f, -6.0f)
        horizontalLineTo(4.0f)
        curveToRelative(0.0f, 4.42f, 3.58f, 8.0f, 8.0f, 8.0f)
        reflectiveCurveToRelative(8.0f, -3.58f, 8.0f, -8.0f)
        reflectiveCurveToRelative(-3.58f, -8.0f, -8.0f, -8.0f)
        close()
    }

    /** `Icons.Filled.Speed` */
    val Speed: ImageVector = materialIcon("Speed") {
        moveTo(20.38f, 8.57f)
        lineToRelative(-1.23f, 1.85f)
        arcToRelative(8.0f, 8.0f, 0.0f, false, true, -0.22f, 7.58f)
        lineTo(5.07f, 18.0f)
        arcTo(8.0f, 8.0f, 0.0f, false, true, 15.58f, 6.85f)
        lineToRelative(1.85f, -1.23f)
        arcTo(10.0f, 10.0f, 0.0f, false, false, 3.35f, 19.0f)
        arcToRelative(2.0f, 2.0f, 0.0f, false, false, 1.72f, 1.0f)
        horizontalLineToRelative(13.85f)
        arcToRelative(2.0f, 2.0f, 0.0f, false, false, 1.74f, -1.0f)
        arcToRelative(10.0f, 10.0f, 0.0f, false, false, -0.27f, -10.44f)
        close()
        moveTo(10.59f, 15.41f)
        arcToRelative(2.0f, 2.0f, 0.0f, false, false, 2.83f, 0.0f)
        lineToRelative(5.66f, -8.49f)
        lineToRelative(-8.49f, 5.66f)
        arcToRelative(2.0f, 2.0f, 0.0f, false, false, 0.0f, 2.83f)
        close()
    }

    /** `Icons.Filled.Bedtime` — the sleep-timer crescent. */
    val Bedtime: ImageVector = materialIcon("Bedtime") {
        moveTo(12.34f, 2.02f)
        curveTo(6.59f, 1.82f, 2.0f, 6.42f, 2.0f, 12.0f)
        curveToRelative(0.0f, 5.52f, 4.48f, 10.0f, 10.0f, 10.0f)
        curveToRelative(3.71f, 0.0f, 6.93f, -2.02f, 8.66f, -5.02f)
        curveTo(13.15f, 16.73f, 8.57f, 8.55f, 12.34f, 2.02f)
        close()
    }

    /** `Icons.Filled.GraphicEq` — the now-playing marker on the current chapter row. */
    val GraphicEq: ImageVector = materialIcon("GraphicEq") {
        moveTo(7.0f, 18.0f)
        horizontalLineToRelative(2.0f)
        lineTo(9.0f, 6.0f)
        lineTo(7.0f, 6.0f)
        verticalLineToRelative(12.0f)
        close()
        moveTo(11.0f, 22.0f)
        horizontalLineToRelative(2.0f)
        lineTo(13.0f, 2.0f)
        horizontalLineToRelative(-2.0f)
        verticalLineToRelative(20.0f)
        close()
        moveTo(3.0f, 14.0f)
        horizontalLineToRelative(2.0f)
        verticalLineToRelative(-4.0f)
        lineTo(3.0f, 10.0f)
        verticalLineToRelative(4.0f)
        close()
        moveTo(15.0f, 18.0f)
        horizontalLineToRelative(2.0f)
        lineTo(17.0f, 6.0f)
        horizontalLineToRelative(-2.0f)
        verticalLineToRelative(12.0f)
        close()
        moveTo(19.0f, 10.0f)
        verticalLineToRelative(4.0f)
        horizontalLineToRelative(2.0f)
        verticalLineToRelative(-4.0f)
        horizontalLineToRelative(-2.0f)
        close()
    }

    /** `Icons.Filled.Bookmark` */
    val Bookmark: ImageVector = materialIcon("Bookmark") {
        moveTo(17.0f, 3.0f)
        horizontalLineTo(7.0f)
        curveToRelative(-1.1f, 0.0f, -1.99f, 0.9f, -1.99f, 2.0f)
        lineTo(5.0f, 21.0f)
        lineToRelative(7.0f, -3.0f)
        lineToRelative(7.0f, 3.0f)
        verticalLineTo(5.0f)
        curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
        close()
    }

    /** `Icons.Filled.MoreVert` */
    val MoreVert: ImageVector = materialIcon("MoreVert") {
        moveTo(12.0f, 8.0f)
        curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
        reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
        reflectiveCurveToRelative(-2.0f, 0.9f, -2.0f, 2.0f)
        reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
        close()
        moveTo(12.0f, 10.0f)
        curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
        reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
        reflectiveCurveToRelative(2.0f, -0.9f, 2.0f, -2.0f)
        reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
        close()
        moveTo(12.0f, 16.0f)
        curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
        reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
        reflectiveCurveToRelative(2.0f, -0.9f, 2.0f, -2.0f)
        reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
        close()
    }

    /** `Icons.Filled.Edit` */
    val Edit: ImageVector = materialIcon("Edit") {
        moveTo(3.0f, 17.25f)
        verticalLineTo(21.0f)
        horizontalLineToRelative(3.75f)
        lineTo(17.81f, 9.94f)
        lineToRelative(-3.75f, -3.75f)
        lineTo(3.0f, 17.25f)
        close()
        moveTo(20.71f, 7.04f)
        curveToRelative(0.39f, -0.39f, 0.39f, -1.02f, 0.0f, -1.41f)
        lineToRelative(-2.34f, -2.34f)
        curveToRelative(-0.39f, -0.39f, -1.02f, -0.39f, -1.41f, 0.0f)
        lineToRelative(-1.83f, 1.83f)
        lineToRelative(3.75f, 3.75f)
        lineToRelative(1.83f, -1.83f)
        close()
    }

    /** `Icons.Filled.Delete` */
    val Delete: ImageVector = materialIcon("Delete") {
        moveTo(6.0f, 19.0f)
        curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
        horizontalLineToRelative(8.0f)
        curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
        verticalLineTo(7.0f)
        horizontalLineTo(6.0f)
        verticalLineToRelative(12.0f)
        close()
        moveTo(19.0f, 4.0f)
        horizontalLineToRelative(-3.5f)
        lineToRelative(-1.0f, -1.0f)
        horizontalLineToRelative(-5.0f)
        lineToRelative(-1.0f, 1.0f)
        horizontalLineTo(5.0f)
        verticalLineToRelative(2.0f)
        horizontalLineToRelative(14.0f)
        verticalLineTo(4.0f)
        close()
    }

    /** `Icons.Filled.Close` */
    val Close: ImageVector = materialIcon("Close") {
        moveTo(19.0f, 6.41f)
        lineTo(17.59f, 5.0f)
        lineTo(12.0f, 10.59f)
        lineTo(6.41f, 5.0f)
        lineTo(5.0f, 6.41f)
        lineTo(10.59f, 12.0f)
        lineTo(5.0f, 17.59f)
        lineTo(6.41f, 19.0f)
        lineTo(12.0f, 13.41f)
        lineTo(17.59f, 19.0f)
        lineTo(19.0f, 17.59f)
        lineTo(13.41f, 12.0f)
        close()
    }

    /** `Icons.AutoMirrored.Filled.ArrowBack` */
    val ArrowBack: ImageVector = materialIcon("ArrowBack", autoMirror = true) {
        moveTo(20.0f, 11.0f)
        horizontalLineTo(7.83f)
        lineToRelative(5.59f, -5.59f)
        lineTo(12.0f, 4.0f)
        lineToRelative(-8.0f, 8.0f)
        lineToRelative(8.0f, 8.0f)
        lineToRelative(1.41f, -1.41f)
        lineTo(7.83f, 13.0f)
        horizontalLineTo(20.0f)
        verticalLineToRelative(-2.0f)
        close()
    }

    /**
     * `Icons.AutoMirrored.Filled.MenuBook`. The androidx original is four separate `materialPath`
     * blocks (the book outline plus three page lines); they are concatenated here into one path,
     * which renders identically because every sub-path is closed and none overlap.
     */
    val MenuBook: ImageVector = materialIcon("MenuBook", autoMirror = true) {
        moveTo(21.0f, 5.0f)
        curveToRelative(-1.11f, -0.35f, -2.33f, -0.5f, -3.5f, -0.5f)
        curveToRelative(-1.95f, 0.0f, -4.05f, 0.4f, -5.5f, 1.5f)
        curveToRelative(-1.45f, -1.1f, -3.55f, -1.5f, -5.5f, -1.5f)
        reflectiveCurveTo(2.45f, 4.9f, 1.0f, 6.0f)
        verticalLineToRelative(14.65f)
        curveToRelative(0.0f, 0.25f, 0.25f, 0.5f, 0.5f, 0.5f)
        curveToRelative(0.1f, 0.0f, 0.15f, -0.05f, 0.25f, -0.05f)
        curveTo(3.1f, 20.45f, 5.05f, 20.0f, 6.5f, 20.0f)
        curveToRelative(1.95f, 0.0f, 4.05f, 0.4f, 5.5f, 1.5f)
        curveToRelative(1.35f, -0.85f, 3.8f, -1.5f, 5.5f, -1.5f)
        curveToRelative(1.65f, 0.0f, 3.35f, 0.3f, 4.75f, 1.05f)
        curveToRelative(0.1f, 0.05f, 0.15f, 0.05f, 0.25f, 0.05f)
        curveToRelative(0.25f, 0.0f, 0.5f, -0.25f, 0.5f, -0.5f)
        verticalLineTo(6.0f)
        curveTo(22.4f, 5.55f, 21.75f, 5.25f, 21.0f, 5.0f)
        close()
        moveTo(21.0f, 18.5f)
        curveToRelative(-1.1f, -0.35f, -2.3f, -0.5f, -3.5f, -0.5f)
        curveToRelative(-1.7f, 0.0f, -4.15f, 0.65f, -5.5f, 1.5f)
        verticalLineTo(8.0f)
        curveToRelative(1.35f, -0.85f, 3.8f, -1.5f, 5.5f, -1.5f)
        curveToRelative(1.2f, 0.0f, 2.4f, 0.15f, 3.5f, 0.5f)
        verticalLineTo(18.5f)
        close()
        moveTo(17.5f, 10.5f)
        curveToRelative(0.88f, 0.0f, 1.73f, 0.09f, 2.5f, 0.26f)
        verticalLineTo(9.24f)
        curveTo(19.21f, 9.09f, 18.36f, 9.0f, 17.5f, 9.0f)
        curveToRelative(-1.7f, 0.0f, -3.24f, 0.29f, -4.5f, 0.83f)
        verticalLineToRelative(1.66f)
        curveTo(14.13f, 10.85f, 15.7f, 10.5f, 17.5f, 10.5f)
        close()
        moveTo(13.0f, 12.49f)
        verticalLineToRelative(1.66f)
        curveToRelative(1.13f, -0.64f, 2.7f, -0.99f, 4.5f, -0.99f)
        curveToRelative(0.88f, 0.0f, 1.73f, 0.09f, 2.5f, 0.26f)
        verticalLineTo(11.9f)
        curveToRelative(-0.79f, -0.15f, -1.64f, -0.24f, -2.5f, -0.24f)
        curveTo(15.8f, 11.66f, 14.26f, 11.96f, 13.0f, 12.49f)
        close()
        moveTo(17.5f, 14.33f)
        curveToRelative(-1.7f, 0.0f, -3.24f, 0.29f, -4.5f, 0.83f)
        verticalLineToRelative(1.66f)
        curveToRelative(1.13f, -0.64f, 2.7f, -0.99f, 4.5f, -0.99f)
        curveToRelative(0.88f, 0.0f, 1.73f, 0.09f, 2.5f, 0.26f)
        verticalLineToRelative(-1.52f)
        curveTo(19.21f, 14.41f, 18.36f, 14.33f, 17.5f, 14.33f)
        close()
    }
}

/**
 * The same 24dp/24-unit box and the same path defaults `androidx.compose.material.icons`
 * `materialIcon` + `materialPath` use, so a glyph copied from there renders byte-identically.
 * `Color.Black` is what `Icon`'s tint replaces.
 */
private fun materialIcon(
    name: String,
    autoMirror: Boolean = false,
    pathBuilder: PathBuilder.() -> Unit,
): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = autoMirror,
    ).path(
        fill = SolidColor(Color.Black),
        fillAlpha = 1f,
        stroke = null,
        strokeAlpha = 1f,
        strokeLineWidth = 1f,
        strokeLineCap = StrokeCap.Butt,
        strokeLineJoin = StrokeJoin.Bevel,
        strokeLineMiter = 1f,
        pathBuilder = pathBuilder,
    ).build()
