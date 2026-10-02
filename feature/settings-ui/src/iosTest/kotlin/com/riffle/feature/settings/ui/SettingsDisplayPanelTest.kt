package com.riffle.feature.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.core.domain.FormattingPreferences
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.settings.ui.panels.DisplaySettingsPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Display panel's On-Screen Info section on iOS.
 *
 * All five switches were deleted from this panel when the reader had no overlay to configure.
 * The overlay exists now (`:feature:reader-ui`'s `ChapterMapOverlay`, mounted by
 * `IosEpubReaderScreen`), so the switches are back — and each one must actually write its
 * preference, which is what a hidden-then-restored control most easily gets wrong.
 *
 * Runs on `iosSimulatorArm64` as part of `:feature:settings-ui:iosSimulatorArm64Test`.
 */
class SettingsDisplayPanelTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun displayPanelOffersEveryOnScreenInfoSwitch() = runComposeUiTest {
        setContent {
            Column { DisplaySettingsPanel(FormattingPreferences(), onPrefsChange = {}, onDismiss = {}) }
        }
        onNodeWithText("On-screen info").assertIsDisplayed()
        onNodeWithTag(TestTags.SETTINGS_DISPLAY_CHAPTER_MAP).assertIsDisplayed()
        onNodeWithTag(TestTags.READER_SETTINGS_COLORED_CHAPTER_MAP).assertIsDisplayed()
        onNodeWithTag(TestTags.SETTINGS_DISPLAY_CURRENT_CHAPTER_LABEL).assertIsDisplayed()
        onNodeWithTag(TestTags.SETTINGS_DISPLAY_READING_PROGRESS_LABELS).assertIsDisplayed()
        onNodeWithTag(TestTags.SETTINGS_DISPLAY_TIME_REMAINING).assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun eachOnScreenInfoSwitchWritesItsOwnPreference() = runComposeUiTest {
        var prefs = FormattingPreferences(
            showChapterMap = false,
            coloredChapterMap = false,
            showCurrentChapterLabel = false,
            showReadingProgressLabels = false,
            showReadingTimeEstimate = false,
        )
        val writes = mutableListOf<FormattingPreferences>()
        setContent {
            Column { DisplaySettingsPanel(prefs, onPrefsChange = { writes += it }, onDismiss = {}) }
        }
        onNodeWithTag(TestTags.SETTINGS_DISPLAY_CHAPTER_MAP).performClick()
        onNodeWithTag(TestTags.SETTINGS_DISPLAY_CURRENT_CHAPTER_LABEL).performClick()
        onNodeWithTag(TestTags.SETTINGS_DISPLAY_READING_PROGRESS_LABELS).performClick()
        onNodeWithTag(TestTags.SETTINGS_DISPLAY_TIME_REMAINING).performClick()

        assertEquals(4, writes.size)
        assertTrue(writes[0].showChapterMap, "chapter map switch must set showChapterMap")
        assertTrue(writes[1].showCurrentChapterLabel, "label switch must set showCurrentChapterLabel")
        assertTrue(writes[2].showReadingProgressLabels, "progress switch must set showReadingProgressLabels")
        assertTrue(writes[3].showReadingTimeEstimate, "time switch must set showReadingTimeEstimate")
        // Each switch writes exactly one flag — a copy/paste slip between five near-identical
        // rows is the failure mode here, and it would otherwise be invisible.
        assertEquals(
            listOf(false, false, false, false),
            listOf(
                writes[0].showCurrentChapterLabel,
                writes[1].showChapterMap,
                writes[2].showReadingTimeEstimate,
                writes[3].showReadingProgressLabels,
            ),
        )
        prefs = writes.last()
    }

    /**
     * "Colored chapter map" is a sub-setting of the chapter map, so with the map off its row is
     * inert — the same `ReaderSettingsSections.coloredChapterMapEnabled` rule Android's
     * `DisplaySection` greys it out with.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun coloredChapterMapIsInertWhileTheChapterMapIsOff() = runComposeUiTest {
        val writes = mutableListOf<FormattingPreferences>()
        setContent {
            Column {
                DisplaySettingsPanel(
                    FormattingPreferences(showChapterMap = false, coloredChapterMap = false),
                    onPrefsChange = { writes += it },
                    onDismiss = {},
                )
            }
        }
        onNodeWithTag(TestTags.READER_SETTINGS_COLORED_CHAPTER_MAP).performClick()
        assertEquals(emptyList(), writes)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun coloredChapterMapTogglesOnceTheChapterMapIsOn() = runComposeUiTest {
        val writes = mutableListOf<FormattingPreferences>()
        setContent {
            Column {
                DisplaySettingsPanel(
                    FormattingPreferences(showChapterMap = true, coloredChapterMap = false),
                    onPrefsChange = { writes += it },
                    onDismiss = {},
                )
            }
        }
        onNodeWithTag(TestTags.READER_SETTINGS_COLORED_CHAPTER_MAP).performClick()
        assertEquals(1, writes.size)
        assertTrue(writes[0].coloredChapterMap)
    }
}
