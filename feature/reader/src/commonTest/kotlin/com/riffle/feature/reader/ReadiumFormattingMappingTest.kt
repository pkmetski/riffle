package com.riffle.feature.reader

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.effectiveOrientation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Pins the Readium text-styling derivation both platforms feed their navigator.
 *
 * iOS used to map this itself, in three branches, and had drifted on all four fields. Each test
 * below names the iOS behaviour it replaces; reverting the mapper turns them red.
 */
class ReadiumFormattingMappingTest {

    private val defaults = FormattingPreferences()

    @Test fun darkDimKeepsTheDarkPageButMutesTheBodyText() {
        // Old iOS: theme "dark" and no text colour, so DarkDim and Dark rendered identically —
        // the muted body colour is the only thing that distinguishes them.
        val styling = defaults.copy(theme = ReaderTheme.DarkDim).toReadiumTextStyling()
        assertEquals(ReadiumThemeName.DARK, styling.theme)
        assertEquals(DARK_DIM_TEXT_ARGB, styling.textColorArgb)
    }

    @Test fun plainDarkLeavesTheBodyColourToTheTheme() {
        val styling = defaults.copy(theme = ReaderTheme.Dark).toReadiumTextStyling()
        assertEquals(ReadiumThemeName.DARK, styling.theme)
        assertNull(styling.textColorArgb)
    }

    @Test fun lightAndSepiaMapStraightAcross() {
        assertEquals(ReadiumThemeName.LIGHT, ReaderTheme.Light.toReadiumThemeName())
        assertEquals(ReadiumThemeName.SEPIA, ReaderTheme.Sepia.toReadiumThemeName())
    }

    @Test fun publisherStylesAreAlwaysOff() {
        // Readium ignores lineHeight and textAlign while the publisher stylesheet is in charge,
        // so leaving this on makes two user preferences inert. Old iOS never passed it at all.
        assertFalse(defaults.toReadiumTextStyling().publisherStyles)
    }

    @Test fun reflowablePaginatedPinsOneColumn() {
        // Readium 3.3.0 changed its default to two columns on a phone-width viewport, and its
        // decoration renderer mispositions highlights in a multi-column layout.
        val styling = defaults.copy(orientation = ReaderOrientation.Horizontal).toReadiumTextStyling()
        assertEquals(1, styling.columnCount)
    }

    @Test fun landscapeDoublePageAsksForTwoColumns() {
        val styling = defaults.copy(orientation = ReaderOrientation.Horizontal)
            .toReadiumTextStyling(isLandscape = true, isDoublePage = true)
        assertEquals(2, styling.columnCount)
    }

    @Test fun scrollModeHasNoColumnCount() {
        assertNull(defaults.copy(orientation = ReaderOrientation.Vertical).toReadiumTextStyling().columnCount)
    }

    @Test fun fixedLayoutHasNoColumnCount() {
        val styling = defaults.copy(orientation = ReaderOrientation.Horizontal)
            .toReadiumTextStyling(isFixedLayout = true)
        assertNull(styling.columnCount)
    }

    @Test fun forcePaginatedInLandscapePinsOneColumnEvenFromScroll() {
        val styling = defaults.copy(
            orientation = ReaderOrientation.Vertical,
            forcePaginatedInLandscape = true,
        ).toReadiumTextStyling(isLandscape = true)
        assertEquals(1, styling.columnCount)
    }

    /**
     * `toReadiumTextStyling` used to inline `if (isLandscape && forcePaginatedInLandscape)
     * Horizontal else orientation` instead of calling `FormattingPreferences.effectiveOrientation`,
     * so the reader's column count and every other consumer of the same rule could drift apart
     * silently. This walks the whole input space and fails the moment the two disagree.
     */
    @Test fun columnCountAgreesWithTheSharedEffectiveOrientationForEveryInput() {
        for (orientation in ReaderOrientation.entries) {
            for (isLandscape in listOf(false, true)) {
                for (forcePaginated in listOf(false, true)) {
                    val prefs = defaults.copy(
                        orientation = orientation,
                        forcePaginatedInLandscape = forcePaginated,
                    )
                    assertEquals(
                        readiumColumnCount(
                            prefs.effectiveOrientation(isLandscape),
                            isFixedLayout = false,
                            isDoublePage = false,
                        ),
                        prefs.toReadiumTextStyling(isLandscape = isLandscape).columnCount,
                        "$orientation landscape=$isLandscape forcePaginated=$forcePaginated",
                    )
                }
            }
        }
    }

    @Test fun originalFontLeavesThePublisherTypographyAlone() {
        assertNull(ReaderFontFamily.Original.readiumFontFamilyName())
    }

    @Test fun everyOtherFontNamesItsCssFamily() {
        assertEquals("serif", ReaderFontFamily.Serif.readiumFontFamilyName())
        assertEquals("sans-serif", ReaderFontFamily.SansSerif.readiumFontFamilyName())
        assertEquals("monospace", ReaderFontFamily.Monospace.readiumFontFamilyName())
        assertEquals("Literata", ReaderFontFamily.Literata.readiumFontFamilyName())
        assertEquals("Merriweather", ReaderFontFamily.Merriweather.readiumFontFamilyName())
        assertEquals("OpenDyslexic", ReaderFontFamily.OpenDyslexic.readiumFontFamilyName())
    }

    @Test fun autoFallsBackToLightRatherThanCrashing() {
        // Production resolves Auto upstream; this is the defensive branch, matching the palette.
        assertEquals(ReadiumThemeName.LIGHT, ReaderTheme.Auto.toReadiumThemeName())
    }

    @Test fun themeNameValuesAreTheStringsTheSwiftBridgeSwitchesOn() {
        assertEquals("light", ReadiumThemeName.LIGHT.value)
        assertEquals("dark", ReadiumThemeName.DARK.value)
        assertEquals("sepia", ReadiumThemeName.SEPIA.value)
    }
}
