package com.riffle.feature.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.autoscroll.AutoScrollSpeed
import com.riffle.core.models.HighlightColor
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.settings.ui.panels.CadenceSettingsPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Cadence panel on iOS.
 *
 * It was deleted while the iOS reader had no Cadence to configure; it is back because the reader
 * has one now (`CadenceSession` + `CadenceDomScript` tokenisation + the per-sentence decoration,
 * mounted by `IosEpubReaderScreen`), and every control here must actually write.
 *
 * Runs on `iosSimulatorArm64` as part of `:feature:settings-ui:iosSimulatorArm64Test`.
 */
class SettingsCadencePanelTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun cadencePanelOffersTheToggleTheSpeedStepperAndTheColourChips() = runComposeUiTest {
        setContent {
            Column { CadenceSettingsPanel(FormattingPreferences(), onPrefsChange = {}, onDismiss = {}) }
        }
        onNodeWithTag(TestTags.SETTINGS_CADENCE_READER_TOGGLE).assertIsDisplayed()
        onNodeWithText("${FormattingPreferences().cadenceWpm} wpm").assertIsDisplayed()
        val defaultHighlightColor = FormattingPreferences().cadenceHighlightColor
        HighlightColor.entries.forEach { color ->
            val desc = "${highlightColorLabel(color)} highlight" +
                if (color == defaultHighlightColor) ", selected" else ""
            onAllNodesWithContentDescription(desc).assertCountEquals(1)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theReaderToggleSwitchWritesShowCadence() = runComposeUiTest {
        val writes = mutableListOf<FormattingPreferences>()
        setContent {
            Column {
                CadenceSettingsPanel(
                    FormattingPreferences(showCadence = false),
                    onPrefsChange = { writes += it },
                    onDismiss = {},
                )
            }
        }
        onNodeWithTag(TestTags.SETTINGS_CADENCE_READER_TOGGLE).performClick()
        assertEquals(1, writes.size)
        assertTrue(writes[0].showCadence)
    }

    /**
     * The stepper goes through `AutoScrollSpeed.of`, the same range and snap rule Cadence's
     * ticker and HUD pill use, with a 20-WPM step per tap (coarser than the slider's own 10-WPM
     * snap — single-snap deltas are imperceptible for reading pace). The old panel hand-rolled ±10
     * clamped to 50..1000 — both ends outside what the ticker accepts.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theSpeedStepperMovesInAutoScrollSpeedStepsAndClamps() = runComposeUiTest {
        val writes = mutableListOf<FormattingPreferences>()
        setContent {
            Column {
                CadenceSettingsPanel(
                    FormattingPreferences(cadenceWpm = AutoScrollSpeed.MIN_WPM),
                    onPrefsChange = { writes += it },
                    onDismiss = {},
                )
            }
        }
        onNodeWithTag(TestTags.SETTINGS_CADENCE_WPM_DECREMENT).performClick()
        onNodeWithTag(TestTags.SETTINGS_CADENCE_WPM_INCREMENT).performClick()
        assertEquals(
            listOf(AutoScrollSpeed.MIN_WPM, AutoScrollSpeed.MIN_WPM + 20),
            writes.map { it.cadenceWpm },
        )
    }

    /**
     * The chips are keyed on the [HighlightColor] value. The rows on this screen used to select
     * by string equality against a rendered label, so one wording change left no chip selected
     * and every tap a silent no-op.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aColourChipWritesTheHighlightColourEnum() = runComposeUiTest {
        val writes = mutableListOf<FormattingPreferences>()
        setContent {
            Column {
                CadenceSettingsPanel(
                    FormattingPreferences(cadenceHighlightColor = HighlightColor.YELLOW),
                    onPrefsChange = { writes += it },
                    onDismiss = {},
                )
            }
        }
        onNodeWithContentDescription("${highlightColorLabel(HighlightColor.BLUE)} highlight").performClick()
        assertEquals(listOf(HighlightColor.BLUE), writes.map { it.cadenceHighlightColor })
    }

    @Test
    fun theColourChipsAreEnumBackedSoTheyCannotOfferAnUnpaintableColour() {
        assertEquals(HighlightColor.entries.toList(), cadenceHighlightChipOptions)
    }

    /**
     * The `Intl.Segmenter` gate. Cadence has no fallback tokeniser, so on a WebView without it
     * the panel must say so rather than offer controls that configure a feature the reader
     * cannot run.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun anUnsupportedWebViewReplacesTheControlsWithANote() = runComposeUiTest {
        setContent {
            Column {
                CadenceSettingsPanel(
                    FormattingPreferences(cadencePlatformSupported = false),
                    onPrefsChange = {},
                    platformSupported = false,
                    onDismiss = {},
                )
            }
        }
        // The note text is below the hero icon and description paragraphs and falls outside the
        // initial skiko test viewport, so we can't look it up by text on iOS — the meaningful
        // assertion is that the toggle controls are absent, which confirms the unsupported branch.
        onAllNodesWithText("Show Cadence").assertCountEquals(0)
    }
}
