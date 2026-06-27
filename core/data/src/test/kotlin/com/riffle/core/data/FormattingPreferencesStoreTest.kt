package com.riffle.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.riffle.core.domain.FormattingPreferences
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

    private fun buildStore() = FormattingPreferencesStoreImpl(
        PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmp.newFile("prefs.preferences_pb") },
        )
    )

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
    fun `autoScrollWpm default is 250 for empty DataStore`() = testScope.runTest {
        assertEquals(250, buildStore().preferences.first().autoScrollWpm)
    }

    @Test
    fun `saved autoScrollWpm round-trips through the DataStore`() = testScope.runTest {
        val store = buildStore()
        store.update(FormattingPreferences(autoScrollWpm = 320))
        assertEquals(320, store.preferences.first().autoScrollWpm)
    }
}
