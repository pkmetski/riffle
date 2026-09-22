package com.riffle.feature.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.riffle.feature.library.CoverGridLayout

/**
 * Width of the window this composition is drawn into, in dp.
 *
 * `LocalWindowInfo.current.containerSize` is the multiplatform reading of the window (not the
 * physical screen): on Android it tracks split-screen and freeform windows exactly as
 * `LocalConfiguration.screenWidthDp` does, and on iOS it tracks Slide Over and Split View. It is
 * the only form-factor signal available in `commonMain` — `androidx.compose.material3.windowsizeclass`
 * is an Android-only artifact.
 */
@Composable
@ReadOnlyComposable
fun windowWidthDp(): Dp {
    val widthPx = LocalWindowInfo.current.containerSize.width
    return with(LocalDensity.current) { widthPx.toDp() }
}

/**
 * True when the window is at least [CoverGridLayout.EXPANDED_WIDTH_BREAKPOINT_DP] wide — the
 * Material 3 **Expanded** width size class, which ADR 0019 defines as the Tablet Layout.
 *
 * This is the primitive iOS did not have. `grep isExpandedWidth shared/src/commonMain` returned
 * four literal `isExpandedWidth = false` call sites in `SourceOnboardingHost`, so a landscape iPad
 * and a 12.9" iPad Pro both rendered the phone layout and stretched single-column forms
 * edge-to-edge. Android computes the same predicate from `WindowSizeClass`
 * (`app/.../TabletContentWidthContainer.kt`, `SourceNavGraph.kt`); both now index on the same
 * 840dp breakpoint constant.
 *
 * Note this is *width* only, deliberately. ADR 0019 (as amended for phone landscape) treats a
 * device as the Tablet form factor on the Expanded width class; height plays no part.
 */
@Composable
@ReadOnlyComposable
fun isExpandedWidth(): Boolean =
    windowWidthDp().value >= CoverGridLayout.EXPANDED_WIDTH_BREAKPOINT_DP

/**
 * Pure predicate behind [isExpandedWidth], so the breakpoint can be pinned by a unit test without
 * standing up a composition.
 */
fun isExpandedWidth(widthDp: Float): Boolean = widthDp >= CoverGridLayout.EXPANDED_WIDTH_BREAKPOINT_DP

/** Convenience overload of [isExpandedWidth] for callers that already hold a [Dp]. */
fun isExpandedWidth(width: Dp): Boolean = isExpandedWidth(width.value)

/** The width cap applied to single-column list/form content on the Tablet Layout. */
val MaxContentWidth: Dp = 600.dp
