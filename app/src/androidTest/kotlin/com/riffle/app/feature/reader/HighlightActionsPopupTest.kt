package com.riffle.app.feature.reader

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntRect
import com.riffle.feature.designsystem.TestTags
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.core.models.HighlightColor
import com.riffle.feature.reader.ui.NoteEditorSheet
import com.riffle.feature.reader.ui.annotationSheetLabels
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
        noteOnly: Boolean = false,
        onOpenNoteEditor: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            HighlightActionsPopup(
                anchorRect = stubRect,
                selected = HighlightColor.YELLOW,
                note = note,
                readerBackground = Color.White,
                onPick = {},
                onDelete = {},
                onOpenNoteEditor = onOpenNoteEditor,
                onDismiss = onDismiss,
                noteOnly = noteOnly,
            )
        }
    }

    @Test
    fun noNote_tapNoteRow_callsOnOpenNoteEditor() {
        // The shared AnnotationActionsSheet uses the addNote label "Add a note…" and the whole
        // note row is clickable — no intermediate expand step.
        var called = false
        showPopup(note = null, onOpenNoteEditor = { called = true })
        composeTestRule.onNodeWithText("Add a note…").performClick()
        assertTrue("onOpenNoteEditor must be called directly when there is no note", called)
    }

    @Test
    fun existingNote_initialState_showsPreviewText() {
        // The shared AnnotationActionsSheet shows a compact note preview inline (maxLines = 2),
        // without expand/collapse icons. The retired expand/collapse behavior lived only in the
        // old HighlightActionsSheet private copy.
        showPopup(note = "My detailed note text here")
        composeTestRule.onNodeWithText("Note").assertIsDisplayed()
        composeTestRule.onNodeWithText("My detailed note text here").assertIsDisplayed()
    }

    @Test
    fun existingNote_tapRow_callsOnOpenNoteEditor() {
        // The shared AnnotationActionsSheet makes the whole note row directly open the editor —
        // no expand step. The retired expand/collapse "Edit" button lived only in the old private
        // copy of HighlightActionsSheet.
        var called = false
        showPopup(note = "My detailed note text here", onOpenNoteEditor = { called = true })
        composeTestRule.onNodeWithText("Note").performClick()
        assertTrue("tapping the note row must call onOpenNoteEditor directly", called)
    }

    @Test
    fun existingNote_expanded_tapEditButton_callsOnOpenNoteEditor() {
        var called = false
        showPopup(note = "Some note", onOpenNoteEditor = { called = true })
        composeTestRule.onNodeWithText("Note").performClick()   // expand
        composeTestRule.onNodeWithText("Edit").performClick()   // open editor
        assertTrue("tapping Edit in expanded state must call onOpenNoteEditor", called)
    }

    @Test
    fun noteOnly_hidesColorRowAndShowsNoteDirectly() {
        showPopup(note = "My note", noteOnly = true)
        composeTestRule.onNodeWithText("Note").assertIsDisplayed()
        composeTestRule.onNodeWithText("My note").assertIsDisplayed()
        composeTestRule.onNodeWithText("Edit").assertIsDisplayed()
        // Colour swatches and delete must not be visible in note-only mode
        composeTestRule.onNodeWithContentDescription("Delete highlight").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Yellow highlight, selected").assertDoesNotExist()
    }

    @Test
    fun noteOnly_tapEditButton_callsOnOpenNoteEditor() {
        var called = false
        showPopup(note = "My note", noteOnly = true, onOpenNoteEditor = { called = true })
        composeTestRule.onNodeWithText("Edit").performClick()
        assertTrue("Edit in note-only mode must call onOpenNoteEditor", called)
    }

    @Test
    fun noteOnly_nullNote_showsLabelButNoEditButton() {
        // Race: glyph decoration still visible in WebView while note was just deleted in Kotlin state.
        // The popup must not render an orphaned "Edit" button with no note content.
        showPopup(note = null, noteOnly = true)
        composeTestRule.onNodeWithText("Note").assertIsDisplayed()
        composeTestRule.onNodeWithText("Edit").assertDoesNotExist()
    }

    @Test
    fun popup_widthIsAtMost360dp() {
        showPopup(note = "A short note")
        val bounds = composeTestRule.onNodeWithTag(TestTags.ANNOTATION_NOTE_ROW).getBoundsInRoot()
        val widthDp = (bounds.right - bounds.left).value
        assertTrue("annotation popup note row width must be at most 300dp, was $widthDp", widthDp <= 300f)
    }

    @Test
    fun noteEditor_autofocusesTextFieldWhenOpened() {
        composeTestRule.setContent {
            NoteEditorSheet(
                initialNote = "",
                labels = annotationSheetLabels(),
                onConfirm = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNode(hasSetTextAction()).assertIsFocused()
    }
}
