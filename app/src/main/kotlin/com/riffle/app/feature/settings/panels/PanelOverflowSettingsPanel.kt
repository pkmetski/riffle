package com.riffle.app.feature.settings.panels

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.comic.PanelOverflowBehavior

@Composable
internal fun PanelOverflowSettingsPanel(
    selected: PanelOverflowBehavior,
    onSelected: (PanelOverflowBehavior) -> Unit,
    onDismiss: () -> Unit,
) = DetailScaffold("Panel Overflow", onDismiss) {
    Text(
        text = "Some panels span the full width or height of the page and can't be zoomed " +
            "into in the current orientation. Split divides them at the midpoint into two " +
            "navigable halves. Auto-rotate temporarily turns the device so the panel fits " +
            "naturally.",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
    )
    val options = listOf(
        PanelOverflowBehavior.SPLIT to "Split",
        PanelOverflowBehavior.AUTO_ROTATE to "Auto-rotate",
        PanelOverflowBehavior.OFF to "Off",
    )
    Column(Modifier.selectableGroup()) {
        options.forEach { (behavior, label) ->
            ListItem(
                headlineContent = { Text(label) },
                leadingContent = {
                    RadioButton(
                        selected = selected == behavior,
                        onClick = null,
                    )
                },
                modifier = Modifier.selectable(
                    selected = selected == behavior,
                    onClick = { onSelected(behavior) },
                    role = Role.RadioButton,
                ),
            )
        }
    }
}
