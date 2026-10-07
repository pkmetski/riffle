package com.riffle.feature.settings.ui.panels
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
import com.riffle.feature.settings.ui.generated.resources.Res
import com.riffle.feature.settings.ui.generated.resources.ui_cuts_oversized_panels_in_half
import com.riffle.feature.settings.ui.generated.resources.ui_no_split
import com.riffle.feature.settings.ui.generated.resources.ui_show_oversized_panels_as_is_without_splitting
import com.riffle.feature.settings.ui.generated.resources.ui_smart_split
import com.riffle.feature.settings.ui.generated.resources.ui_smart_split_description
import com.riffle.feature.settings.ui.generated.resources.ui_split
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PanelOverflowRadioGroup(
    selected: PanelOverflowBehavior,
    enabled: Boolean,
    onSelect: (PanelOverflowBehavior) -> Unit,
) {
    val options = listOf(
        Triple(PanelOverflowBehavior.OFF, stringResource(Res.string.ui_no_split), stringResource(Res.string.ui_show_oversized_panels_as_is_without_splitting)),
        Triple(PanelOverflowBehavior.SPLIT, stringResource(Res.string.ui_split), stringResource(Res.string.ui_cuts_oversized_panels_in_half)),
        Triple(PanelOverflowBehavior.SMART_SPLIT, stringResource(Res.string.ui_smart_split), stringResource(Res.string.ui_smart_split_description)),
    )
    Column(Modifier.selectableGroup()) {
        options.forEach { (behavior, label, description) ->
            ListItem(
                headlineContent = { Text(label) },
                supportingContent = { Text(description) },
                leadingContent = {
                    RadioButton(selected = selected == behavior, onClick = null, enabled = enabled)
                },
                modifier = Modifier
                    .alpha(if (enabled) 1f else 0.38f)
                    .then(
                        if (enabled) {
                            Modifier.selectable(
                                selected = selected == behavior,
                                onClick = { onSelect(behavior) },
                                role = Role.RadioButton,
                            )
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}
