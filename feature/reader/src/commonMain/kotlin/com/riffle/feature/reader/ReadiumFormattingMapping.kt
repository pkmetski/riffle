package com.riffle.feature.reader

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.effectiveOrientation

/**
 * The three page themes Readium ships. Both engines take the same three names — Readium-Kotlin as
 * `Theme.LIGHT/DARK/SEPIA`, Readium-Swift as the `"light"/"dark"/"sepia"` strings the iOS bridge
 * adapts — so the *choice* is shared here and only the type adaptation is platform code.
 */
enum class ReadiumThemeName(val value: String) {
    LIGHT("light"),
    DARK("dark"),
    SEPIA("sepia"),
}

/**
 * Everything a Readium EPUB navigator needs to paint text the way the user's [FormattingPreferences]
 * ask for, expressed in platform-neutral values.
 *
 * Android derived this inside `FormattingPreferencesMapper.toEpubPreferences`; iOS re-derived a
 * three-branch subset in `IosEpubReaderScreen` and had drifted on all four fields — `DarkDim`
 * collapsed to plain dark (losing the muted body colour that is the entire difference between the
 * two themes), `Auto` was hardcoded to light instead of being resolved, `publisherStyles` was left
 * at Readium's `true` so line-height and text-align were silently ignored, and the single-column
 * pin was missing.
 *
 * @property theme the Readium page theme.
 * @property textColorArgb body-text override, or null to let the theme decide. Only `DarkDim`
 *   sets it, to [DARK_DIM_TEXT_ARGB].
 * @property publisherStyles always false — Readium ignores `lineHeight` and `textAlign` while the
 *   publisher's own stylesheet is in charge, so leaving this on makes two user preferences inert.
 * @property columnCount the `--RS__colCount` pin, or null when column count does not apply
 *   (scroll mode, fixed layout). One column is pinned explicitly for reflowable paginated reading
 *   because Readium 3.3.0 changed its default to two, and Readium's decoration renderer
 *   mispositions highlights in a multi-column layout.
 */
data class ReadiumTextStyling(
    val theme: ReadiumThemeName,
    val textColorArgb: Long?,
    val publisherStyles: Boolean,
    val columnCount: Int?,
)

/**
 * Maps a [FormattingPreferences] onto [ReadiumTextStyling].
 *
 * [FormattingPreferences.theme] must already be concrete: resolve [ReaderTheme.Auto] upstream via
 * `withResolvedTheme` or `AppearanceCoordinator.resolved`. If Auto does reach here it falls back
 * to light rather than crashing the reader, matching the palette's defensive branch.
 */
fun FormattingPreferences.toReadiumTextStyling(
    isLandscape: Boolean = false,
    isFixedLayout: Boolean = false,
    isDoublePage: Boolean = false,
): ReadiumTextStyling {
    val effectiveOrientation = effectiveOrientation(isLandscape)
    return ReadiumTextStyling(
        theme = theme.toReadiumThemeName(),
        textColorArgb = if (theme == ReaderTheme.DarkDim) DARK_DIM_TEXT_ARGB else null,
        publisherStyles = false,
        columnCount = readiumColumnCount(effectiveOrientation, isFixedLayout, isDoublePage),
    )
}

/** Light/Sepia map straight across; both dark variants use Readium's DARK page theme. */
fun ReaderTheme.toReadiumThemeName(): ReadiumThemeName = when (this) {
    ReaderTheme.Light -> ReadiumThemeName.LIGHT
    ReaderTheme.Dark, ReaderTheme.DarkDim -> ReadiumThemeName.DARK
    ReaderTheme.Sepia -> ReadiumThemeName.SEPIA
    // Defensive: Auto must be resolved upstream. Fall back to light rather than crash.
    ReaderTheme.Auto -> ReadiumThemeName.LIGHT
}

/** See [ReadiumTextStyling.columnCount]. */
fun readiumColumnCount(
    effectiveOrientation: ReaderOrientation,
    isFixedLayout: Boolean,
    isDoublePage: Boolean,
): Int? = when {
    isDoublePage -> 2
    !isFixedLayout && effectiveOrientation == ReaderOrientation.Horizontal -> 1
    else -> null
}

/**
 * The CSS font-family name Readium should apply, or null to leave `--USER__fontFamily` unset.
 *
 * Null-gating matters: the typography-override stylesheet is gated on the variable's presence, so
 * an unset variable means the publisher's own typography survives on an uncustomised book. Every
 * other choice — including the generic "Serif" — overrides the publisher font.
 */
fun ReaderFontFamily.readiumFontFamilyName(): String? = when (this) {
    ReaderFontFamily.Original -> null
    ReaderFontFamily.Serif -> "serif"
    ReaderFontFamily.SansSerif -> "sans-serif"
    ReaderFontFamily.Monospace -> "monospace"
    ReaderFontFamily.Literata -> "Literata"
    ReaderFontFamily.Merriweather -> "Merriweather"
    ReaderFontFamily.OpenDyslexic -> "OpenDyslexic"
}
