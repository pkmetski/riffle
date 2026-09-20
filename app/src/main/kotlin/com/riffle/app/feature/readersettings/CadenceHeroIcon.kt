package com.riffle.app.feature.readersettings

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.riffle.feature.reader.ui.CadenceGlyph

/**
 * Hero-size rendition of the Cadence glyph, for the Settings drill-in About blurb. Same shape as
 * the reader top-bar toggle; larger. Caller places it above the About text.
 */
@Composable
fun CadenceHeroIcon(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    CadenceGlyph(color = LocalContentColor.current, size = size, modifier = modifier)
}
