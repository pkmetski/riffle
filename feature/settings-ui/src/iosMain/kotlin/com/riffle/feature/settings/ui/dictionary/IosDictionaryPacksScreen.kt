package com.riffle.feature.settings.ui.dictionary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.core.dictionary.LanguageCatalog
import com.riffle.feature.designsystem.RiffleIcons
import com.riffle.feature.downloads.formatBytes
import com.riffle.feature.settings.ui.generated.resources.Res
import com.riffle.feature.settings.ui.generated.resources.ui_approx_size
import com.riffle.feature.settings.ui.generated.resources.ui_available
import com.riffle.feature.settings.ui.generated.resources.ui_back
import com.riffle.feature.settings.ui.generated.resources.ui_dictionary_packs
import com.riffle.feature.settings.ui.generated.resources.ui_dictionary_source_description
import com.riffle.feature.settings.ui.generated.resources.ui_download
import org.jetbrains.compose.resources.stringResource

/**
 * iOS version of the Dictionary Packs screen. Shows all available [LanguageCatalog] entries with
 * approximate download sizes. Downloads are not yet supported on iOS — the Download button is
 * disabled until a background download mechanism is wired for iOS.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IosDictionaryPacksScreen(onNavigateBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.ui_dictionary_packs)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(RiffleIcons.ArrowBack, contentDescription = stringResource(Res.string.ui_back))
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(paddingValues),
        ) {
            Text(
                text = stringResource(Res.string.ui_available),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                text = stringResource(Res.string.ui_dictionary_source_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 4.dp),
            )
            LanguageCatalog.all.forEach { entry ->
                ListItem(
                    headlineContent = { Text(entry.displayName) },
                    supportingContent = {
                        Text(stringResource(Res.string.ui_approx_size, formatBytes(entry.approximateSizeBytes)))
                    },
                    trailingContent = {
                        // Downloads are not yet supported on iOS.
                        TextButton(onClick = {}, enabled = false) {
                            Text(stringResource(Res.string.ui_download))
                        }
                    },
                )
            }
        }
    }
}
