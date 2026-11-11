package com.riffle.feature.library.ui

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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.riffle.core.models.LibraryItem
import com.riffle.feature.designsystem.generated.resources.ui_cancel
import com.riffle.feature.designsystem.generated.resources.ui_save
import com.riffle.feature.designsystem.generated.resources.ui_series
import com.riffle.feature.library.ui.generated.resources.Res
import com.riffle.feature.library.ui.generated.resources.ui_author
import com.riffle.feature.library.ui.generated.resources.ui_edit_metadata
import com.riffle.feature.library.ui.generated.resources.ui_pick_cover_image
import com.riffle.feature.library.ui.generated.resources.ui_restore_from_file_metadata
import com.riffle.feature.library.ui.generated.resources.ui_series_number
import com.riffle.feature.library.ui.generated.resources.ui_title
import org.jetbrains.compose.resources.stringResource
import com.riffle.feature.designsystem.generated.resources.Res as DsRes

/**
 * Shared dialog for editing a local-file book's metadata.
 *
 * [onPickCover] is a platform-specific callback: the host (Android: gallery launcher; iOS:
 * PHPickerViewController) calls the supplied [onResult] with a stable file:// or content:// URI
 * string when the user picks an image. Coil 3 on both platforms can load these strings directly.
 */
@Composable
fun EditLocalFileMetadataDialog(
    item: LibraryItem,
    originalItem: LibraryItem?,
    onPickCover: (onResult: (String) -> Unit) -> Unit,
    onSave: (title: String, author: String, seriesName: String, seriesIndex: Double?, coverPath: String?, clearCoverOverride: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    fun String.seriesBaseName() = if (contains(" #")) substringBeforeLast(" #") else this
    fun String.seriesNumber() = if (contains(" #")) substringAfterLast(" #") else ""

    val itemSeriesName = item.seriesName
    var title by remember { mutableStateOf(item.title) }
    var author by remember { mutableStateOf(item.author) }
    var seriesName by remember { mutableStateOf(itemSeriesName?.seriesBaseName() ?: "") }
    var seriesIndexText by remember { mutableStateOf(itemSeriesName?.seriesNumber() ?: "") }
    var pickedCoverPath by remember { mutableStateOf<String?>(null) }
    var clearCoverOverride by remember { mutableStateOf(false) }
    var displayCoverUrl by remember { mutableStateOf<String?>(item.coverUrl) }

    fun restoreFromMetadata() {
        val orig = originalItem ?: return
        val origSeriesName = orig.seriesName
        title = orig.title
        author = orig.author
        seriesName = origSeriesName?.seriesBaseName() ?: ""
        seriesIndexText = origSeriesName?.seriesNumber() ?: ""
        pickedCoverPath = null
        clearCoverOverride = true
        displayCoverUrl = orig.coverUrl
    }

    val platformContext = LocalPlatformContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.ui_edit_metadata)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.4f)
                        .aspectRatio(2f / 3f)
                        .align(Alignment.CenterHorizontally),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    if (displayCoverUrl != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(platformContext)
                                .data(displayCoverUrl)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize(),
                        )
                    } else {
                        Surface(modifier = Modifier.matchParentSize()) {}
                    }
                    IconButton(
                        onClick = {
                            onPickCover { path ->
                                pickedCoverPath = path
                                clearCoverOverride = false
                                displayCoverUrl = path
                            }
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(LibraryUiGlyphs.Edit, contentDescription = stringResource(Res.string.ui_pick_cover_image))
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(Res.string.ui_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text(stringResource(Res.string.ui_author)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = seriesName,
                    onValueChange = { seriesName = it },
                    label = { Text(stringResource(DsRes.string.ui_series)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = seriesIndexText,
                    onValueChange = { seriesIndexText = it },
                    label = { Text(stringResource(Res.string.ui_series_number)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (originalItem != null) {
                    TextButton(
                        onClick = { restoreFromMetadata() },
                        modifier = Modifier.align(Alignment.Start),
                    ) {
                        Text(stringResource(Res.string.ui_restore_from_file_metadata))
                    }
                }
            }
        },
        confirmButton = {
            Row(modifier = Modifier.padding(end = 8.dp, bottom = 4.dp)) {
                TextButton(onClick = onDismiss) { Text(stringResource(DsRes.string.ui_cancel)) }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        if (title.isNotBlank()) {
                            onSave(
                                title,
                                author,
                                seriesName,
                                seriesIndexText.toDoubleOrNull(),
                                pickedCoverPath,
                                clearCoverOverride,
                            )
                        }
                    },
                    enabled = title.isNotBlank(),
                ) {
                    Text(stringResource(DsRes.string.ui_save))
                }
            }
        },
        dismissButton = null,
    )
}
