package com.riffle.core.domain.appearance

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the Auto-resolution both reader hosts now share (Android's `FormattingSession`, iOS's
 * `IosEpubReaderScreen`). iOS previously handed raw preferences to its Readium bridge, where
 * `ReaderTheme.Auto` fell through a `when` to `"light"` — the schedule and the app-theme follow
 * mode never applied (#1071 §15.1).
 */
class ResolvedFormattingPreferencesTest {

    @Test
    fun autoBecomesTheCoordinatorResolvedTheme() {
        val prefs = FormattingPreferences(theme = ReaderTheme.Auto)

        assertEquals(
            ReaderTheme.Dark,
            prefs.withResolvedTheme(appearance(ConcreteReaderTheme.Dark)).theme,
        )
        assertEquals(
            ReaderTheme.Sepia,
            prefs.withResolvedTheme(appearance(ConcreteReaderTheme.Sepia)).theme,
        )
        assertEquals(
            ReaderTheme.DarkDim,
            prefs.withResolvedTheme(appearance(ConcreteReaderTheme.DarkDim)).theme,
            "DarkDim must survive resolution — it is a distinct reader theme, not plain Dark",
        )
    }

    @Test
    fun aConcreteThemeIsLeftExactlyAsTheUserPickedIt() {
        val prefs = FormattingPreferences(theme = ReaderTheme.Sepia)

        val resolved = prefs.withResolvedTheme(appearance(ConcreteReaderTheme.Dark))

        assertEquals(prefs, resolved, "resolution must be identity for any non-Auto theme")
    }

    @Test
    fun everyOtherPreferenceSurvivesResolution() {
        val prefs = FormattingPreferences(theme = ReaderTheme.Auto, fontSize = 1.4f, lineSpacing = 1.8f)

        val resolved = prefs.withResolvedTheme(appearance(ConcreteReaderTheme.Dark))

        assertEquals(prefs.copy(theme = ReaderTheme.Dark), resolved)
    }

    private fun appearance(readerTheme: ConcreteReaderTheme) = ResolvedAppearance(
        appChrome = ChromeTheme.Light,
        readerTheme = readerTheme,
        isSystemDark = false,
    )
}
