package com.riffle.feature.reader.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.core.models.EmphasisStyle
import com.riffle.core.models.HighlightColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The annotate surface, driven through the real composable on `iosSimulatorArm64` as well as the
 * JVM.
 *
 * `IosEpubReaderScreen` mounts these exact composables and Android's `HighlightActionsPopup`
 * embeds the same two rows, so this is the one test for both. Before the move, the swatch row
 * and the chip row lived in `:app` and iOS had no way to create or recolour an annotation at
 * all — the `@Composable`s asserted here are that path's only affordance.
 */
class AnnotationActionsSheetTest {

    private val labels = AnnotationSheetLabels.English

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun everyPaletteColourIsOfferedAndReportsItself() = runComposeUiTest {
        val picked = mutableListOf<HighlightColor>()
        setContent {
            HighlightSwatchRow(
                selected = null,
                readerBackground = Color.White,
                labels = labels,
                onPick = { picked += it },
                onPickNone = {},
            )
        }
        HighlightColor.entries.forEach { color ->
            onNodeWithTag("annotation_swatch_${color.token}").assertIsDisplayed()
            onNodeWithTag("annotation_swatch_${color.token}").performClick()
        }
        assertEquals(HighlightColor.entries.toList(), picked)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theNoColourSwatchIsSeparateFromDeletingTheAnnotation() = runComposeUiTest {
        // ADR 0056 §4: `∅` clears the wash and keeps the note and emphasis rows. Wiring it to
        // delete instead would silently discard the user's note.
        var none = 0
        var deleted = 0
        setContent {
            AnnotationActionsSheet(
                selectedColor = HighlightColor.YELLOW,
                emphasisStyles = emptySet(),
                note = null,
                readerBackground = Color.White,
                labels = labels,
                onPickColor = {},
                onRemoveColor = { none++ },
                onToggleEmphasis = {},
                onOpenNoteEditor = {},
                onDelete = { deleted++ },
            )
        }
        onNodeWithTag("annotation_swatch_none").performClick()
        assertEquals(1, none)
        assertEquals(0, deleted, "clearing the colour must not delete the annotation")

        onNodeWithTag("annotation_delete").performClick()
        assertEquals(1, deleted)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun allFourEmphasisStylesAreOfferedAndToggle() = runComposeUiTest {
        val toggled = mutableListOf<EmphasisStyle>()
        setContent {
            EmphasisChipRow(
                selected = setOf(EmphasisStyle.BOLD),
                labels = labels,
                onToggle = { toggled += it },
            )
        }
        EmphasisStyle.entries.forEach { style ->
            onNodeWithTag("annotation_chip_${style.token}").assertIsDisplayed()
            onNodeWithTag("annotation_chip_${style.token}").performClick()
        }
        assertEquals(EmphasisStyle.entries.toList(), toggled)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aFreshSelectionOffersNoDeleteBecauseThereIsNothingToDeleteYet() = runComposeUiTest {
        setContent {
            AnnotationActionsSheet(
                selectedColor = null,
                emphasisStyles = emptySet(),
                note = null,
                readerBackground = Color.White,
                labels = labels,
                onPickColor = {},
                onRemoveColor = {},
                onToggleEmphasis = {},
                onOpenNoteEditor = {},
                onDelete = null,
            )
        }
        onNodeWithTag("annotation_delete").assertDoesNotExist()
        onNodeWithText(labels.addNote).assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun anExistingNoteIsShownInlineRatherThanOnlyBehindTheEditor() = runComposeUiTest {
        // The note row IS the note indicator inside the sheet; a highlight-with-note was
        // otherwise indistinguishable from a plain one on iOS.
        setContent {
            AnnotationActionsSheet(
                selectedColor = HighlightColor.GREEN,
                emphasisStyles = emptySet(),
                note = "remember why this matters",
                readerBackground = Color.White,
                labels = labels,
                onPickColor = {},
                onRemoveColor = {},
                onToggleEmphasis = {},
                onOpenNoteEditor = {},
                onDelete = {},
            )
        }
        onNodeWithText("remember why this matters").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theNoteEditorSavesAndRemoves() = runComposeUiTest {
        val confirmed = mutableListOf<String>()
        setContent {
            NoteEditorSheet(
                initialNote = "an existing note",
                labels = labels,
                onConfirm = { confirmed += it },
                onDismiss = {},
            )
        }
        // An existing note offers Remove in place of Cancel: confirming an empty string is the
        // documented "delete the note" path on the store side.
        onNodeWithTag("note_editor_cancel").assertDoesNotExist()
        onNodeWithTag("note_editor_remove").performClick()
        assertEquals(listOf(""), confirmed)

        onNodeWithTag("note_editor_save").performClick()
        assertEquals("an existing note", confirmed.last())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun anEmptyNoteEditorOffersCancelNotRemove() = runComposeUiTest {
        var dismissed = 0
        setContent {
            NoteEditorSheet(
                initialNote = "",
                labels = labels,
                onConfirm = {},
                onDismiss = { dismissed++ },
            )
        }
        onNodeWithTag("note_editor_remove").assertDoesNotExist()
        onNodeWithTag("note_editor_cancel").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun theEnglishLabelsAreAllPopulated() {
        // The host supplies these; an empty one would render a blank, untappable control and an
        // empty VoiceOver announcement.
        val fields = listOf(
            labels.noHighlightColor, labels.emphasis, labels.note, labels.addNote,
            labels.edit, labels.save, labels.remove, labels.cancel, labels.delete,
            labels.bookmark, labels.removeBookmark,
        )
        assertTrue(fields.none { it.isBlank() }, "blank label in $fields")
    }
}
