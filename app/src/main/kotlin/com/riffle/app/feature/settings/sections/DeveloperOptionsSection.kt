package com.riffle.app.feature.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.riffle.app.feature.settings.DrillInChevron

@Composable
internal fun DeveloperOptionsSection(onOpen: () -> Unit) {
    ListItem(
        headlineContent = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_developer_options)) },
        trailingContent = { DrillInChevron() },
        modifier = Modifier.clickable(onClick = onOpen),
    )
}
