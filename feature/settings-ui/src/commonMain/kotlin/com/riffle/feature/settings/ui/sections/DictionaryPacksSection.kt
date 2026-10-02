package com.riffle.feature.settings.ui.sections
import org.jetbrains.compose.resources.stringResource
import com.riffle.feature.settings.ui.generated.resources.Res
import com.riffle.feature.settings.ui.generated.resources.*

import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

import com.riffle.feature.settings.ui.DrillInChevron
import com.riffle.feature.designsystem.SettingsSectionHeader

@Composable
internal fun DictionaryPacksSection(
    onOpen: () -> Unit,
) {
    // TODO: migrate to Res.string.ui_dictionary
    SettingsSectionHeader(stringResource(Res.string.ui_dictionary))
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        // TODO: migrate to Res.string.ui_dictionary_packs
        headlineContent = { Text(stringResource(Res.string.ui_dictionary_packs)) },
        // TODO: migrate to Res.string.ui_manage_offline_word_lookup_packs
        supportingContent = { Text(stringResource(Res.string.ui_manage_offline_word_lookup_packs)) },
        trailingContent = { DrillInChevron() },
    )
}
