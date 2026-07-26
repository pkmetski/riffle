package com.riffle.app.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.riffle.core.models.LibraryItem

@Composable
fun EditLocalFileMetadataDialog(
    item: LibraryItem,
    onSave: (title: String, author: String, seriesName: String, seriesIndex: Double?) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(item.title) }
    var author by remember { mutableStateOf(item.author) }
    var seriesName by remember { mutableStateOf(item.seriesName ?: "") }
    var seriesIndexText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit metadata") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("Author") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = seriesName,
                    onValueChange = { seriesName = it },
                    label = { Text("Series") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = seriesIndexText,
                    onValueChange = { seriesIndexText = it },
                    label = { Text("Series number") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Row(modifier = Modifier.padding(end = 8.dp, bottom = 4.dp)) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        if (title.isNotBlank()) {
                            onSave(
                                title,
                                author,
                                seriesName,
                                seriesIndexText.toDoubleOrNull(),
                            )
                        }
                    },
                    enabled = title.isNotBlank(),
                ) {
                    Text("Save")
                }
            }
        },
        dismissButton = null,
    )
}
