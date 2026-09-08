package com.riffle.feature.source.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Width-cap container for single-column list/form screens on the Tablet Layout
 * (ADR 0019: Material 3 Expanded size class, ≥ 840dp). When [isExpandedWidth] the content is
 * capped at [MaxContentWidth] and centred horizontally in the available pane; otherwise the
 * container is a transparent pass-through that forwards [modifier] to a single [Box] wrapper —
 * visually identical to having the modifier applied directly to the caller's root layout.
 *
 * Takes a plain boolean rather than `WindowSizeClass` because
 * `androidx.compose.material3.windowsizeclass` is an Android-only artifact and these screens
 * render on iOS too. `com.riffle.app.ui.TabletContentWidthContainer` is the Android-side
 * `WindowSizeClass` overload and delegates here.
 */
@Composable
fun TabletContentWidthContainer(
    isExpandedWidth: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (isExpandedWidth) {
        Box(modifier = modifier, contentAlignment = Alignment.TopCenter) {
            Box(modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxSize()) {
                content()
            }
        }
    } else {
        // Intentional wrapper Box even on the no-op path so callers can pass a
        // single modifier (typically `fillMaxSize().padding(scaffoldPadding)`)
        // and get the same effect as applying it directly to their root layout.
        Box(modifier = modifier) { content() }
    }
}

private val MaxContentWidth = 600.dp
