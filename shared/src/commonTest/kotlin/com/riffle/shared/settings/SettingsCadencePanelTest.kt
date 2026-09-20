package com.riffle.shared.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.autoscroll.AutoScrollSpeed
import com.riffle.core.models.HighlightColor
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
 * Runs on `iosSimulatorArm64` as part of `:shared:iosSimulatorArm64Test`.
 */
class SettingsCadencePanelTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun cadencePanelOffersTheToggleTheSpeedStepperAndTheColourChips() = runComposeUiTest {
        setContent {
            Column { CadencePanelContent(FormattingPreferences(), onPrefsChange = {}) }
        }
        onNodeWithTag("panel-toggle-Show cadence toggle in reader").assertIsDisplayed()
        onNodeWithText("${FormattingPreferences().cadenceWpm} WPM").assertIsDisplayed()
        HighlightColor.entries.forEach { color ->
            onNodeWithText(highlightColorLabel(color)).assertIsDisplayed()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theReaderToggleSwitchWritesShowCadence() = runComposeUiTest {
        val writes = mutableListOf<FormattingPreferences>()
        setContent {
            Column {
                CadencePanelContent(
                    FormattingPreferences(showCadence = false),
                    onPrefsChange = { writes += it },
                )
            }
        }
        onNodeWithTag("panel-toggle-Show cadence toggle in reader").performClick()
        assertEquals(1, writes.size)
        assertTrue(writes[0].showCadence)
    }

    /**
     * The stepper goes through `AutoScrollSpeed.of`, the same range and snap rule Cadence's
     * ticker and HUD pill use. The version of this panel that was removed hand-rolled ±10
     * clamped to 50..1000 — both ends outside what the ticker accepts.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theSpeedStepperMovesInAutoScrollSpeedStepsAndClamps() = runComposeUiTest {
        val writes = mutableListOf<FormattingPreferences>()
        setContent {
            Column {
                CadencePanelContent(
                    FormattingPreferences(cadenceWpm = AutoScrollSpeed.MIN_WPM),
                    onPrefsChange = { writes += it },
                )
            }
        }
        onNodeWithText("−").performClick()
        onNodeWithText("+").performClick()
        assertEquals(
            listOf(AutoScrollSpeed.MIN_WPM, AutoScrollSpeed.MIN_WPM + AutoScrollSpeed.STEP_WPM),
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
                CadencePanelContent(
                    FormattingPreferences(cadenceHighlightColor = HighlightColor.YELLOW),
                    onPrefsChange = { writes += it },
                )
            }
        }
        onNodeWithText(highlightColorLabel(HighlightColor.BLUE)).performClick()
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
                CadencePanelContent(
                    FormattingPreferences(cadencePlatformSupported = false),
                    onPrefsChange = {},
                )
            }
        }
        onNodeWithText(CADENCE_UNSUPPORTED_NOTE).assertIsDisplayed()
        onAllNodesWithText("Show cadence toggle in reader").assertCountEquals(0)
    }
}
