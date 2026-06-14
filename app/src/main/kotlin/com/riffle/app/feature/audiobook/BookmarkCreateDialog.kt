package com.riffle.app.feature.audiobook

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
import androidx.compose.ui.unit.dp

/**
 * Stateless dialog for naming a new bookmark (also reused for Rename — see Task 8 — via [title]).
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
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "New bookmark",
) {
    var text by rememberSaveable(initialTitle) { mutableStateOf(initialTitle) }
    AlertDialog(
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
                    modifier = Modifier.fillMaxWidth(),
                )
                val distinctSuggestions = suggestions.distinct()
                if (distinctSuggestions.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    // Manual wrapping rows instead of FlowRow: this project deliberately avoids
                    // FlowRow due to its compose-foundation 1.7 API mismatch (see FormattingPanel).
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
            TextButton(onClick = { onConfirm(text.trim().ifEmpty { initialTitle }) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
