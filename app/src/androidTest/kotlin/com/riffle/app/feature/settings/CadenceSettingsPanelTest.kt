package com.riffle.app.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.app.feature.settings.panels.AutoScrollSettingsPanel
import com.riffle.app.feature.settings.panels.CadenceSettingsPanel
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.HighlightColor
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * JVM-close Compose tests for the two Reading drill-ins added by issue #403. Verifies:
 *  - both panels render their About blurb, Show toggle, and Default speed slider
 *  - Cadence's drill-in additionally renders a highlight-colour picker
 *  - toggle interactions produce the expected FormattingPreferences deltas
 *  - the Cadence panel degrades gracefully when the WebView `Intl.Segmenter` gate reports
 *    unsupported — the toggles hide and a "not available" note is shown
 *
 * Phone form factor (no @TabletLayout).
 */
@RunWith(AndroidJUnit4::class)
class CadenceSettingsPanelTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun cadence_panel_renders_hero_about_toggle_speed_and_color() {
        composeTestRule.setContent {
            CadenceSettingsPanel(
                prefs = FormattingPreferences(),
                onPrefsChange = {},
                onDismiss = {},
            )
        }
        composeTestRule.onNodeWithText("Cadence").assertIsDisplayed()
        // About blurb start.
        composeTestRule.onNodeWithText("Cadence highlights one sentence at a time", substring = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Show Cadence").assertIsDisplayed()
        composeTestRule.onNodeWithText("Default speed", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Highlight color").assertIsDisplayed()
        // The four palette swatches all render with distinct content descriptions.
        composeTestRule.onNodeWithContentDescription("Yellow highlight, selected").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Green highlight").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Blue highlight").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Red highlight").assertIsDisplayed()
    }

    @Test
    fun cadence_panel_toggle_off_emits_showCadence_false() {
        // Regression: the Show Cadence toggle must round-trip to onPrefsChange. If a future
        // refactor accidentally omits `.copy(showCadence = ...)`, the switch flips visibly but
        // the persisted preference stays stale — and Cadence would still show its top-bar icon
        // after the user disabled it.
        var latest: FormattingPreferences? = null
        composeTestRule.setContent {
            CadenceSettingsPanel(
                prefs = FormattingPreferences(showCadence = true),
                onPrefsChange = { latest = it },
                onDismiss = {},
            )
        }
        composeTestRule.onNodeWithText("Show Cadence").performClick()
        assertEquals(false, latest?.showCadence)
    }

    @Test
    fun cadence_panel_color_swatch_click_emits_selected_color() {
        var latest: FormattingPreferences? = null
        composeTestRule.setContent {
            CadenceSettingsPanel(
                prefs = FormattingPreferences(cadenceHighlightColor = HighlightColor.YELLOW),
                onPrefsChange = { latest = it },
                onDismiss = {},
            )
        }
        composeTestRule.onNodeWithContentDescription("Blue highlight").performClick()
        assertEquals(HighlightColor.BLUE, latest?.cadenceHighlightColor)
    }

    @Test
    fun cadence_panel_platformUnsupported_hides_toggles_and_shows_note() {
        // Regression: when the WebView reports no Intl.Segmenter support, the panel body must
        // hide the toggle + slider + color picker and show a "not available on this WebView"
        // note. Without this fallback, the user could enable Cadence via the toggle on a device
        // where it can't work, then get no toggle in the reader and no explanation why.
        composeTestRule.setContent {
            CadenceSettingsPanel(
                prefs = FormattingPreferences(),
                onPrefsChange = {},
                platformSupported = false,
                onDismiss = {},
            )
        }
        composeTestRule.onNodeWithText("Cadence isn't available on this device's WebView", substring = true)
            .assertIsDisplayed()
        // The toggle + slider + color picker do NOT render.
        composeTestRule.onAllNodesWithText("Show Cadence").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Default speed", substring = true).assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Highlight color").assertCountEquals(0)
    }

    @Test
    fun autoScroll_panel_renders_hero_about_toggle_and_speed_but_no_color_picker() {
        composeTestRule.setContent {
            AutoScrollSettingsPanel(
                prefs = FormattingPreferences(),
                onPrefsChange = {},
                onDismiss = {},
            )
        }
        composeTestRule.onNodeWithText("Auto-Scroll").assertIsDisplayed()
        composeTestRule.onNodeWithText("Auto-Scroll creeps the page upward", substring = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Show Auto-Scroll").assertIsDisplayed()
        composeTestRule.onNodeWithText("Default speed", substring = true).assertIsDisplayed()
        // Auto-Scroll doesn't get a highlight-color picker (Cadence-only per the ticket).
        composeTestRule.onAllNodesWithText("Highlight color").assertCountEquals(0)
    }

    @Test
    fun autoScroll_panel_toggle_off_emits_showAutoScroll_false() {
        var latest: FormattingPreferences? = null
        composeTestRule.setContent {
            AutoScrollSettingsPanel(
                prefs = FormattingPreferences(showAutoScroll = true),
                onPrefsChange = { latest = it },
                onDismiss = {},
            )
        }
        composeTestRule.onNodeWithText("Show Auto-Scroll").performClick()
        assertEquals(false, latest?.showAutoScroll)
    }
}
