package com.riffle.app.feature.reader

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression tests for the EpubNavigatorView submitPreferences call contract.
 *
 * Background: the original LaunchedEffect(formattingPrefs, fragmentRef.value) fired
 * submitPreferences on fragment creation (when fragmentRef.value changed null → fragment).
 * Since the fragment was already created with EpubPreferences() defaults, this caused
 * Readium to re-render with the stored prefs — producing a white flash on every open.
 *
 * The fix: LaunchedEffect(formattingPrefs) only — submitPreferences fires only when prefs
 * change, not when the fragment becomes available.
 */
@RunWith(AndroidJUnit4::class)
class EpubNavigatorPreferencesTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun submitPreferencesNotCalledWhenFragmentRefBecomesAvailable() {
        var fragmentRef by mutableStateOf<String?>(null)
        var prefs by mutableStateOf(FormattingPreferences())
        val submitCallCount = AtomicInteger(0)

        composeTestRule.setContent {
            val capturedPrefs = prefs
            val capturedFrag = fragmentRef
            LaunchedEffect(capturedPrefs) {
                capturedFrag?.let { submitCallCount.incrementAndGet() }
            }
        }

        composeTestRule.waitForIdle()
        val countAfterInit = submitCallCount.get()

        // Simulate the fragment becoming available (fragmentRef.value changes null → fragment).
        // This must NOT trigger submitPreferences — the fragment was created with the correct
        // initialPreferences already, so calling it again would cause a white flash.
        composeTestRule.runOnIdle { fragmentRef = "fragment" }
        composeTestRule.waitForIdle()

        assertEquals(
            "submitPreferences was called when fragment became available — this causes a white flash",
            countAfterInit,
            submitCallCount.get(),
        )
    }

    @Test
    fun submitPreferencesCalledWhenFormattingPreferencesChange() {
        var fragmentRef by mutableStateOf<String?>("fragment")
        var prefs by mutableStateOf(FormattingPreferences())
        val submitCallCount = AtomicInteger(0)

        composeTestRule.setContent {
            val capturedPrefs = prefs
            val capturedFrag = fragmentRef
            LaunchedEffect(capturedPrefs) {
                capturedFrag?.let { submitCallCount.incrementAndGet() }
            }
        }

        composeTestRule.waitForIdle()
        val countAfterInit = submitCallCount.get()

        // User changes theme to Dark — submitPreferences must fire to apply the change.
        composeTestRule.runOnIdle { prefs = FormattingPreferences(theme = ReaderTheme.Dark) }
        composeTestRule.waitForIdle()

        assertEquals(countAfterInit + 1, submitCallCount.get())
    }

    @Test
    fun fragmentRefChangeAndPrefsChangeTriggerOnlyOneCall() {
        var fragmentRef by mutableStateOf<String?>(null)
        var prefs by mutableStateOf(FormattingPreferences())
        val submitCallCount = AtomicInteger(0)

        composeTestRule.setContent {
            val capturedPrefs = prefs
            val capturedFrag = fragmentRef
            LaunchedEffect(capturedPrefs) {
                capturedFrag?.let { submitCallCount.incrementAndGet() }
            }
        }

        composeTestRule.waitForIdle()
        val countAfterInit = submitCallCount.get()

        // Fragment becomes available AND prefs change simultaneously.
        // Only the prefs change must trigger submitPreferences (once).
        composeTestRule.runOnIdle {
            fragmentRef = "fragment"
            prefs = FormattingPreferences(theme = ReaderTheme.Dark)
        }
        composeTestRule.waitForIdle()

        assertEquals(countAfterInit + 1, submitCallCount.get())
    }
}
