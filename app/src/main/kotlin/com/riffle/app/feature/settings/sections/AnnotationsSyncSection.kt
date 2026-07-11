package com.riffle.app.feature.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.riffle.app.feature.settings.AnnotationSyncBadge
import com.riffle.app.feature.settings.AnnotationSyncRowState
import com.riffle.app.feature.settings.DrillInChevron
import com.riffle.app.feature.settings.SettingsSectionHeader

/**
 * "Annotations Sync" section — collapsed to a single drill-in row that leads to the dedicated
 * WebDAV settings screen. Row preserves the four-state badge (Local/Synced/Pending/Error) and the
 * tone-colored subtitle (error/pending/normal) from the pre-collapse layout so status stays
 * legible at the main-screen altitude.
 */
@Composable
internal fun AnnotationsSyncSection(
    row: AnnotationSyncRowState,
    onOpen: () -> Unit,
) {
    SettingsSectionHeader("Annotations Sync")
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        leadingContent = { AnnotationSyncBadge(row.badge) },
        headlineContent = { Text(if (row.badge == AnnotationSyncRowState.Badge.Local) "Configure WebDAV" else row.headline) },
        supportingContent = {
            Text(
                text = row.sub,
                color = when (row.subTone) {
                    AnnotationSyncRowState.Tone.Error -> MaterialTheme.colorScheme.error
                    AnnotationSyncRowState.Tone.Pending -> MaterialTheme.colorScheme.tertiary
                    AnnotationSyncRowState.Tone.Normal -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        trailingContent = { DrillInChevron() },
    )
}
