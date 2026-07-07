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
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
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
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

@Composable
fun HighlightActionsPopup(
    anchorRect: IntRect,
    selected: HighlightColor?,
    note: String?,
    onPick: (HighlightColor) -> Unit,
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
        properties = PopupProperties(focusable = true),
    ) {
        // Compose creates a focusable Popup as its own WindowManager window that steals input
        // focus from the reader Activity. The reader's non-sticky SYSTEM_UI_FLAG_IMMERSIVE only
        // holds while its Window has focus, so on focus loss the OS reveals the status/nav bars
        // behind the popup. Applying the fullscreen/immersive flags to THIS popup Window means
        // the OS sees the focused window is also fullscreen — no reason to draw bars.
        ImmersivePopupWindow()
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
                        HighlightSwatchRow(selected = selected, onPick = onPick)
                        IconButton(onClick = onDelete) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete highlight",
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
                                onClick = { onDismiss(); onOpenNoteEditor() },
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
                                    note == null -> { onDismiss(); onOpenNoteEditor() }
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
                                    onClick = { onDismiss(); onOpenNoteEditor() },
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

/**
 * Hide the system bars on the enclosing focusable Popup's own Window so the reader Activity
 * behind never has its bars revealed by the focus transfer. See the comment at the Popup call
 * site above for the full rationale. Uses `View.systemUiVisibility` directly — the popup root
 * isn't backed by an Activity `Window`, so `WindowInsetsControllerCompat` can't be attached
 * conventionally. The deprecated flags path is well-supported across API 25..34 for this
 * exact use case (a subordinate WindowManager view opting into fullscreen).
 */
@Suppress("DEPRECATION")
@Composable
private fun ImmersivePopupWindow() {
    val popupView = LocalView.current
    DisposableEffect(popupView) {
        val decor = popupView.rootView
        val previous = decor.systemUiVisibility
        decor.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        onDispose { decor.systemUiVisibility = previous }
    }
}
