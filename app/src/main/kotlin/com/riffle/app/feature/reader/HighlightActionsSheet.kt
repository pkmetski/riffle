package com.riffle.app.feature.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.riffle.core.models.EmphasisStyle
import com.riffle.core.models.HighlightColor
import com.riffle.feature.reader.ui.AnnotationActionsSheet
import com.riffle.feature.reader.ui.AnnotationSheetLabels
import com.riffle.feature.reader.ui.annotationSheetLabels

@Composable
fun HighlightActionsPopup(
    anchorRect: IntRect,
    selected: HighlightColor?,
    note: String?,
    readerBackground: Color,
    emphasisStyles: Set<EmphasisStyle> = emptySet(),
    onPick: (HighlightColor) -> Unit,
    onRemoveColor: () -> Unit = {},
    onToggleEmphasis: (EmphasisStyle) -> Unit = {},
    onDelete: () -> Unit,
    onOpenNoteEditor: () -> Unit,
    onDismiss: () -> Unit,
    noteOnly: Boolean = false,
    showOpenInBook: Boolean = false,
    onOpenInBook: () -> Unit = {},
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val margin = with(density) { 8.dp.roundToPx() }
    val provider = remember(anchorRect) { HighlightPopupPositionProvider(anchorRect, margin) }
    val sheetLabels = annotationSheetLabels()
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false),
    ) {
        BackHandler(enabled = true, onBack = onDismiss)
        if (noteOnly) {
            NoteOnlyPopup(
                note = note,
                labels = sheetLabels,
                readerBackground = readerBackground,
                onOpenNoteEditor = onOpenNoteEditor,
                onDismiss = currentOnDismiss,
            )
        } else {
            AnnotationActionsSheet(
                selectedColor = selected,
                emphasisStyles = emphasisStyles,
                note = note,
                readerBackground = readerBackground,
                labels = sheetLabels,
                onPickColor = onPick,
                onRemoveColor = onRemoveColor,
                onToggleEmphasis = onToggleEmphasis,
                onOpenNoteEditor = onOpenNoteEditor,
                onDelete = onDelete,
                showOpenInBook = showOpenInBook,
                onOpenInBook = onOpenInBook,
                modifier = Modifier
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(onDragStart = { currentOnDismiss() }) { _, _ -> }
                    },
            )
        }
    }
}

@Composable
private fun NoteOnlyPopup(
    note: String?,
    labels: AnnotationSheetLabels,
    readerBackground: Color,
    onOpenNoteEditor: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 4.dp,
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp).fillMaxWidth()) {
            Text(text = labels.note, style = MaterialTheme.typography.bodyMedium)
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                TextButton(onClick = onOpenNoteEditor) {
                    Text(labels.edit)
                }
            }
        }
    }
}

@Composable
internal fun NoteEditorDialog(
    initialNote: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetLabels = annotationSheetLabels()
    var text by rememberSaveable(initialNote) { mutableStateOf(initialNote) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(sheetLabels.note) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(sheetLabels.addNote) },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text(sheetLabels.save) }
        },
        dismissButton = {
            if (initialNote.isNotBlank()) {
                TextButton(onClick = { onConfirm("") }) {
                    Text(sheetLabels.remove, color = MaterialTheme.colorScheme.error)
                }
            } else {
                TextButton(onClick = onDismiss) { Text(sheetLabels.cancel) }
            }
        },
    )
}
