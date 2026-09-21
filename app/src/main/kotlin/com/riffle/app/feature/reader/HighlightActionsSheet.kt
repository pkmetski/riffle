package com.riffle.app.feature.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.riffle.core.models.EmphasisStyle
import com.riffle.core.models.HighlightColor
import com.riffle.feature.reader.ui.AnnotationSheetLabels
import com.riffle.feature.reader.ui.EmphasisChipRow
import com.riffle.feature.reader.ui.HighlightSwatchRow

/**
 * The strings the shared annotation rows need, from this app's resources — so `values-bg` and
 * `values-es` keep serving them while the composables themselves live in `:feature:reader-ui`
 * and are rendered by both platforms.
 */
@Composable
internal fun rememberAnnotationSheetLabels(): AnnotationSheetLabels = AnnotationSheetLabels(
    noHighlightColor = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_no_highlight_color),
    selectedSuffix = ", selected",
    highlightSuffix = " highlight",
    emphasis = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_emphasis),
    activeSuffix = ", active",
    note = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_note),
    addNote = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_add_a_note),
    edit = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_edit),
    save = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_save),
    remove = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_remove),
    cancel = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_cancel),
    delete = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_delete_annotation),
    bookmark = "Bookmark this page",
    removeBookmark = "Remove bookmark",
)

@Composable
fun HighlightActionsPopup(
    anchorRect: IntRect,
    selected: HighlightColor?,
    note: String?,
    readerBackground: Color,
    emphasisStyles: Set<EmphasisStyle> = emptySet(),
    onPick: (HighlightColor) -> Unit,
    /** ADR 0056 §4: remove the highlight color while keeping the emphasis rows intact. */
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
    val sheetLabels = rememberAnnotationSheetLabels()

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
        val currentOnDismiss by rememberUpdatedState(onDismiss)
        Surface(
            shape = RoundedCornerShape(12.dp),
            shadowElevation = 4.dp,
            tonalElevation = 0.dp,
            // Dismiss when a vertical drag is detected on the popup surface. Without this, a scroll
            // gesture that starts inside the popup bounds is absorbed by the popup window and the
            // reader never sees it — the user experiences resistance until they lift and retry from
            // outside the popup area. Dismissing on drag start clears the popup immediately so the
            // user's next gesture scrolls the reader without friction.
            // Unit key keeps the detector stable for the popup's lifetime; rememberUpdatedState
            // ensures the latest onDismiss is always called even if the lambda reference changes.
            modifier = Modifier.pointerInput(Unit) {
                detectVerticalDragGestures(onDragStart = { currentOnDismiss() }) { _, _ -> }
            },
        ) {
            Column(modifier = Modifier.width(280.dp)) {
                if (!noteOnly) {
                    // Swatches on their own row (5 circles + no icon at the end) so the pink
                    // swatch and the destructive trash don't crowd each other.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HighlightSwatchRow(
                            selected = selected,
                            readerBackground = readerBackground,
                            labels = sheetLabels,
                            onPick = onPick,
                            onPickNone = onRemoveColor,
                        )
                    }
                    // Emphasis chip row + destructive trash share a row: chips left-aligned, trash
                    // pushed to the trailing edge with its own padding so it reads as an escape
                    // hatch, not a fifth chip.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        EmphasisChipRow(
                            selected = emphasisStyles,
                            labels = sheetLabels,
                            onToggle = onToggleEmphasis,
                        )
                        IconButton(onClick = onDelete) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_delete_annotation),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
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
                            text = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_note),
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
                                Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_edit))
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
                                    Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_edit))
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
                // Highlights-mode only (Task 9, ADR 0048): the elided reader has no chapter
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
                            text = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_open_in_book),
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
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_note)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_add_a_note)) },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_save)) }
        },
        dismissButton = {
            if (initialNote.isNotBlank()) {
                TextButton(onClick = { onConfirm("") }) {
                    Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_remove), color = MaterialTheme.colorScheme.error)
                }
            } else {
                TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_cancel)) }
            }
        },
    )
}
