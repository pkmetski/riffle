package com.riffle.feature.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.riffle.core.models.EmphasisStyle
import com.riffle.core.models.HighlightColor
import com.riffle.feature.designsystem.TestTags

/**
 * Every user-visible string the annotation sheets need, supplied by the host.
 *
 * Same contract as [ChapterMapProgressLabelTemplates] in this module: `:app` passes its
 * `stringResource` values (and therefore its `values-bg` / `values-es` translations), `:shared`
 * passes [English] until the i18n mechanism for iOS exists. Keeping the strings out of the
 * composable is what lets one implementation serve both hosts without a resource migration.
 */
data class AnnotationSheetLabels(
    val noHighlightColor: String,
    val selectedSuffix: String,
    val highlightSuffix: String,
    val emphasis: String,
    val activeSuffix: String,
    val note: String,
    val addNote: String,
    val edit: String,
    val save: String,
    val remove: String,
    val cancel: String,
    val delete: String,
    val bookmark: String,
    val removeBookmark: String,
) {
    companion object {
        val English = AnnotationSheetLabels(
            noHighlightColor = "No highlight color",
            selectedSuffix = ", selected",
            highlightSuffix = " highlight",
            emphasis = "Emphasis ",
            activeSuffix = ", active",
            note = "Note",
            addNote = "Add note",
            edit = "Edit",
            save = "Save",
            remove = "Remove",
            cancel = "Cancel",
            delete = "Delete annotation",
            bookmark = "Bookmark this page",
            removeBookmark = "Remove bookmark",
        )
    }
}

/**
 * A row of the four highlight swatches plus the `∅` "no colour" swatch.
 *
 * The selected swatch gets an onSurface ring + a centred checkmark; the 4dp padding is always
 * reserved so the row doesn't shift on selection.
 *
 * [readerBackground] is painted as an opaque backdrop behind each swatch so the semi-transparent
 * (alpha 0x80) highlight colour composites against the same paper the reader is drawing —
 * otherwise the swatches look muddy in a dark app while the reader is on light theme.
 */
@Composable
fun HighlightSwatchRow(
    selected: HighlightColor?,
    readerBackground: Color,
    labels: AnnotationSheetLabels,
    onPick: (HighlightColor) -> Unit,
    onPickNone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // ADR 0056 §4: the `∅` swatch removes the highlight color while keeping any emphasis
        // rows intact — the escape hatch when the user only wanted formatting.
        val noneSelected = selected == null
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .testTag(TestTags.ANNOTATION_SWATCH_NONE)
                .clickable { onPickNone() }
                .then(
                    if (noneSelected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    } else {
                        Modifier
                    },
                )
                .padding(4.dp)
                .clip(CircleShape)
                .border(1.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), CircleShape)
                .semantics {
                    contentDescription =
                        labels.noHighlightColor + if (noneSelected) labels.selectedSuffix else ""
                },
        ) {
            Text(
                text = "∅",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        HighlightColor.entries.forEach { color ->
            val isSelected = color == selected
            val swatchColor = Color(color.argb.toLong() and 0xFFFFFFFFL)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .testTag(TestTags.annotationSwatch(color.token))
                    .clickable { onPick(color) }
                    .then(
                        if (isSelected) {
                            Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        } else {
                            Modifier
                        },
                    )
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(readerBackground)
                    .background(swatchColor)
                    .semantics {
                        contentDescription = color.token.replaceFirstChar { it.uppercase() } +
                            labels.highlightSuffix + if (isSelected) labels.selectedSuffix else ""
                    },
            ) {
                if (isSelected) {
                    // A glyph rather than a Material icon so this composable stays free of the
                    // icon registry the design-system module owns.
                    Text(text = "✓", color = Color(0xDD000000), style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

/**
 * ADR 0056 §4: the B/I/U/S emphasis chips, each rendered in the style it applies so the
 * affordance mirrors the visual result.
 */
@Composable
fun EmphasisChipRow(
    selected: Set<EmphasisStyle>,
    labels: AnnotationSheetLabels,
    onToggle: (EmphasisStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        EmphasisStyle.entries.forEach { style ->
            val isActive = style in selected
            val bg = if (isActive) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
            val fg = if (isActive) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            }
            val label = when (style) {
                EmphasisStyle.BOLD -> "B"
                EmphasisStyle.ITALIC -> "I"
                EmphasisStyle.UNDERLINE -> "U"
                EmphasisStyle.STRIKE -> "S"
            }
            val chipStyle = when (style) {
                EmphasisStyle.BOLD ->
                    MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                EmphasisStyle.ITALIC ->
                    MaterialTheme.typography.titleMedium.copy(fontStyle = FontStyle.Italic)
                EmphasisStyle.UNDERLINE ->
                    MaterialTheme.typography.titleMedium.copy(textDecoration = TextDecoration.Underline)
                EmphasisStyle.STRIKE ->
                    MaterialTheme.typography.titleMedium.copy(textDecoration = TextDecoration.LineThrough)
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(width = 40.dp, height = 34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg)
                    .testTag(TestTags.annotationChip(style.token))
                    .clickable { onToggle(style) }
                    .semantics {
                        contentDescription = labels.emphasis + style.token +
                            if (isActive) labels.activeSuffix else ""
                    },
            ) {
                Text(text = label, color = fg, style = chipStyle)
            }
        }
    }
}

/**
 * The annotate surface: colour swatches, emphasis chips, a note row and delete.
 *
 * Used for a fresh selection (no [note], nothing selected yet) and for an existing annotation
 * alike — the two differ only in what is pre-selected and whether [onDelete] is offered, which
 * is exactly how Android's `HighlightActionsPopup` behaves.
 *
 * Android anchors its copy in a `Popup` next to the tapped rect and iOS docks this at the
 * bottom of the reader; the anchoring is the host's, the content is this.
 */
@Composable
fun AnnotationActionsSheet(
    selectedColor: HighlightColor?,
    emphasisStyles: Set<EmphasisStyle>,
    note: String?,
    readerBackground: Color,
    labels: AnnotationSheetLabels,
    onPickColor: (HighlightColor) -> Unit,
    onRemoveColor: () -> Unit,
    onToggleEmphasis: (EmphasisStyle) -> Unit,
    onOpenNoteEditor: () -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 4.dp,
        tonalElevation = 0.dp,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HighlightSwatchRow(
                    selected = selectedColor,
                    readerBackground = readerBackground,
                    labels = labels,
                    onPick = onPickColor,
                    onPickNone = onRemoveColor,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                EmphasisChipRow(
                    selected = emphasisStyles,
                    labels = labels,
                    onToggle = onToggleEmphasis,
                )
                if (onDelete != null) {
                    Text(
                        text = "🗑",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .testTag(TestTags.ANNOTATION_DELETE)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onDelete)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .semantics { contentDescription = labels.delete },
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.ANNOTATION_NOTE_ROW)
                    .clickable(onClick = onOpenNoteEditor)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (note != null) labels.note else labels.addNote,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (note != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    text = labels.edit,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * The note editor.
 *
 * Confirming an empty string is the documented "remove the note" path (`updateNote(id, null)`
 * on the store side) — the same semantics as Android's `NoteEditorDialog`, whose Remove button
 * calls `onConfirm("")`.
 */
@Composable
fun NoteEditorSheet(
    initialNote: String,
    labels: AnnotationSheetLabels,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(initialNote) { mutableStateOf(initialNote) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 4.dp,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(text = labels.note, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(labels.addNote) },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth().testTag(TestTags.NOTE_EDITOR_FIELD),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (initialNote.isNotBlank()) {
                    TextButton(
                        onClick = { onConfirm("") },
                        modifier = Modifier.testTag(TestTags.NOTE_EDITOR_REMOVE),
                    ) {
                        Text(labels.remove, color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag(TestTags.NOTE_EDITOR_CANCEL),
                    ) { Text(labels.cancel) }
                }
                TextButton(
                    onClick = { onConfirm(text) },
                    modifier = Modifier.testTag(TestTags.NOTE_EDITOR_SAVE),
                ) { Text(labels.save) }
            }
        }
    }
}
