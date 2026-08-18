package com.riffle.app.feature.reader.cbz

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
    hasBookOverrides: Boolean,
    onUpdate: (BookComicFormattingOverrides) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
        ) {
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

            val overflowEnabled = formatting.panelViewOn
            val options = listOf(
                Triple(PanelOverflowBehavior.SPLIT, "Split", "Cuts oversized panels in half and shows each half as its own page"),
                Triple(PanelOverflowBehavior.SMART_SPLIT, "Smart split", "Like Split, but finds a natural seam (gutter or whitespace) to cut at a cleaner boundary"),
            )
            Column(Modifier.selectableGroup()) {
                options.forEach { (behavior, label, description) ->
                    ListItem(
                        headlineContent = {
                            Text(
                                label,
                                color = if (overflowEnabled) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                },
                            )
                        },
                        supportingContent = {
                            Text(
                                description,
                                color = if (overflowEnabled) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
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

            HorizontalDivider()
            TextButton(
                onClick = onReset,
                enabled = hasBookOverrides,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 4.dp),
            ) {
                Text("Restore global defaults")
            }
        }
    }
}
