package com.riffle.app.ui

import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass

/**
 * Whether this window should render the Tablet Layout (ADR 0019).
 *
 * The width must be Expanded (≥ 840dp), AND the height must not be Compact. The original ADR keyed
 * on Expanded width alone, on the assumption that a phone in landscape stays in the Medium width
 * bucket — but a *large* phone in landscape crosses 840dp, so width alone hands it the permanent
 * drawer and two-pane layouts next to a postcard-height (Compact) area. The height guard restores the
 * ADR's stated intent: a landscape phone (Compact height) is treated as a phone, a real tablet
 * (taller in both orientations) as a tablet.
 */
fun WindowSizeClass.isTabletLayout(): Boolean =
    widthSizeClass == WindowWidthSizeClass.Expanded &&
        heightSizeClass != WindowHeightSizeClass.Compact
