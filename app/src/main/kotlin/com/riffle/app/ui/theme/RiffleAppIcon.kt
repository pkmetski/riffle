package com.riffle.app.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.riffle.app.R

/**
 * Renders the Riffle app icon (the round launcher icon) at the requested [size].
 *
 * [painterResource] cannot load mipmap adaptive icons on API 26+ (they are XML, not rasters).
 * [ContextCompat.getDrawable] correctly resolves the adaptive icon and [toBitmap] renders both
 * background and foreground layers onto a canvas, producing a real bitmap at any size.
 */
@Composable
fun RiffleAppIcon(
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    val context = LocalContext.current
    val bitmap = remember(size) {
        val px = (size.value * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        ContextCompat.getDrawable(context, R.mipmap.ic_launcher_round)?.toBitmap(px, px)
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
        )
    }
}
