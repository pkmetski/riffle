package com.riffle.app.feature.reader

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.app.feature.reader.cbz.ComicDisplaySection
import com.riffle.app.feature.readersettings.DisplaySection
import com.riffle.app.feature.readersettings.formatting.RenderCapabilities
import com.riffle.core.domain.AutoReaderThemeMode
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.comic.ComicFormattingPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phone-form-factor (no @TabletLayout): runs on the Harness Medium Phone AVD via `make harness-test`.
 */
@RunWith(AndroidJUnit4::class)
class ReaderSettingsSectionsTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun readerSheet_showsAllThreeTabs_andBehaviorReachable() {
        // "Behavior" tab was retired — device-level settings moved to top-level Settings section.
        // This test now pins the 2-tab sheet (Formatting + Display).
        composeTestRule.setContent {
            ReaderSettingsSheet(
                prefs = FormattingPreferences(),
                capabilities = RenderCapabilities.EPUB,
                hasBookOverrides = false,
                onPrefsChange = {},
                onReset = {},
                onDismiss = {},
            )
        }
        composeTestRule.onNodeWithText("Formatting").assertIsDisplayed()
        composeTestRule.onNodeWithText("Display").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Behavior").assertCountEquals(0)
    }

    @Test
    fun displaySection_editableHostShowsScheduleEditor() {
        composeTestRule.setContent {
            DisplaySection(
                prefs = FormattingPreferences().copy(theme = ReaderTheme.Auto),
                onPrefsChange = {},
                scheduleEditable = true,
            )
        }
        composeTestRule.onNodeWithText("Day starts at").assertIsDisplayed()
    }

    @Test
    fun displaySection_readerHostShowsReadOnlySummaryNotEditor() {
        composeTestRule.setContent {
            DisplaySection(
                prefs = FormattingPreferences().copy(theme = ReaderTheme.Auto),
                onPrefsChange = {},
                scheduleEditable = false,
            )
        }
        composeTestRule.onNodeWithText("Edit Auto in Settings → Display").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Day starts at").assertCountEquals(0)
    }

    @Test
    fun displaySection_appThemeAutoModeHidesScheduleEditor() {
        composeTestRule.setContent {
            DisplaySection(
                prefs = FormattingPreferences().copy(
                    theme = ReaderTheme.Auto,
                    autoReaderThemeMode = AutoReaderThemeMode.AppTheme,
                ),
                onPrefsChange = {},
                scheduleEditable = true,
            )
        }
        composeTestRule.onNodeWithText("App theme").assertIsDisplayed()
        composeTestRule.onNodeWithText("Light app theme").assertIsDisplayed()
        composeTestRule.onNodeWithText("Dark app theme").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Day starts at").assertCountEquals(0)
    }

    @Test
    fun coloredChapterMap_isGreyedOutWhenChapterMapIsOff() {
        composeTestRule.setContent {
            DisplaySection(
                prefs = FormattingPreferences(
                    showChapterMap = false,
                    coloredChapterMap = true,
                ),
                onPrefsChange = {},
                scheduleEditable = false,
            )
        }

        composeTestRule.onNodeWithText("Colored chapter map").assertIsDisplayed()
        composeTestRule.onNodeWithTag("colored_chapter_map_toggle").assertIsNotEnabled()
    }

    @Test
    fun coloredChapterMap_isEnabledAndUpdatesPreferenceWhenChapterMapIsOn() {
        var updated: FormattingPreferences? = null
        composeTestRule.setContent {
            DisplaySection(
                prefs = FormattingPreferences(
                    showChapterMap = true,
                    coloredChapterMap = true,
                ),
                onPrefsChange = { updated = it },
                scheduleEditable = false,
            )
        }

        composeTestRule.onNodeWithTag("colored_chapter_map_toggle")
            .assertIsEnabled()
            .performClick()
        composeTestRule.runOnIdle {
            assertFalse(updated!!.coloredChapterMap)
        }
    }

    @Test
    fun displaySection_allReadingOrientationChipsAreClickable() {
        var updated: FormattingPreferences? = null
        composeTestRule.setContent {
            DisplaySection(
                prefs = FormattingPreferences().copy(orientation = ReaderOrientation.Horizontal),
                onPrefsChange = { updated = it },
                scheduleEditable = false,
            )
        }

        // All three orientation chips must be reachable and produce the expected preference update.
        // Regression: in locales with longer translations (e.g. Spanish "Continuo") the last chip
        // was clipped/word-wrapped because the Row had no horizontal scroll.
        composeTestRule.onNodeWithContentDescription("Paginated reading orientation").performClick()
        composeTestRule.runOnIdle { assertEquals(ReaderOrientation.Horizontal, updated!!.orientation) }

        composeTestRule.onNodeWithContentDescription("Scroll reading orientation").performClick()
        composeTestRule.runOnIdle { assertEquals(ReaderOrientation.Vertical, updated!!.orientation) }

        composeTestRule.onNodeWithContentDescription("Continuous reading orientation").performClick()
        composeTestRule.runOnIdle { assertEquals(ReaderOrientation.Continuous, updated!!.orientation) }
    }

    @Test
    fun displaySection_allConcreteThemeChipsAreClickable() {
        var updated: FormattingPreferences? = null
        composeTestRule.setContent {
            DisplaySection(
                prefs = FormattingPreferences().copy(theme = ReaderTheme.Light),
                onPrefsChange = { updated = it },
                scheduleEditable = false,
            )
        }

        // All four concrete theme chips must be reachable and produce the expected preference update.
        // Regression: in locales with longer translations (e.g. Spanish "Atenuado") the Sepia chip
        // was squeezed to zero width because the Row had no horizontal scroll.
        composeTestRule.onNodeWithContentDescription("Light theme").performClick()
        composeTestRule.runOnIdle { assertEquals(ReaderTheme.Light, updated!!.theme) }

        composeTestRule.onNodeWithContentDescription("Dark theme").performClick()
        composeTestRule.runOnIdle { assertEquals(ReaderTheme.Dark, updated!!.theme) }

        composeTestRule.onNodeWithContentDescription("Dim theme").performClick()
        composeTestRule.runOnIdle { assertEquals(ReaderTheme.DarkDim, updated!!.theme) }

        composeTestRule.onNodeWithContentDescription("Sepia theme").performClick()
        composeTestRule.runOnIdle { assertEquals(ReaderTheme.Sepia, updated!!.theme) }
    }

    @Test
    fun comicDisplaySection_backgroundThemeIncludesAutoThemeChip() {
        var selected: ReaderTheme? = null
        composeTestRule.setContent {
            Column(Modifier.padding(horizontal = 24.dp)) {
                ComicDisplaySection(
                    prefs = ComicFormattingPreferences(backgroundTheme = ReaderTheme.Dark),
                    onBackgroundThemeChange = { selected = it },
                    onPanelViewChange = {},
                    onPanelOverflowChange = {},
                    onPanelAnimationSpeedChange = {},
                    onShowReadingProgressChange = {},
                    onShowPageNumbersChange = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Background").assertIsDisplayed()
        composeTestRule.onNodeWithText("Background").assertLeftPositionInRootIsEqualTo(24.dp)
        composeTestRule.onNodeWithText("Auto").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Dim").assertCountEquals(0)
        composeTestRule.onNodeWithContentDescription("Sepia theme").performClick()
        composeTestRule.runOnIdle {
            assertEquals(ReaderTheme.Sepia, selected)
        }
        composeTestRule.onNodeWithContentDescription("Auto theme").performClick()
        composeTestRule.runOnIdle {
            assertEquals(ReaderTheme.Auto, selected)
        }
    }
}
