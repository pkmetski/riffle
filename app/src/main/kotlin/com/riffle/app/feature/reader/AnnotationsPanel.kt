package com.riffle.app.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.riffle.app.ui.fadingScrollbar
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.Annotation
import com.riffle.core.domain.HighlightColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotationsPanel(
    annotations: List<Annotation>,
    onNavigate: (id: String) -> Unit,
    onDelete: (id: String) -> Unit,
    onRename: (id: String, title: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var renamingId by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.fillMaxHeight()) {
            Text(
                text = "Annotations",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
            if (annotations.isEmpty()) {
                Text(
                    text = "No annotations yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            } else {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().fadingScrollbar(listState),
                ) {
                    items(annotations, key = { it.id }) { annotation ->
                        AnnotationRow(
                            annotation = annotation,
                            onClick = { onNavigate(annotation.id) },
                            onDelete = { onDelete(annotation.id) },
                            onRename = { renamingId = annotation.id },
                        )
                    }
                }
            }
        }
    }

    renamingId?.let { id ->
        val currentTitle = annotations.firstOrNull { it.id == id }?.bookmarkTitle ?: ""
        BookmarkRenameDialog(
            initialTitle = currentTitle,
            onConfirm = { newTitle ->
                onRename(id, newTitle)
                renamingId = null
            },
            onDismiss = { renamingId = null },
        )
    }
}

/**
 * Which visual variant an [AnnotationRow] renders, derived from [Annotation.type].
 */
internal enum class RowKind { Bookmark, Highlight, Image }

/**
 * Pure type→row-variant selector, extracted so the routing decision is unit-testable without
 * standing up Compose. Unknown/legacy types fall back to [RowKind.Highlight].
 */
internal fun rowKindFor(annotation: Annotation): RowKind = when (annotation.type) {
    AnnotationEntity.TYPE_BOOKMARK -> RowKind.Bookmark
    AnnotationEntity.TYPE_HIGHLIGHT -> RowKind.Highlight
    AnnotationEntity.TYPE_IMAGE -> RowKind.Image
    else -> RowKind.Highlight
}

@Composable
private fun AnnotationRow(
    annotation: Annotation,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
) {
    val rowKind = rowKindFor(annotation)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier.size(
                width = if (rowKind == RowKind.Image) 96.dp else 32.dp,
                height = if (rowKind == RowKind.Image) 80.dp else 32.dp,
            ),
            contentAlignment = Alignment.Center,
        ) {
            when (rowKind) {
                RowKind.Bookmark -> Icon(
                    Icons.Filled.Bookmark,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RowKind.Image -> {
                    val highlightColor = HighlightColor.fromToken(annotation.color)
                    val borderColor = Color(highlightColor.argb.toLong() and 0xFFFFFFFFL)
                    val bytesUri = annotation.imageBytes
                    val bitmap = remember(bytesUri) { decodeImageDataUri(bytesUri) }
                    if (bitmap != null) {
                        ComposeImage(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(6.dp))
                                .border(2.dp, borderColor, RoundedCornerShape(6.dp)),
                        )
                    } else {
                        Icon(
                            Icons.Filled.Image,
                            contentDescription = null,
                            tint = borderColor,
                            modifier = Modifier.size(48.dp),
                        )
                    }
                }
                RowKind.Highlight -> {
                    val highlightColor = HighlightColor.fromToken(annotation.color)
                    Surface(
                        shape = CircleShape,
                        color = Color(highlightColor.argb.toLong() and 0xFFFFFFFFL),
                        modifier = Modifier.size(16.dp),
                    ) {}
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            val title = if (rowKind == RowKind.Bookmark) {
                annotation.bookmarkTitle.ifBlank { "Bookmark" }
            } else {
                annotation.textSnippet
            }
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = maxLinesForAnnotationTitle(annotation.type),
                overflow = TextOverflow.Ellipsis,
            )
            val note = annotation.note
            if (rowKind == RowKind.Highlight && !note.isNullOrBlank()) {
                Text(
                    text = note.take(60),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        AnnotationOverflow(
            isBookmark = rowKind == RowKind.Bookmark,
            onDelete = onDelete,
            onRename = onRename,
        )
    }
}

@Composable
private fun AnnotationOverflow(
    isBookmark: Boolean,
    onDelete: () -> Unit,
    onRename: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Options")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (isBookmark) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onRename()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Delete") },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

internal const val BOOKMARK_TITLE_MAX_LINES = 2
internal const val HIGHLIGHT_SNIPPET_MAX_LINES = 6

internal fun maxLinesForAnnotationTitle(type: String): Int =
    if (type == AnnotationEntity.TYPE_BOOKMARK) BOOKMARK_TITLE_MAX_LINES else HIGHLIGHT_SNIPPET_MAX_LINES

/**
 * Decode a `data:image/…;base64,…` URI into a [android.graphics.Bitmap]. Null on malformed input
 * or a non-data-URI value. Used by the annotations panel to display a `TYPE_IMAGE` annotation's
 * captured thumbnail and by the Highlights-mode elided reader to render the full-size figure.
 */
internal fun decodeImageDataUri(dataUri: String?): android.graphics.Bitmap? {
    if (dataUri.isNullOrBlank()) return null
    val comma = dataUri.indexOf(',')
    if (comma < 0 || !dataUri.startsWith("data:")) return null
    return runCatching {
        val bytes = Base64.decode(dataUri.substring(comma + 1), Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}

@Composable
private fun BookmarkRenameDialog(
    initialTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename bookmark") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            val trimmed = title.trim()
            TextButton(
                onClick = { onConfirm(trimmed) },
                enabled = trimmed.isNotEmpty(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
