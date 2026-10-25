package com.riffle.feature.reader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A minimal read-only note card shown when the user taps a note glyph on an annotation that has
 * a note. Displays the note text and an Edit button; no colour swatches. Mirrors the behaviour of
 * Android's `NoteOnlyPopup` in `HighlightActionsSheet.kt`.
 *
 * [readerBackground] is accepted for API symmetry with [AnnotationActionsSheet] but is currently
 * unused — the card renders on the Material surface, not over the reader canvas.
 */
@Composable
fun NoteOnlyPopup(
    note: String?,
    labels: AnnotationSheetLabels,
    readerBackground: Color,
    onOpenNoteEditor: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 4.dp,
        tonalElevation = 0.dp,
        modifier = modifier,
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
