package com.riffle.core.data

import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.comic.ComicFormattingPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSUserDefaults
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression: DarkDim and Auto are not valid comic background themes. The store must normalise
 * them through asComicBackgroundTheme() on both write and read so the comic reader never sees
 * an unsupported enum value. Bug: both update() and loadFromDefaults() stored the raw enum name,
 * so DarkDim persisted and the reader crashed on the next launch (#1208).
 */
class IosComicFormattingPreferencesStoreImplTest {

    private val testKeys = listOf("comic_formatting.background_theme")

    @BeforeTest
    fun setUp() {
        testKeys.forEach { NSUserDefaults.standardUserDefaults.removeObjectForKey(it) }
    }

    @AfterTest
    fun tearDown() {
        testKeys.forEach { NSUserDefaults.standardUserDefaults.removeObjectForKey(it) }
    }

    @Test
    fun darkDimNormalisesToDarkOnWrite() = runTest {
        val store = IosComicFormattingPreferencesStoreImpl()
        store.update(ComicFormattingPreferences(backgroundTheme = ReaderTheme.DarkDim))
        val prefs = store.preferences.first()
        // DarkDim → Dark via asComicBackgroundTheme()
        assertEquals(ReaderTheme.Dark, prefs.backgroundTheme)
    }

    @Test
    fun darkDimStoredAsLiteralNormalisesToDarkOnLoad() = runTest {
        // Simulate a pre-fix store that persisted the raw enum name
        NSUserDefaults.standardUserDefaults.setObject("DarkDim", forKey = "comic_formatting.background_theme")
        val store = IosComicFormattingPreferencesStoreImpl()
        // The store must normalise on load so the loaded value is never DarkDim
        val prefs = store.preferences.first()
        assertEquals(ReaderTheme.Dark, prefs.backgroundTheme)
    }

    @Test
    fun darkThemeRoundTripsUnchanged() = runTest {
        val store = IosComicFormattingPreferencesStoreImpl()
        store.update(ComicFormattingPreferences(backgroundTheme = ReaderTheme.Dark))
        assertEquals(ReaderTheme.Dark, store.preferences.first().backgroundTheme)
    }

    @Test
    fun autoRoundTripsUnchanged() = runTest {
        // Auto is a valid stored value (user set "follow system"); asComicBackgroundTheme() leaves it
        // unchanged. The actual Dark/Light resolution happens at render time via resolveComicBackgroundTheme.
        val store = IosComicFormattingPreferencesStoreImpl()
        store.update(ComicFormattingPreferences(backgroundTheme = ReaderTheme.Auto))
        assertEquals(ReaderTheme.Auto, store.preferences.first().backgroundTheme)
    }
}
