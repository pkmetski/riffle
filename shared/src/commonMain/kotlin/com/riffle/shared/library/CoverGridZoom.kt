package com.riffle.shared.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.riffle.feature.designsystem.LocalCoverGridScale
import com.riffle.feature.designsystem.pinchCoverZoom

/**
 * A [Box] that publishes [scale] to every cover grid inside it and turns a two-finger pinch
 * anywhere within it into a density change, so all of a library's grids reflow together.
 */
@Composable
internal fun CoverGridZoomBox(
    scale: Float,
    onScaleChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    CompositionLocalProvider(LocalCoverGridScale provides scale) {
        Box(modifier = modifier.pinchCoverZoom(scale, onScaleChange), content = content)
    }
}
