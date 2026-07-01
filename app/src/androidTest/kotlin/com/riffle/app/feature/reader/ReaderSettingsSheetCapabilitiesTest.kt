package com.riffle.app.feature.reader

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.app.feature.reader.formatting.RenderCapabilities
import com.riffle.core.domain.FormattingPreferences
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phone-form-factor (no @TabletLayout): runs on the Harness Medium Phone AVD via `make harness-test`.
 */
@RunWith(AndroidJUnit4::class)
class ReaderSettingsSheetCapabilitiesTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun pdfCaps_hideFontFamilyAndReadingModeAndDoublePage() {
        composeTestRule.setContent {
            ReaderSettingsSheet(
                prefs = FormattingPreferences(),
                capabilities = RenderCapabilities.PDF,
                hasBookOverrides = false,
                onPrefsChange = {}, onReset = {}, onDismiss = {},
                keepScreenOn = false, onKeepScreenOnChange = {},
                volumeKeyNavigationEnabled = false, onVolumeKeyNavigationEnabledChange = {},
                invertVolumeKeys = false, onInvertVolumeKeysChange = {},
            )
        }
        // "Font" is the section label that heads the font-family picker chip row (FormattingSection.kt).
        composeTestRule.onNodeWithText("Font").assertDoesNotExist()
        composeTestRule.onNodeWithText("Display").performClick()
        composeTestRule.onNodeWithText("Reading mode").assertDoesNotExist()
        composeTestRule.onNodeWithText("Double page in landscape").assertDoesNotExist()
    }

    @Test
    fun epubCaps_showFontFamilyAndReadingModeAndDoublePage() {
        composeTestRule.setContent {
            ReaderSettingsSheet(
                prefs = FormattingPreferences(),
                capabilities = RenderCapabilities.EPUB,
                hasBookOverrides = false,
                onPrefsChange = {}, onReset = {}, onDismiss = {},
                keepScreenOn = false, onKeepScreenOnChange = {},
                volumeKeyNavigationEnabled = false, onVolumeKeyNavigationEnabledChange = {},
                invertVolumeKeys = false, onInvertVolumeKeysChange = {},
            )
        }
        composeTestRule.onNodeWithText("Font").assertIsDisplayed()
        composeTestRule.onNodeWithText("Display").performClick()
        composeTestRule.onNodeWithText("Reading mode").assertIsDisplayed()
        composeTestRule.onNodeWithText("Double page in landscape").assertIsDisplayed()
    }
}
