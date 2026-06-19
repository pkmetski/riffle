package com.riffle.app.feature.audiobook

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards against regressions in the "Bookmark saved" snackbar shown in
 * [AudiobookPlayerScreen] after the user confirms a new bookmark.
 *
 * Note: these tests exercise the snackbar mechanics in isolation (not the full screen) because
 * wiring [AudiobookPlayerScreen] with a fake ViewModel requires disproportionate setup. They
 * verify that the chosen parameters (SnackbarDuration.Long, actionLabel = "Undo") produce the
 * right visible behaviour, but do not protect against accidentally reverting the duration back
 * to Indefinite in the production call site.
 */
@RunWith(AndroidJUnit4::class)
class AudiobookPlayerSnackbarTest {

    @get:Rule val rule = createComposeRule()

    // Shared setup: show the "Bookmark saved" snackbar with the same parameters used in
    // AudiobookPlayerScreen and advance the clock enough for it to appear.
    // Returns the SnackbarHostState so individual tests can drive it further.
    private fun showBookmarkSavedSnackbar(): SnackbarHostState {
        val state = SnackbarHostState()
        rule.mainClock.autoAdvance = false

        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                SnackbarHost(
                    state,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
                )
            }
            LaunchedEffect(Unit) {
                state.showSnackbar(
                    message = "Bookmark saved",
                    actionLabel = "Undo",
                    duration = SnackbarDuration.Long,
                )
            }
        }

        // Allow the LaunchedEffect to start and the snackbar to appear.
        rule.mainClock.advanceTimeBy(100L)
        rule.waitForIdle()
        rule.onNodeWithText("Bookmark saved").assertIsDisplayed()
        return state
    }

    /**
     * The snackbar must auto-dismiss after the Long duration (~10 s).
     *
     * Before the fix it used SnackbarDuration.Indefinite (the Material3 default when
     * actionLabel != null), so it never disappeared. This test would fail with Indefinite
     * because assertDoesNotExist() would still find the node after 11 seconds.
     */
    @Test
    fun bookmarkSavedSnackbarAutoDismissesAfterLongDuration() {
        showBookmarkSavedSnackbar()

        // Advance past SnackbarDuration.Long (10 000 ms).
        rule.mainClock.advanceTimeBy(11_000L)
        rule.waitForIdle()
        rule.onNodeWithText("Bookmark saved").assertDoesNotExist()
    }

    /**
     * Tapping Undo dismisses the snackbar immediately without waiting for the timeout.
     */
    @Test
    fun bookmarkSavedSnackbarDismissesOnUndoTap() {
        val state = showBookmarkSavedSnackbar()

        // Perform the action — equivalent to tapping the Undo button.
        state.currentSnackbarData?.performAction()

        rule.waitForIdle()
        rule.onNodeWithText("Bookmark saved").assertDoesNotExist()
    }
}
