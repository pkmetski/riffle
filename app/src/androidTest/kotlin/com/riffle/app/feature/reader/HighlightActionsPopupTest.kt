package com.riffle.app.feature.reader

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntRect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.core.domain.HighlightColor
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HighlightActionsPopupTest {

    @get:Rule val composeTestRule = createComposeRule()

    private val stubRect = IntRect(100, 200, 300, 220)

    private fun showPopup(
        note: String? = null,
        onOpenNoteEditor: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            HighlightActionsPopup(
                anchorRect = stubRect,
                selected = HighlightColor.YELLOW,
                note = note,
                onPick = {},
                onDelete = {},
                onOpenNoteEditor = onOpenNoteEditor,
                onDismiss = onDismiss,
            )
        }
    }

    @Test
    fun noNote_tapNoteRow_callsOnOpenNoteEditor() {
        var called = false
        showPopup(note = null, onOpenNoteEditor = { called = true })
        composeTestRule.onNodeWithText("Add note").performClick()
        assertTrue("onOpenNoteEditor must be called directly when there is no note", called)
    }

    @Test
    fun existingNote_initialState_showsPreviewAndExpandIcon() {
        showPopup(note = "My detailed note text here")
        composeTestRule.onNodeWithText("Note").assertIsDisplayed()
        composeTestRule.onNodeWithText("My detailed note text here").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Expand note").assertIsDisplayed()
    }

    @Test
    fun existingNote_tapRow_expandsToFullTextAndEditButton() {
        showPopup(note = "My detailed note text here")
        composeTestRule.onNodeWithText("Note").performClick()
        composeTestRule.onNodeWithText("Edit").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Collapse note").assertIsDisplayed()
    }

    @Test
    fun existingNote_expanded_tapEditButton_callsOnOpenNoteEditor() {
        var called = false
        showPopup(note = "Some note", onOpenNoteEditor = { called = true })
        composeTestRule.onNodeWithText("Note").performClick()   // expand
        composeTestRule.onNodeWithText("Edit").performClick()   // open editor
        assertTrue("tapping Edit in expanded state must call onOpenNoteEditor", called)
    }
}
