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
import com.riffle.core.models.Annotation
import com.riffle.core.models.HighlightColor

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
    // A HIGHLIGHT whose selection enclosed a figure with captured bytes gets the Image row so
    // the panel shows the figure thumbnail — same visual weight as a standalone TYPE_IMAGE. The
    // text snippet still renders alongside via the row's title column. Highlights without a
    // captured figure (or figures without bytes) fall back to the plain color-dot row.
    AnnotationEntity.TYPE_HIGHLIGHT ->
        if (annotation.embeddedFigures.orEmpty().any { !it.imageBytes.isNullOrBlank() })
            RowKind.Image else RowKind.Highlight
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
    // Bookmarks stay top-aligned (single-line title next to the icon); highlights + image rows
    // centre vertically so a multi-line annotation (esp. text-figure-text) puts the colour dot
    // in the middle of the card instead of pinned to the first line (fix 2026-07-10).
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
                    Icons.Filled.Bookmark,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Image and Highlight rows share the same colour dot marker in the leading slot —
                // fix #2 (2026-07-09): the figure thumbnail is no longer rendered on the left, it
                // now sits inline between the text runs in the content column (see below), matching
                // how the book itself lays out prose around a figure.
                RowKind.Image, RowKind.Highlight -> {
                    // ADR 0046 §4: format-only highlight anchors (color="") represent a
                    // "just-format this text" annotation. HighlightColor.fromToken("") falls
                    // back to YELLOW, which used to make these rows look like plain yellow
                    // highlights in the panel — a real user complaint. Render a hollow /
                    // outlined dot for the color-less case instead, so the row is still visible
                    // but visually distinct from an actual coloured highlight.
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
                            color = Color(highlightColor.argb.toLong() and 0xFFFFFFFFL),
                            modifier = Modifier.size(16.dp),
                        ) {}
                    }
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            AnnotationContent(annotation = annotation, rowKind = rowKind)
        }
        Spacer(Modifier.width(16.dp))
        AnnotationOverflow(
            isBookmark = rowKind == RowKind.Bookmark,
            onDelete = onDelete,
            onRename = onRename,
        )
    }
}

/**
 * Content column of an annotation row. For rows that carry a figure ([RowKind.Image]), the figure
 * is rendered INLINE between the text runs — mirroring how the graph sits between paragraphs in
 * the source book (fix #2, 2026-07-09). Since [EmbeddedFigure] doesn't record where within the
 * highlighted range each figure sat (v1 approximation — see HighlightsPublicationFactory's class
 * KDoc), we split the highlight's snippet at newline boundaries and interleave figures at those
 * gaps in `order` sequence. When no boundaries exist, figures render after the snippet as a
 * fallback — the standalone TYPE_IMAGE row hits this fallback intentionally (image over caption).
 */
@Composable
private fun AnnotationContent(annotation: Annotation, rowKind: RowKind) {
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
    val highlightColor = HighlightColor.fromToken(annotation.color)
    val figureBorderColor = Color(highlightColor.argb.toLong() and 0xFFFFFFFFL)
    val figures: List<InlineFigure> = when (rowKind) {
        RowKind.Image -> collectInlineFigures(annotation)
        else -> emptyList()
    }
    if (figures.isEmpty()) {
        Text(
            text = annotation.textSnippet,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = maxLinesForAnnotationTitle(annotation.type),
            overflow = TextOverflow.Ellipsis,
        )
    } else {
        val textChunks = splitSnippetForFiguresAt(
            snippet = annotation.textSnippet,
            offsets = figures.map { it.charOffset },
        )
        textChunks.forEachIndexed { index, chunk ->
            // Readium's textSnippet embeds "\n\n\n" between paragraphs on either side of a void
            // figure to represent the block break. Compose's Text renders those newlines as
            // blank lines, producing a wide gap in the annotations-list card (bug 2026-07-10).
            // Trim ONLY leading/trailing whitespace runs — an intentional internal line-break
            // still renders as a single visible break.
            val trimmed = chunk.trim()
            if (trimmed.isNotEmpty()) {
                Text(
                    text = trimmed,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.size(6.dp))
            }
            figures.getOrNull(index)?.let { figure ->
                InlineFigureImage(figure = figure, borderColor = figureBorderColor)
                Spacer(Modifier.size(6.dp))
            }
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

/** A figure to render inline in an annotation row — decoded bitmap or the caption fallback. */
private data class InlineFigure(
    val bytesUri: String?,
    val caption: String,
    /** Position within the highlight's snippet (see [EmbeddedFigure.charOffset]); null → append. */
    val charOffset: Long?,
)

private fun collectInlineFigures(annotation: Annotation): List<InlineFigure> {
    val fromEmbedded = annotation.embeddedFigures
        ?.sortedBy { it.order }
        ?.mapNotNull { fig ->
            val bytes = fig.imageBytes
            if (!bytes.isNullOrBlank()) InlineFigure(bytes, fig.caption, fig.charOffset) else null
        }
        ?: emptyList()
    if (fromEmbedded.isNotEmpty()) return fromEmbedded
    // Standalone TYPE_IMAGE — its own imageBytes drives the single inline figure.
    val bytes = annotation.imageBytes
    return if (!bytes.isNullOrBlank()) listOf(InlineFigure(bytes, "", charOffset = null)) else emptyList()
}

/**
 * Splits the highlight's [snippet] into [figureCount] + 1 chunks so figures can be interleaved
 * between them. When [snippet] contains one or more newlines, splits at those boundaries (matching
 * paragraph breaks Readium captured in the concatenated snippet). Otherwise emits the whole
 * snippet followed by empty chunks — figures render after the text, which is the best v1 can do
 * without character-offset metadata on [EmbeddedFigure] (see class KDoc in
 * `HighlightsPublicationFactory`). Blank chunks are filtered out at render time.
 */
/**
 * Splits [snippet] at each figure's [offsets] (see [EmbeddedFigure.charOffset]) so the returned
 * `offsets.size + 1` chunks can be interleaved with figures in DOM order. Any null entry in
 * [offsets] falls back to the heuristic in [splitSnippetForFigures] — figures at the end. Offsets
 * are clamped into the snippet's char range and de-duplicated in order; a figure at offset 0 gets
 * an empty leading chunk (figure renders first, then all text).
 *
 * The snippet's char index space matches Readium's captured `text.highlight`, which concatenates
 * readable text nodes verbatim (a void `<img>` contributes zero chars, exactly matching
 * `findEnclosedFiguresInHtml`'s counter). So an offset captured from that walk lands at the same
 * position in the snippet — no reshaping required.
 */
internal fun splitSnippetForFiguresAt(snippet: String, offsets: List<Long?>): List<String> {
    if (offsets.isEmpty()) return listOf(snippet)
    if (offsets.all { it == null }) return splitSnippetForFigures(snippet, offsets.size)
    val chunks = mutableListOf<String>()
    var cursor = 0
    val maxLen = snippet.length
    var lastOffset = 0
    for (offset in offsets) {
        val clamped = when (offset) {
            null -> maxLen
            else -> offset.toInt().coerceIn(lastOffset, maxLen)
        }
        chunks += snippet.substring(cursor, clamped)
        cursor = clamped
        lastOffset = clamped
    }
    chunks += snippet.substring(cursor, maxLen)
    return chunks
}

internal fun splitSnippetForFigures(snippet: String, figureCount: Int): List<String> {
    if (figureCount <= 0) return listOf(snippet)
    val parts = snippet.split('\n').filter { it.isNotBlank() }
    return when {
        parts.size >= figureCount + 1 -> {
            // More paragraphs than needed splits — merge the trailing extras into the last chunk.
            parts.take(figureCount) + listOf(parts.drop(figureCount).joinToString("\n"))
        }
        parts.isEmpty() -> listOf(snippet) + List(figureCount) { "" }
        else -> parts + List(figureCount + 1 - parts.size) { "" }
    }
}

@Composable
private fun InlineFigureImage(figure: InlineFigure, borderColor: Color) {
    val bitmap = remember(figure.bytesUri) {
        // Panel thumbnails render at panel width (< 1000 CSS px on any device); cap at 1024 so
        // a captured 4000x3000 figure doesn't allocate ~48 MB per row while the user scrolls.
        decodeImageDataUri(figure.bytesUri, reqDimensionPx = 1024)
    }
    if (bitmap != null) {
        ComposeImage(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = figure.caption.takeIf { it.isNotBlank() },
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
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
    if (figure.caption.isNotBlank()) {
        Spacer(Modifier.size(2.dp))
        Text(
            text = figure.caption,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
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
 *
 * [reqDimensionPx] caps the decode to `reqDimensionPx` on either axis via `inSampleSize` — set to
 * 0 (default) for the historical full-resolution decode. The annotations panel passes a small cap
 * so scrolling a list of large captured figures doesn't allocate tens of MB per thumbnail on a
 * memory-constrained device.
 */
internal fun decodeImageDataUri(dataUri: String?, reqDimensionPx: Int = 0): android.graphics.Bitmap? {
    if (dataUri.isNullOrBlank()) return null
    val comma = dataUri.indexOf(',')
    if (comma < 0 || !dataUri.startsWith("data:")) return null
    return runCatching {
        val bytes = Base64.decode(dataUri.substring(comma + 1), Base64.DEFAULT)
        if (reqDimensionPx > 0) {
            decodeSampledBitmap(bytes, reqDimensionPx, reqDimensionPx)
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
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
