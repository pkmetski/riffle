package com.riffle.app.feature.reader

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.app.feature.reader.formatting.RenderCapabilities
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderTheme
import org.junit.Assert.assertFalse
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
        composeTestRule.onNodeWithText("Edit the schedule in Settings → Display").assertIsDisplayed()
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
}
