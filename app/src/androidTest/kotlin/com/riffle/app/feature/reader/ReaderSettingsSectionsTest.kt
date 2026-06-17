package com.riffle.app.feature.reader

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderTheme
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
        composeTestRule.setContent {
            ReaderSettingsSheet(
                prefs = FormattingPreferences(),
                hasBookOverrides = false,
                onPrefsChange = {},
                onReset = {},
                onDismiss = {},
                keepScreenOn = false, onKeepScreenOnChange = {},
                volumeKeyNavigationEnabled = false, onVolumeKeyNavigationEnabledChange = {},
                invertVolumeKeys = false, onInvertVolumeKeysChange = {},
            )
        }
        composeTestRule.onNodeWithText("Formatting").assertIsDisplayed()
        composeTestRule.onNodeWithText("Display").assertIsDisplayed()
        composeTestRule.onNodeWithText("Behavior").assertIsDisplayed()
        // Behavior is reachable while reading.
        composeTestRule.onNodeWithText("Behavior").performClick()
        composeTestRule.onNodeWithText("Keep screen on").assertIsDisplayed()
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
}
