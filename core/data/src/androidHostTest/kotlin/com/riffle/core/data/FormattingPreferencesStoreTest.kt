package com.riffle.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.riffle.core.domain.AppThemeReaderThemes
import com.riffle.core.domain.AutoReaderThemeMode
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.models.HighlightColor
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.ThemeSchedule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalTime

@OptIn(ExperimentalCoroutinesApi::class)
class FormattingPreferencesStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private fun buildDataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmp.newFile("prefs.preferences_pb") },
        )

    private fun buildStore() = FormattingPreferencesStoreImpl(buildDataStore())

    @Test
    fun `default preferences returned when DataStore is empty`() = testScope.runTest {
        assertEquals(FormattingPreferences(), buildStore().preferences.first())
    }

    @Test
    fun `saved fontSize is returned after update`() = testScope.runTest {
        val store = buildStore()
        store.update(FormattingPreferences(fontSize = 1.5f))
        assertEquals(1.5f, store.preferences.first().fontSize)
    }

    @Test
    fun `saved theme is returned after update`() = testScope.runTest {
        val store = buildStore()
        store.update(FormattingPreferences(theme = ReaderTheme.Sepia))
        assertEquals(ReaderTheme.Sepia, store.preferences.first().theme)
    }

    @Test
    fun `saved fontFamily is returned after update`() = testScope.runTest {
        val store = buildStore()
        store.update(FormattingPreferences(fontFamily = ReaderFontFamily.OpenDyslexic))
        assertEquals(ReaderFontFamily.OpenDyslexic, store.preferences.first().fontFamily)
    }

    // Regression: the new Serif (real CSS serif face) must round-trip through the DataStore
    // without collapsing to Original. The codec persists it as "SerifV2" specifically so that
    // legacy "Serif" strings (previously passthrough) can be distinguished from new picks.
    @Test
    fun `new Serif round-trips as Serif, not Original`() = testScope.runTest {
        val store = buildStore()
        store.update(FormattingPreferences(fontFamily = ReaderFontFamily.Serif))
        assertEquals(ReaderFontFamily.Serif, store.preferences.first().fontFamily)
    }

    // Regression: users upgrading from a build where "Serif" was the passthrough default (and
    // was persisted verbatim by update()) must land on Original, not on the new real-serif
    // Serif. If this flipped, every upgrader would suddenly see every book forced into CSS
    // serif rather than the publisher font they had been reading.
    @Test fun `legacy 'Serif' persisted name decodes to Original`() {
        assertEquals(ReaderFontFamily.Original, "Serif".decodeFontFamily())
    }

    @Test fun `new Serif encodes to SerifV2 so it survives the legacy decode`() {
        assertEquals("SerifV2", ReaderFontFamily.Serif.encodePersistName())
        assertEquals(ReaderFontFamily.Serif, "SerifV2".decodeFontFamily())
    }

    @Test
    fun `saved lineSpacing is returned after update`() = testScope.runTest {
        val store = buildStore()
        store.update(FormattingPreferences(lineSpacing = 1.8f))
        assertEquals(1.8f, store.preferences.first().lineSpacing)
    }

    @Test
    fun `saved margins is returned after update`() = testScope.runTest {
        val store = buildStore()
        store.update(FormattingPreferences(margins = 1.6f))
        assertEquals(1.6f, store.preferences.first().margins)
    }

    @Test
    fun `saved orientation is returned after update`() = testScope.runTest {
        val store = buildStore()
        store.update(FormattingPreferences(orientation = ReaderOrientation.Vertical))
        assertEquals(ReaderOrientation.Vertical, store.preferences.first().orientation)
    }

    @Test
    fun `colored chapter map defaults on and round-trips through DataStore`() = testScope.runTest {
        val store = buildStore()
        assertEquals(true, store.preferences.first().coloredChapterMap)

        store.update(FormattingPreferences(coloredChapterMap = false))

        assertEquals(false, store.preferences.first().coloredChapterMap)
    }

    @Test
    fun `saved justifyText is returned after update`() = testScope.runTest {
        val store = buildStore()
        store.update(FormattingPreferences(justifyText = false))
        assertEquals(false, store.preferences.first().justifyText)
    }

    @Test
    fun `saved themeSchedule round-trips through the DataStore`() = testScope.runTest {
        val store = buildStore()
        val schedule = ThemeSchedule(
            dayStart = LocalTime.of(6, 30),
            nightStart = LocalTime.of(19, 45),
            dayTheme = ReaderTheme.Sepia,
            nightTheme = ReaderTheme.DarkDim,
        )
        store.update(FormattingPreferences(themeSchedule = schedule))
        assertEquals(schedule, store.preferences.first().themeSchedule)
    }

    @Test
    fun `themeSchedule defaults are returned for empty DataStore`() = testScope.runTest {
        assertEquals(ThemeSchedule(), buildStore().preferences.first().themeSchedule)
    }

    @Test
    fun `Auto theme round-trips through the DataStore`() = testScope.runTest {
        val store = buildStore()
        store.update(FormattingPreferences(theme = ReaderTheme.Auto))
        assertEquals(ReaderTheme.Auto, store.preferences.first().theme)
    }

    @Test
    fun `legacy Auto schedule preferences load as time based Auto without losing schedule`() = testScope.runTest {
        val dataStore = buildDataStore()
        val schedule = ThemeSchedule(
            dayStart = LocalTime.of(6, 30),
            nightStart = LocalTime.of(19, 45),
            dayTheme = ReaderTheme.Sepia,
            nightTheme = ReaderTheme.DarkDim,
        )
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("theme")] = ReaderTheme.Auto.name
            prefs[intPreferencesKey("theme_schedule_day_start_minute_of_day")] =
                schedule.dayStart.hour * 60 + schedule.dayStart.minute
            prefs[intPreferencesKey("theme_schedule_night_start_minute_of_day")] =
                schedule.nightStart.hour * 60 + schedule.nightStart.minute
            prefs[stringPreferencesKey("theme_schedule_day_theme")] = schedule.dayTheme.name
            prefs[stringPreferencesKey("theme_schedule_night_theme")] = schedule.nightTheme.name
        }

        val loaded = FormattingPreferencesStoreImpl(dataStore).preferences.first()

        assertEquals(ReaderTheme.Auto, loaded.theme)
        assertEquals(AutoReaderThemeMode.Schedule, loaded.autoReaderThemeMode)
        assertEquals(schedule, loaded.themeSchedule)
        assertEquals(AppThemeReaderThemes(), loaded.appThemeReaderThemes)
    }

    @Test
    fun `auto reader theme mode round-trips through the DataStore`() = testScope.runTest {
        val store = buildStore()
        store.update(FormattingPreferences(autoReaderThemeMode = AutoReaderThemeMode.AppTheme))
        assertEquals(AutoReaderThemeMode.AppTheme, store.preferences.first().autoReaderThemeMode)
    }

    @Test
    fun `app theme reader themes round-trip through the DataStore`() = testScope.runTest {
        val store = buildStore()
        val appThemeReaderThemes = AppThemeReaderThemes(
            lightTheme = ReaderTheme.Sepia,
            darkTheme = ReaderTheme.DarkDim,
        )
        store.update(FormattingPreferences(appThemeReaderThemes = appThemeReaderThemes))
        assertEquals(appThemeReaderThemes, store.preferences.first().appThemeReaderThemes)
    }

    @Test
    fun `autoScrollWpm default is 250 for empty DataStore`() = testScope.runTest {
        assertEquals(250, buildStore().preferences.first().autoScrollWpm)
    }

    @Test
    fun `saved autoScrollWpm round-trips through the DataStore`() = testScope.runTest {
        val store = buildStore()
        store.update(FormattingPreferences(autoScrollWpm = 320))
        assertEquals(320, store.preferences.first().autoScrollWpm)
    }

    @Test
    fun `Cadence defaults for empty DataStore`() = testScope.runTest {
        val prefs = buildStore().preferences.first()
        assertEquals(false, prefs.showAutoScroll)
        assertEquals(250, prefs.cadenceWpm)
        assertEquals(false, prefs.showCadence)
        assertEquals(HighlightColor.YELLOW, prefs.cadenceHighlightColor)
    }

    @Test
    fun `showAutoScroll round-trips through the DataStore`() = testScope.runTest {
        val store = buildStore()
        store.update(FormattingPreferences(showAutoScroll = true))
        assertEquals(true, store.preferences.first().showAutoScroll)
    }

    @Test
    fun `showCadence round-trips through the DataStore`() = testScope.runTest {
        val store = buildStore()
        store.update(FormattingPreferences(showCadence = true))
        assertEquals(true, store.preferences.first().showCadence)
    }

    @Test
    fun `cadenceWpm round-trips through the DataStore`() = testScope.runTest {
        val store = buildStore()
        store.update(FormattingPreferences(cadenceWpm = 340))
        assertEquals(340, store.preferences.first().cadenceWpm)
    }

    @Test
    fun `cadenceHighlightColor round-trips through the DataStore`() = testScope.runTest {
        val store = buildStore()
        store.update(FormattingPreferences(cadenceHighlightColor = HighlightColor.GREEN))
        assertEquals(HighlightColor.GREEN, store.preferences.first().cadenceHighlightColor)
    }

    @Test
    fun `cadencePlatformSupported defaults to true for empty DataStore`() = testScope.runTest {
        assertEquals(true, buildStore().preferences.first().cadencePlatformSupported)
    }

    // Regression: setCadencePlatformSupported must persist so the Settings screen (which opens
    // without the reader) can gate its Cadence entry on the flag the reader saw on last book open.
    // Flip this assertion and Android 7.1.1 users would see a Cadence Settings entry that never
    // surfaces a runtime button — exactly the bug this fix pins.
    @Test
    fun `setCadencePlatformSupported false round-trips through the DataStore`() = testScope.runTest {
        val store = buildStore()
        store.setCadencePlatformSupported(false)
        assertEquals(false, store.preferences.first().cadencePlatformSupported)
    }

    // Regression: update() must NOT overwrite the persisted platform-supported flag. Otherwise, a
    // user changing any preference in Settings between reader closes would clobber the reader's
    // feature-detect result with the stale in-memory `true` default.
    @Test
    fun `update does not overwrite the persisted cadencePlatformSupported flag`() = testScope.runTest {
        val store = buildStore()
        store.setCadencePlatformSupported(false)
        // Now simulate a general preferences update carrying a stale in-memory `true` for the flag.
        store.update(FormattingPreferences(cadencePlatformSupported = true, fontSize = 1.4f))
        val prefs = store.preferences.first()
        assertEquals(false, prefs.cadencePlatformSupported)
        assertEquals(1.4f, prefs.fontSize)
    }
}
