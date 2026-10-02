package com.riffle.feature.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.autoscroll.AutoScrollSpeed
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.settings.ui.panels.AutoScrollSettingsPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Auto-scroll panel on iOS.
 *
 * The whole panel was deleted while the iOS reader had no auto-scroll to configure. It is back
 * because the reader has one now (`AutoScrollController` + `ReadiumSwiftNavigator.scrollByPx`,
 * mounted by `IosEpubReaderScreen`), and both controls must actually write.
 *
 * Runs on `iosSimulatorArm64` as part of `:feature:settings-ui:iosSimulatorArm64Test`.
 */
class SettingsAutoScrollPanelTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun autoScrollPanelOffersTheToggleAndTheSpeedStepper() = runComposeUiTest {
        setContent {
            Column { AutoScrollSettingsPanel(FormattingPreferences(), onPrefsChange = {}, onDismiss = {}) }
        }
        onNodeWithTag(TestTags.SETTINGS_AUTO_SCROLL_READER_TOGGLE).assertIsDisplayed()
        onNodeWithText("${FormattingPreferences().autoScrollWpm} wpm").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theReaderToggleSwitchWritesShowAutoScroll() = runComposeUiTest {
        val writes = mutableListOf<FormattingPreferences>()
        setContent {
            Column {
                AutoScrollSettingsPanel(
                    FormattingPreferences(showAutoScroll = false),
                    onPrefsChange = { writes += it },
                    onDismiss = {},
                )
            }
        }
        onNodeWithTag(TestTags.SETTINGS_AUTO_SCROLL_READER_TOGGLE).performClick()
        assertEquals(1, writes.size)
        assertTrue(writes[0].showAutoScroll)
    }

    /**
     * The stepper goes through `AutoScrollSpeed.of`, the same range and snap rule the HUD pill's
     * nudges and the ticker use, with a 20-WPM step per tap (coarser than the slider's own 10-WPM
     * snap — single-snap deltas are imperceptible for reading pace).
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theSpeedStepperMovesInAutoScrollSpeedSteps() = runComposeUiTest {
        val writes = mutableListOf<FormattingPreferences>()
        setContent {
            Column {
                AutoScrollSettingsPanel(
                    FormattingPreferences(autoScrollWpm = 250),
                    onPrefsChange = { writes += it },
                    onDismiss = {},
                )
            }
        }
        onNodeWithTag(TestTags.SETTINGS_AUTO_SCROLL_WPM_INCREMENT).performClick()
        onNodeWithTag(TestTags.SETTINGS_AUTO_SCROLL_WPM_DECREMENT).performClick()
        assertEquals(listOf(270, 230), writes.map { it.autoScrollWpm })
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theSpeedStepperClampsAtTheDomainBounds() = runComposeUiTest {
        val writes = mutableListOf<FormattingPreferences>()
        setContent {
            Column {
                AutoScrollSettingsPanel(
                    FormattingPreferences(autoScrollWpm = AutoScrollSpeed.MAX_WPM),
                    onPrefsChange = { writes += it },
                    onDismiss = {},
                )
            }
        }
        onNodeWithTag(TestTags.SETTINGS_AUTO_SCROLL_WPM_INCREMENT).performClick()
        assertEquals(listOf(AutoScrollSpeed.MAX_WPM), writes.map { it.autoScrollWpm })
    }
}
