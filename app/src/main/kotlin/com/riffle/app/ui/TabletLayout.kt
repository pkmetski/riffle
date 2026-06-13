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

/**
 * A wide-but-short window — a large phone in landscape: Expanded width (≥ 840dp) but Compact height
 * (< 480dp). It is NOT a tablet (so it keeps the phone chrome — modal drawer), but it is wide enough
 * to host a two-column *content* layout (e.g. the item-detail cover beside the text) instead of one
 * tall scrolling column. A real tablet is non-Compact height, so it never matches this.
 */
fun WindowSizeClass.isPhoneLandscape(): Boolean =
    widthSizeClass == WindowWidthSizeClass.Expanded &&
        heightSizeClass == WindowHeightSizeClass.Compact
