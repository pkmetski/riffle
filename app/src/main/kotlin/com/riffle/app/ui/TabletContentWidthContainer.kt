package com.riffle.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Width-cap container for single-column list/form screens on the Tablet Layout
 * (ADR 0019: Material 3 Expanded size class, ≥ 840dp). On Expanded the content
 * is capped at [MaxContentWidth] and centred horizontally in the available
 * pane. On Compact and Medium the container is a transparent pass-through that
 * forwards [modifier] to a single [Box] wrapper — visually identical to having
 * the modifier applied directly to the caller's root layout.
 *
 * Used by Settings, Downloads, AddServer, and Library Visibility Preferences
 * (SelectLibrariesScreen). Not used by the in-reader TOC and Formatting
 * Preferences sheets, which already render as constrained sheet surfaces.
 */
@Composable
fun TabletContentWidthContainer(
    windowSizeClass: WindowSizeClass,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded) {
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
