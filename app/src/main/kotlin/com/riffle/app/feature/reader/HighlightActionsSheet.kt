package com.riffle.app.feature.reader

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.riffle.core.domain.EmphasisStyle
import com.riffle.core.domain.HighlightColor

/**
 * A row of the four highlight swatches. The selected swatch gets an onSurface ring + a centred
 * checkmark (reads clearly in both themes); the 4dp padding is always reserved so the row doesn't
 * shift on selection. Modelled on the readaloud settings picker for visual consistency.
 */
@Composable
fun HighlightSwatchRow(
    selected: HighlightColor?,
    onPick: (HighlightColor) -> Unit,
    onPickNone: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // ADR 0046 §4: the `∅` swatch removes the highlight color while keeping any emphasis
        // rows intact — the coupled "Annotate" sheet's escape hatch when the user only wanted
        // formatting (bold/italic/underline/strike) and not a highlight.
        val noneSelected = selected == null
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable { onPickNone() }
                .then(
                    if (noneSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    else Modifier
                )
                .padding(4.dp)
                .clip(CircleShape)
                .border(1.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), CircleShape)
                .semantics {
                    contentDescription = "No highlight color" + if (noneSelected) ", selected" else ""
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
                    .clickable { onPick(color) }
                    .then(
                        if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        else Modifier
                    )
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(swatchColor)
                    .semantics {
                        contentDescription = color.token.replaceFirstChar { it.uppercase() } +
                            " highlight" + if (isSelected) ", selected" else ""
                    },
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color(0xDD000000),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * ADR 0046 §4: Emphasis chip row. Four chips (B/I/U/S) rendered in their own style so the
 * affordance mirrors the visual result. Active chips fill with the reader accent; the row is
 * independent of the highlight-colour row above.
 */
@Composable
fun EmphasisChipRow(
    selected: Set<EmphasisStyle>,
    onToggle: (EmphasisStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        EmphasisStyle.entries.forEach { style ->
            val isActive = style in selected
            val bg = if (isActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            val fg = if (isActive) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface
            val label = when (style) {
                EmphasisStyle.BOLD -> "B"
                EmphasisStyle.ITALIC -> "I"
                EmphasisStyle.UNDERLINE -> "U"
                EmphasisStyle.STRIKE -> "S"
            }
            val chipStyle = when (style) {
                EmphasisStyle.BOLD -> MaterialTheme.typography.titleMedium.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                )
                EmphasisStyle.ITALIC -> MaterialTheme.typography.titleMedium.copy(
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                )
                EmphasisStyle.UNDERLINE -> MaterialTheme.typography.titleMedium.copy(
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                )
                EmphasisStyle.STRIKE -> MaterialTheme.typography.titleMedium.copy(
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(width = 40.dp, height = 34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg)
                    .clickable { onToggle(style) }
                    .semantics {
                        contentDescription = "Emphasis " + style.token +
                            if (isActive) ", active" else ""
                    },
            ) {
                Text(text = label, color = fg, style = chipStyle)
            }
        }
    }
}

@Composable
fun HighlightActionsPopup(
    anchorRect: IntRect,
    selected: HighlightColor?,
    note: String?,
    emphasisStyles: Set<EmphasisStyle> = emptySet(),
    onPick: (HighlightColor) -> Unit,
    /** ADR 0046 §4: remove the highlight color while keeping the emphasis rows intact. */
    onRemoveColor: () -> Unit = {},
    onToggleEmphasis: (EmphasisStyle) -> Unit = {},
    onDelete: () -> Unit,
    onOpenNoteEditor: () -> Unit,
    onDismiss: () -> Unit,
    noteOnly: Boolean = false,
    showOpenInBook: Boolean = false,
    onOpenInBook: () -> Unit = {},
) {
    val density = LocalDensity.current
    val margin = with(density) { 8.dp.roundToPx() }
    val provider = remember(anchorRect) { HighlightPopupPositionProvider(anchorRect, margin) }

    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        // focusable = false so the popup Window does NOT take input focus from the reader
        // Activity — any focus transfer causes the OS to reveal the reader's status/nav bars for
        // ~250ms before the OS's sticky-IMMERSIVE re-hides them, a visible flash. Non-focusable
        // popups still receive touch events (dismissOnClickOutside works via touch dispatch, not
        // focus), so the only casualty is Back-key dismissal — restored explicitly via the
        // BackHandler below.
        properties = PopupProperties(focusable = false),
    ) {
        BackHandler(enabled = true, onBack = onDismiss)
        Surface(
            shape = RoundedCornerShape(12.dp),
            shadowElevation = 4.dp,
            tonalElevation = 0.dp,
        ) {
            Column(modifier = Modifier.width(280.dp)) {
                if (!noteOnly) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HighlightSwatchRow(selected = selected, onPick = onPick, onPickNone = onRemoveColor)
                        IconButton(onClick = onDelete) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete highlight",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    // ADR 0046: Emphasis chip row lives beneath the colour row, sharing the sheet.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EmphasisChipRow(selected = emphasisStyles, onToggle = onToggleEmphasis)
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
                if (noteOnly) {
                    // Read-only note view: full text + Edit button. No colour pickers, no delete.
                    // note==null is a transient race (glyph decorated before note deletion lands);
                    // guard defensively rather than showing a broken "Edit" with no content.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Text(
                            text = "Note",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (note != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = note,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(
                                onClick = { onOpenNoteEditor() },
                                modifier = Modifier.align(Alignment.End),
                            ) {
                                Text("Edit")
                            }
                        }
                    }
                } else {
                    var noteExpanded by remember(note) { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                when {
                                    note == null -> onOpenNoteEditor()
                                    noteExpanded -> noteExpanded = false
                                    else -> noteExpanded = true
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (note != null) "Note" else "Add note",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (note != null && !noteExpanded) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (note != null && noteExpanded) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = note,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                TextButton(
                                    onClick = { onOpenNoteEditor() },
                                    modifier = Modifier.align(Alignment.End),
                                ) {
                                    Text("Edit")
                                }
                            }
                        }
                        Icon(
                            imageVector = when {
                                note == null -> Icons.Outlined.Edit
                                noteExpanded -> Icons.Filled.KeyboardArrowUp
                                else -> Icons.Filled.KeyboardArrowDown
                            },
                            contentDescription = when {
                                note == null -> null
                                noteExpanded -> "Collapse note"
                                else -> "Expand note"
                            },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                // Highlights-mode only (Task 9, ADR 0041): the elided reader has no chapter
                // context, so this row is the escape hatch back to the real book at this
                // highlight's position.
                if (showOpenInBook && !noteOnly) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDismiss(); onOpenInBook() }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Open in book",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun NoteEditorDialog(
    initialNote: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable(initialNote) { mutableStateOf(initialNote) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Add a note…") },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("Save") }
        },
        dismissButton = {
            if (initialNote.isNotBlank()) {
                TextButton(onClick = { onConfirm("") }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
