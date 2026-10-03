package com.riffle.feature.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import com.riffle.core.models.Annotation
import com.riffle.core.models.HighlightColor
import com.riffle.feature.designsystem.RiffleIcons
import com.riffle.feature.reader.RowKind
import com.riffle.feature.reader.collectInlineFigures
import com.riffle.feature.reader.maxLinesForAnnotationTitle
import com.riffle.feature.reader.rowKindFor
import com.riffle.feature.reader.ui.generated.resources.Res
import com.riffle.feature.reader.ui.generated.resources.ui_annotations
import com.riffle.feature.reader.ui.generated.resources.ui_cancel
import com.riffle.feature.reader.ui.generated.resources.ui_delete
import com.riffle.feature.reader.ui.generated.resources.ui_no_annotations_yet
import com.riffle.feature.reader.ui.generated.resources.ui_options
import com.riffle.feature.reader.ui.generated.resources.ui_rename
import com.riffle.feature.reader.ui.generated.resources.ui_rename_bookmark
import com.riffle.feature.reader.ui.generated.resources.ui_save
import com.riffle.feature.source.ui.fadingScrollbar
import org.jetbrains.compose.resources.stringResource

/**
 * Shared annotations panel. Android and iOS both render this; Android passes its
 * [figureContent] implementation backed by [android.graphics.BitmapFactory]; iOS passes
 * one backed by its own image-loading API.
 *
 * Figure thumbnails are rendered via [figureContent] to keep this composable platform-free.
 * Pass a no-op lambda when thumbnail rendering is not required.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedAnnotationsPanel(
    annotations: List<Annotation>,
    onNavigate: (id: String) -> Unit,
    onDelete: (id: String) -> Unit,
    onRename: (id: String, title: String) -> Unit,
    onDismiss: () -> Unit,
    figureContent: @Composable (dataUri: String, caption: String, modifier: Modifier) -> Unit =
        { _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    var renamingId by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxHeight()) {
            Text(
                text = stringResource(Res.string.ui_annotations),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
            if (annotations.isEmpty()) {
                Text(
                    text = stringResource(Res.string.ui_no_annotations_yet),
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
                        SharedAnnotationRow(
                            annotation = annotation,
                            figureContent = figureContent,
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
        SharedBookmarkRenameDialog(
            initialTitle = currentTitle,
            onConfirm = { newTitle ->
                onRename(id, newTitle)
                renamingId = null
            },
            onDismiss = { renamingId = null },
        )
    }
}

@Composable
private fun SharedAnnotationRow(
    annotation: Annotation,
    figureContent: @Composable (dataUri: String, caption: String, modifier: Modifier) -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
) {
    val rowKind = rowKindFor(annotation)
    val leadingAlignment = if (rowKind == RowKind.Bookmark) Alignment.Top else Alignment.CenterVertically
    Row(
        verticalAlignment = leadingAlignment,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier.size(width = 32.dp, height = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (rowKind) {
                RowKind.Bookmark -> Icon(
                    RiffleIcons.BookmarkSingle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RowKind.Image, RowKind.Highlight -> {
                    if (annotation.color.isBlank()) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Transparent,
                            border = androidx.compose.foundation.BorderStroke(
                                width = 1.5.dp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            modifier = Modifier.size(16.dp),
                        ) {}
                    } else {
                        val highlightColor = HighlightColor.fromToken(annotation.color)
                        Surface(
                            shape = CircleShape,
                            color = Color(highlightColor.argb),
                            modifier = Modifier.size(16.dp),
                        ) {}
                    }
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            SharedAnnotationContent(
                annotation = annotation,
                rowKind = rowKind,
                figureContent = figureContent,
            )
        }
        Spacer(Modifier.width(16.dp))
        SharedAnnotationOverflow(
            isBookmark = rowKind == RowKind.Bookmark,
            onDelete = onDelete,
            onRename = onRename,
        )
    }
}

@Composable
private fun SharedAnnotationContent(
    annotation: Annotation,
    rowKind: RowKind,
    figureContent: @Composable (dataUri: String, caption: String, modifier: Modifier) -> Unit,
) {
    if (rowKind == RowKind.Bookmark) {
        val title = annotation.bookmarkTitle.ifBlank { "Bookmark" }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = maxLinesForAnnotationTitle(annotation.type),
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    Text(
        text = annotation.textSnippet,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = maxLinesForAnnotationTitle(annotation.type),
        overflow = TextOverflow.Ellipsis,
    )
    // Render figure thumbnails if available
    if (rowKind == RowKind.Image) {
        val figures = collectInlineFigures(annotation)
        figures.forEach { fig ->
            Spacer(Modifier.size(6.dp))
            figureContent(fig.bytesUri, fig.caption, Modifier.fillMaxWidth())
        }
    }
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

@Composable
private fun SharedAnnotationOverflow(
    isBookmark: Boolean,
    onDelete: () -> Unit,
    onRename: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                RiffleIcons.MoreVert,
                contentDescription = stringResource(Res.string.ui_options),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (isBookmark) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.ui_rename)) },
                    leadingIcon = { Icon(RiffleIcons.Edit, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onRename()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.ui_delete)) },
                leadingIcon = { Icon(RiffleIcons.Delete, contentDescription = null) },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun SharedBookmarkRenameDialog(
    initialTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.ui_rename_bookmark)) },
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
            ) { Text(stringResource(Res.string.ui_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.ui_cancel)) }
        },
    )
}
