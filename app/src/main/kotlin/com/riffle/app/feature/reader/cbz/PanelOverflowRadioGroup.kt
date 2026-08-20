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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.riffle.app.R
import com.riffle.core.domain.comic.PanelOverflowBehavior

private val PANEL_OVERFLOW_OPTIONS = listOf(
    Triple(PanelOverflowBehavior.OFF, R.string.ui_no_split, R.string.ui_show_oversized_panels_as_is_without_splitting),
    Triple(PanelOverflowBehavior.SPLIT, R.string.ui_split, R.string.ui_cuts_oversized_panels_in_half),
    Triple(PanelOverflowBehavior.SMART_SPLIT, R.string.ui_smart_split, R.string.ui_smart_split_description),
)

@Composable
internal fun PanelOverflowRadioGroup(
    selected: PanelOverflowBehavior,
    enabled: Boolean,
    onSelect: (PanelOverflowBehavior) -> Unit,
) {
    Column(Modifier.selectableGroup()) {
        PANEL_OVERFLOW_OPTIONS.forEach { (behavior, labelRes, descriptionRes) ->
            ListItem(
                headlineContent = { Text(stringResource(labelRes)) },
                supportingContent = { Text(stringResource(descriptionRes)) },
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
