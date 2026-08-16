package com.riffle.app.feature.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.riffle.app.feature.settings.DrillInChevron
import com.riffle.app.feature.settings.SettingsSectionHeader

@Composable
internal fun DictionaryPacksSection(
    onOpen: () -> Unit,
) {
    SettingsSectionHeader("Dictionary")
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        headlineContent = { Text("Dictionary packs") },
        supportingContent = { Text("Manage offline word lookup packs") },
        trailingContent = { DrillInChevron() },
    )
}
