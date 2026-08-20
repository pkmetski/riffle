package com.riffle.app.feature.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.riffle.app.R
import com.riffle.app.feature.settings.DrillInChevron
import com.riffle.app.feature.settings.SettingsSectionHeader

@Composable
internal fun DictionaryPacksSection(
    onOpen: () -> Unit,
) {
    SettingsSectionHeader(stringResource(R.string.ui_dictionary))
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        headlineContent = { Text(stringResource(R.string.ui_dictionary_packs)) },
        supportingContent = { Text(stringResource(R.string.ui_manage_offline_word_lookup_packs)) },
        trailingContent = { DrillInChevron() },
    )
}
