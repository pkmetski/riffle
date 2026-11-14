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

    private val testKeys = listOf("background_theme")

    @BeforeTest
    fun setUp() {
        testKeys.forEach { NSUserDefaults.standardUserDefaults.removeObjectForKey(it) }
    }

    @AfterTest
    fun tearDown() {
        testKeys.forEach { NSUserDefaults.standardUserDefaults.removeObjectForKey(it) }
    }

    private fun buildStore() = ComicFormattingPreferencesStoreImpl(IosKeyValueStore())

    @Test
    fun darkDimNormalisesToDarkOnWrite() = runTest {
        val store = buildStore()
        store.update(ComicFormattingPreferences(backgroundTheme = ReaderTheme.DarkDim))
        val prefs = store.preferences.first()
        assertEquals(ReaderTheme.Dark, prefs.backgroundTheme)
    }

    @Test
    fun darkDimStoredAsLiteralNormalisesToDarkOnLoad() = runTest {
        NSUserDefaults.standardUserDefaults.setObject("DarkDim", forKey = "background_theme")
        val store = buildStore()
        val prefs = store.preferences.first()
        assertEquals(ReaderTheme.Dark, prefs.backgroundTheme)
    }

    @Test
    fun darkThemeRoundTripsUnchanged() = runTest {
        val store = buildStore()
        store.update(ComicFormattingPreferences(backgroundTheme = ReaderTheme.Dark))
        assertEquals(ReaderTheme.Dark, store.preferences.first().backgroundTheme)
    }

    @Test
    fun autoRoundTripsUnchanged() = runTest {
        val store = buildStore()
        store.update(ComicFormattingPreferences(backgroundTheme = ReaderTheme.Auto))
        assertEquals(ReaderTheme.Auto, store.preferences.first().backgroundTheme)
    }
}
