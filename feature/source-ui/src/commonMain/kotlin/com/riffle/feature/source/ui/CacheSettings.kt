package com.riffle.feature.source.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.ContentCacheAutoClear
import com.riffle.feature.source.ui.generated.resources.Res
import com.riffle.feature.source.ui.generated.resources.ui_after_days
import com.riffle.feature.source.ui.generated.resources.ui_auto_clear_after_days
import com.riffle.feature.source.ui.generated.resources.ui_auto_clear_off
import com.riffle.feature.source.ui.generated.resources.ui_cache_settings
import com.riffle.feature.source.ui.generated.resources.ui_cached_book_audiobook_comic_and_readaloud_files_can_be_removed_after_they_have_n
import com.riffle.feature.source.ui.generated.resources.ui_done
import com.riffle.feature.source.ui.generated.resources.ui_off
import org.jetbrains.compose.resources.stringResource

/**
 * The Downloads screen's "Cache settings" row and its auto-clear dialog.
 *
 * Both platforms render this one implementation. They previously kept private copies — Android's
 * localized through `app`'s string resources, iOS's hardcoded in English — which is exactly the
 * drift class AGENTS.md's "no private platform copies of shared derivations" rule exists to stop.
 */
@Composable
fun CacheSettingsRow(
    autoClear: ContentCacheAutoClear,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // No leading icon: material-icons is not a dependency of this multiplatform module,
        // and the icon was decorative (contentDescription = null) on Android.
        OutlinedButton(onClick = onClick) {
            Text(stringResource(Res.string.ui_cache_settings))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = autoClear.summaryLabel(),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun CacheSettingsDialog(
    selected: ContentCacheAutoClear,
    onSelected: (ContentCacheAutoClear) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.ui_cache_settings)) },
        text = {
            Column {
                Text(
                    text = stringResource(
                        Res.string.ui_cached_book_audiobook_comic_and_readaloud_files_can_be_removed_after_they_have_n,
                    ),
                    modifier = Modifier.padding(bottom = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ContentCacheAutoClear.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(option) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = option == selected,
                            onClick = { onSelected(option) },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(option.optionLabel(), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.ui_done)) }
        },
    )
}

@Composable
private fun ContentCacheAutoClear.summaryLabel(): String =
    days?.let { stringResource(Res.string.ui_auto_clear_after_days, it) }
        ?: stringResource(Res.string.ui_auto_clear_off)

@Composable
private fun ContentCacheAutoClear.optionLabel(): String =
    days?.let { stringResource(Res.string.ui_after_days, it) } ?: stringResource(Res.string.ui_off)
