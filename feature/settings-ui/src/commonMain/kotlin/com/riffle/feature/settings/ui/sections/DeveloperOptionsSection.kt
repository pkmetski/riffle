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

@Composable
internal fun DeveloperOptionsSection(onOpen: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(Res.string.ui_developer_options)) },
        trailingContent = { DrillInChevron() },
        modifier = Modifier.clickable(onClick = onOpen),
    )
}
