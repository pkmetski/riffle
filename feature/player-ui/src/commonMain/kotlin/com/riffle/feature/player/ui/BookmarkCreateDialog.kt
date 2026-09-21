package com.riffle.feature.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Stateless dialog for naming a new bookmark (also reused for Rename via [title]).
 *
 * Everything except the local edit-field state is supplied by the caller: the pre-filled
 * [initialTitle], the read-only [positionLabel] (e.g. "1:02:11 · The Conversation"), and the
 * tappable [suggestions] (chapter+offset, chapter, absolute, date — already formatted upstream).
 */
@Composable
fun BookmarkCreateDialog(
    initialTitle: String,
    positionLabel: String,
    suggestions: List<String>,
    labels: PlayerChromeLabels,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = labels.newBookmark,
) {
    var text by rememberSaveable(initialTitle) { mutableStateOf(initialTitle) }
    AlertDialog(
        modifier = Modifier.testTag("bookmark_dialog"),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(positionLabel, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("bookmark_title_field"),
                )
                val distinctSuggestions = suggestions.distinct()
                if (distinctSuggestions.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    // Manual wrapping rows instead of FlowRow: this project deliberately avoids
                    // FlowRow due to its compose-foundation 1.7 API mismatch (see FontChipRow in
                    // ReaderSettingsControls.kt).
                    distinctSuggestions.chunked(2).forEach { rowItems ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowItems.forEach { suggestion ->
                                SuggestionChip(
                                    onClick = { text = suggestion },
                                    label = { Text(suggestion) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim().ifEmpty { initialTitle }) },
                modifier = Modifier.testTag("bookmark_save"),
            ) {
                Text(labels.save)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(labels.cancel) }
        },
    )
}

/**
 * The position line the dialog shows above the name field: `"1:02:11 · The Conversation"`, or just
 * the timestamp when the playhead is not inside a titled chapter.
 */
fun bookmarkPositionLabel(absoluteLabel: String, chapterTitle: String): String =
    if (chapterTitle.isNotEmpty()) "$absoluteLabel · $chapterTitle" else absoluteLabel
