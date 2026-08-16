package com.riffle.app.feature.reader.cbz

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.comic.BookComicFormattingOverrides
import com.riffle.core.domain.comic.ComicFormattingPreferences
import com.riffle.core.domain.comic.PanelOverflowBehavior

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComicFormattingSheet(
    formatting: ComicFormattingPreferences,
    onUpdate: (BookComicFormattingOverrides) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(bottom = 24.dp)) {
            // Panel View toggle
            ListItem(
                headlineContent = { Text("Panel View") },
                supportingContent = { Text("Navigate panel by panel") },
                trailingContent = {
                    Switch(
                        checked = formatting.panelViewOn,
                        onCheckedChange = { on ->
                            onUpdate(BookComicFormattingOverrides(panelViewOn = on))
                        },
                    )
                },
            )

            // Panel Overflow picker — disabled when Panel View is off (chapter-map dependency pattern)
            val overflowEnabled = formatting.panelViewOn
            val options = listOf(
                PanelOverflowBehavior.SPLIT to "Split",
                PanelOverflowBehavior.SMART_SPLIT to "Smart split",
                PanelOverflowBehavior.OFF to "Off",
            )
            Column(Modifier.selectableGroup()) {
                options.forEach { (behavior, label) ->
                    ListItem(
                        headlineContent = {
                            Text(
                                label,
                                color = if (overflowEnabled) {
                                    androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                                } else {
                                    androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                },
                            )
                        },
                        leadingContent = {
                            RadioButton(
                                selected = formatting.panelOverflow == behavior,
                                onClick = null,
                                enabled = overflowEnabled,
                            )
                        },
                        modifier = if (overflowEnabled) {
                            Modifier.selectable(
                                selected = formatting.panelOverflow == behavior,
                                onClick = { onUpdate(BookComicFormattingOverrides(panelOverflow = behavior)) },
                                role = Role.RadioButton,
                            )
                        } else Modifier,
                    )
                }
            }
        }
    }
}
