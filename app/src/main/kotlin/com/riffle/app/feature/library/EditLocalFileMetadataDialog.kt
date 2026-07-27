package com.riffle.app.feature.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.riffle.core.models.LibraryItem

@Composable
fun EditLocalFileMetadataDialog(
    item: LibraryItem,
    onSave: (title: String, author: String, seriesName: String, seriesIndex: Double?, coverContentUri: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val itemSeriesName = item.seriesName
    var title by remember { mutableStateOf(item.title) }
    var author by remember { mutableStateOf(item.author) }
    var seriesName by remember {
        mutableStateOf(
            if (itemSeriesName != null && itemSeriesName.contains(" #"))
                itemSeriesName.substringBeforeLast(" #")
            else itemSeriesName ?: ""
        )
    }
    var seriesIndexText by remember {
        mutableStateOf(
            if (itemSeriesName != null && itemSeriesName.contains(" #"))
                itemSeriesName.substringAfterLast(" #")
            else ""
        )
    }
    var pickedCoverUri by remember { mutableStateOf<String?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) pickedCoverUri = uri.toString()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit metadata") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Cover preview with gallery-pick overlay
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.4f)
                        .aspectRatio(2f / 3f)
                        .align(Alignment.CenterHorizontally),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    val coverData: Any? = pickedCoverUri ?: item.coverUrl
                    if (coverData != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(coverData)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize(),
                        )
                    } else {
                        Surface(modifier = Modifier.matchParentSize()) {}
                    }
                    IconButton(
                        onClick = { galleryLauncher.launch("image/*") },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = "Pick cover image")
                    }
                }

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
                                pickedCoverUri,
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
