package com.riffle.shared.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.riffle.feature.designsystem.LocalCoverGridScale
import com.riffle.feature.designsystem.pinchCoverZoom
import kotlinx.coroutines.flow.StateFlow

/**
 * A [Box] that publishes the active cover density to every cover grid inside it and turns a
 * two-finger pinch anywhere within it into a density change, so all of a library's grids reflow
 * together.
 *
 * When [isHomeTab] is true the provider uses [homeScaleFlow] so the Home shelf zoom is
 * independent of the all-books zoom. Accepts [StateFlow]s directly so call sites need not
 * collect them — this composable subscribes to whichever flow is active for the current tab.
 */
@Composable
internal fun CoverGridZoomBox(
    browseScaleFlow: StateFlow<Float>,
    onPersistScaleChange: (Float) -> Unit,
    homeScaleFlow: StateFlow<Float> = browseScaleFlow,
    onPersistHomeScaleChange: (Float) -> Unit = onPersistScaleChange,
    isHomeTab: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val activeFlow = if (isHomeTab) homeScaleFlow else browseScaleFlow
    val onActivePersist = if (isHomeTab) onPersistHomeScaleChange else onPersistScaleChange
    val persistedScale by activeFlow.collectAsState()

    var liveScale by remember(isHomeTab) { mutableFloatStateOf(persistedScale) }
    LaunchedEffect(persistedScale) { liveScale = persistedScale }
    val onScaleChange: (Float) -> Unit = { liveScale = it; onActivePersist(it) }

    CompositionLocalProvider(LocalCoverGridScale provides liveScale) {
        Box(modifier = modifier.pinchCoverZoom(liveScale, onScaleChange), content = content)
    }
}
