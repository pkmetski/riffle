package com.riffle.app.feature.settings.panels

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun PanelViewSettingsPanel(
    panelViewOn: Boolean,
    onPanelViewOnChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) = DetailScaffold("Panel View", onDismiss) {
    Text(
        text = "Panel View frames one panel at a time in reading order, zooming in to fill " +
            "the screen. Navigate with taps or swipes; long-press any panel to peek at the " +
            "full page.",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
    )
    ListItem(
        headlineContent = { Text("Panel View") },
        trailingContent = {
            Switch(checked = panelViewOn, onCheckedChange = onPanelViewOnChange)
        },
    )
}
