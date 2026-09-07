package com.riffle.app.ui

import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.riffle.feature.source.ui.TabletContentWidthContainer as SharedTabletContentWidthContainer

/**
 * Width-cap container for single-column list/form screens on the Tablet Layout
 * (ADR 0019: Material 3 Expanded size class, ≥ 840dp). On Expanded the content
 * is capped and centred horizontally in the available pane. On Compact and
 * Medium the container is a transparent pass-through that forwards [modifier]
 * to a single wrapper — visually identical to having the modifier applied
 * directly to the caller's root layout.
 *
 * Used by Settings, Downloads, AddServer, and Library Visibility Preferences
 * (SelectLibrariesScreen). Not used by the in-reader TOC and Formatting
 * Preferences sheets, which already render as constrained sheet surfaces.
 *
 * This is the Android-only `WindowSizeClass` overload; the layout itself lives in
 * `:feature:source-ui` so the shared source-onboarding screens (which also render on iOS, where
 * `androidx.compose.material3.windowsizeclass` does not exist) can use it. Keeping this overload
 * means every existing Android call site — and both instrumentation tests — stay unchanged.
 */
@Composable
fun TabletContentWidthContainer(
    windowSizeClass: WindowSizeClass,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    SharedTabletContentWidthContainer(
        isExpandedWidth = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded,
        modifier = modifier,
        content = content,
    )
}
