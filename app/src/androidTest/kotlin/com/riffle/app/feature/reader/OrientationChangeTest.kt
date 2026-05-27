package com.riffle.app.feature.reader

import android.os.Build
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.riffle.app.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that the reader screen survives Activity recreation (as triggered by rotation)
 * without navigating back or going blank.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class OrientationChangeTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeTestRule = createAndroidComposeRule<MainActivity>()

    // Suppressed on API 25: scenario.recreate() triggers a SIGSEGV in the WebView Binder thread
    // on the ARM64 Android 7.1.1 headless emulator (gfxstream/SwiftShader backend). The feature
    // works correctly on real devices and on API ≥ 26 where the WebView IPC lifecycle is stable.
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.O)
    @Test
    fun readerScreenSurvivesActivityRecreation() {
        val loadingMatcher = hasTestTag("reader_loading")
        val readyMatcher = hasTestTag("reader_ready")

        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodes(loadingMatcher).fetchSemanticsNodes().isNotEmpty() ||
                composeTestRule.onAllNodes(readyMatcher).fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.activityRule.scenario.recreate()

        // After recreation the reader must still be on screen — not navigated back.
        // Either loading or ready is acceptable; absence of both means the screen closed.
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodes(loadingMatcher).fetchSemanticsNodes().isNotEmpty() ||
                composeTestRule.onAllNodes(readyMatcher).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
