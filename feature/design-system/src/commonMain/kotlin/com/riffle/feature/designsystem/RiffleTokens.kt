package com.riffle.feature.designsystem

import androidx.compose.ui.graphics.Color

/**
 * The few colours that are deliberately **not** `MaterialTheme.colorScheme` values.
 *
 * Everything that sits on a surface must come from the colour scheme, so it follows the App Theme
 * picker and the system dark flag. The exceptions are the scrims painted *on top of cover
 * artwork*: a cover is an arbitrary photograph, so a badge drawn over it needs a fixed dark wash
 * and fixed white ink in both themes, or it becomes unreadable over a light jacket at night.
 * Naming them here is what makes that a decision rather than the 72 loose `Color(0x…)` literals
 * `shared` had accumulated — a reviewer can see at a glance that a literal colour in a component
 * is one of these four and not a hardcoded surface.
 *
 * Adding a token: if the colour belongs on a surface, it does **not** go here — use
 * `MaterialTheme.colorScheme`. If it belongs over imagery, name it here with the reason.
 */
object RiffleTokens {

    /** Circular scrim behind a glyph badge on a cover (the readaloud headphones). */
    val CoverScrim: Color = Color.Black.copy(alpha = 0.55f)

    /** Pill scrim behind a text badge on a cover (the series-position chip). */
    val CoverPillScrim: Color = Color.Black.copy(alpha = 0.70f)

    /** Slightly lighter pill scrim, for the lower-priority source chip. */
    val CoverPillScrimStrong: Color = Color.Black.copy(alpha = 0.65f)

    /** Ink for anything drawn on one of the cover scrims above. */
    val OnCoverScrim: Color = Color.White
}
