package com.riffle.app.feature.reader.cbz

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.comic.BookComicFormattingOverrides
import com.riffle.core.domain.comic.ComicFormattingPreferences

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComicFormattingSheet(
    formatting: ComicFormattingPreferences,
    hasBookOverrides: Boolean,
    onUpdate: (BookComicFormattingOverrides) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
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
            PanelOverflowRadioGroup(
                selected = formatting.panelOverflow,
                enabled = formatting.panelViewOn,
                onSelect = { onUpdate(BookComicFormattingOverrides(panelOverflow = it)) },
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            PanelAnimationSpeedSlider(
                speedMs = formatting.panelAnimationSpeedMs,
                onSpeedChange = { speed -> onUpdate(BookComicFormattingOverrides(panelAnimationSpeedMs = speed)) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                enabled = formatting.panelViewOn,
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Chapter map") },
                trailingContent = {
                    Switch(
                        checked = formatting.showChapterMap,
                        onCheckedChange = { on ->
                            onUpdate(BookComicFormattingOverrides(showChapterMap = on))
                        },
                    )
                },
            )
            if (formatting.showChapterMap) {
                ListItem(
                    headlineContent = { Text("Page progress") },
                    supportingContent = { Text("Current page and remaining pages above the chapter map") },
                    trailingContent = {
                        Switch(
                            checked = formatting.showPageProgress,
                            onCheckedChange = { on ->
                                onUpdate(BookComicFormattingOverrides(showPageProgress = on))
                            },
                        )
                    },
                )
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
