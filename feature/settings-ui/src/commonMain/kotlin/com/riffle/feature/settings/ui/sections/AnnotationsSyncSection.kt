package com.riffle.feature.settings.ui.sections
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.riffle.feature.designsystem.SettingsSectionHeader
import com.riffle.feature.settings.AnnotationSyncRowState
import com.riffle.feature.settings.ui.AnnotationSyncBadge
import com.riffle.feature.settings.ui.DrillInChevron
import com.riffle.feature.settings.ui.generated.resources.*
import com.riffle.feature.settings.ui.generated.resources.Res
import com.riffle.feature.settings.ui.resolve
import org.jetbrains.compose.resources.stringResource

/**
 * WebDAV annotation-sync section — collapsed to a single drill-in row that leads to the dedicated
 * settings screen. The title identifies Komga as the current consumer without binding the generic
 * transport to it permanently. The row preserves the four-state badge
 * (Local/Synced/Pending/Error) and tone-colored status subtitle.
 */
@Composable
internal fun AnnotationsSyncSection(
    row: AnnotationSyncRowState,
    onOpen: () -> Unit,
) {
    // TODO: migrate to Res.string.ui_webdav_annotation_sync_for_komga
    SettingsSectionHeader(stringResource(Res.string.ui_webdav_annotation_sync_for_komga))
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        leadingContent = { AnnotationSyncBadge(row.badge) },
        headlineContent = {
            Text(
                if (row.badge == AnnotationSyncRowState.Badge.Local) {
                    // TODO: migrate to Res.string.ui_configure_webdav
                    stringResource(Res.string.ui_configure_webdav)
                } else {
                    row.headline
                },
            )
        },
        supportingContent = {
            Text(
                text = row.sub.resolve(),
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
