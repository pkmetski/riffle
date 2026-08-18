package com.riffle.app.feature.reader.cbz

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import com.riffle.core.domain.comic.PanelOverflowBehavior

private val PANEL_OVERFLOW_OPTIONS = listOf(
    Triple(PanelOverflowBehavior.SPLIT, "Split", "Cuts oversized panels in half and shows each half as its own page"),
    Triple(PanelOverflowBehavior.SMART_SPLIT, "Smart split", "Like Split, but finds a natural seam (gutter or whitespace) to cut at a cleaner boundary"),
)

@Composable
internal fun PanelOverflowRadioGroup(
    selected: PanelOverflowBehavior,
    enabled: Boolean,
    onSelect: (PanelOverflowBehavior) -> Unit,
) {
    Column(Modifier.selectableGroup()) {
        PANEL_OVERFLOW_OPTIONS.forEach { (behavior, label, description) ->
            ListItem(
                headlineContent = { Text(label) },
                supportingContent = { Text(description) },
                leadingContent = {
                    RadioButton(selected = selected == behavior, onClick = null, enabled = enabled)
                },
                modifier = Modifier
                    .alpha(if (enabled) 1f else 0.38f)
                    .then(
                        if (enabled) Modifier.selectable(
                            selected = selected == behavior,
                            onClick = { onSelect(behavior) },
                            role = Role.RadioButton,
                        ) else Modifier,
                    ),
            )
        }
    }
}
