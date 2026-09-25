package com.riffle.feature.source.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.riffle.core.models.SourceType
import com.riffle.feature.designsystem.RiffleIcons
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.designsystem.TabletContentWidthContainer
import com.riffle.feature.source.ui.generated.resources.Res
import com.riffle.feature.source.ui.generated.resources.ui_add_source
import com.riffle.feature.source.ui.generated.resources.ui_back
import com.riffle.feature.source.ui.generated.resources.ui_singleton_confirm_title
import com.riffle.feature.source.ui.generated.resources.ui_try_again
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Confirmation screen for zero-config singleton sources (Chitanka, Gutenberg, radio.es). Mirrors
 * the Android-only [com.riffle.app.feature.source.chitanka.AddChitankaScreen] / AddGutenbergScreen
 * / AddRadioEsScreen, but lives in [feature/source-ui] so iOS can share it through
 * [com.riffle.shared.source.SourceOnboardingHost].
 *
 * Shows the source name, a brief description, an attribution line and a single "Add source"
 * button. [onInstall] is a suspend lambda that performs the actual install; errors are displayed
 * inline with a retry button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SingletonSourceConfirmScreen(
    type: SourceType,
    isExpandedWidth: Boolean,
    onNavigateBack: () -> Unit,
    onInstall: suspend () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var isInstalling by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val sourceName = localizedSourceDisplayName(type)
    val title = stringResource(Res.string.ui_singleton_confirm_title, sourceName)
    val description = singletonSourceDescriptionRes(type)?.let { stringResource(it) }
    val attribution = singletonSourceAttributionRes(type)?.let { stringResource(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SourceTypeIcon(type = type, size = 28.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(title)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag(TestTags.NAV_BACK)) {
                        Icon(RiffleIcons.ArrowBack, contentDescription = stringResource(Res.string.ui_back))
                    }
                },
            )
        },
    ) { padding ->
        TabletContentWidthContainer(
            isExpandedWidth = isExpandedWidth,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    sourceName,
                    style = MaterialTheme.typography.headlineSmall,
                )
                if (description != null) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                if (attribution != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        attribution,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(16.dp))
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (isInstalling) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                } else if (error != null) {
                    TextButton(onClick = onNavigateBack, modifier = Modifier.fillMaxWidth().testTag(TestTags.SOURCE_TYPE_PICKER_CANCEL)) {
                        Text(stringResource(Res.string.ui_back))
                    }
                    Button(
                        onClick = {
                            error = null
                            isInstalling = true
                            scope.launch {
                                runCatching { onInstall() }
                                    .onFailure { error = it.message ?: "Unknown error" }
                                isInstalling = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag(TestTags.SOURCE_TYPE_PICKER_CONFIRM),
                    ) {
                        Text(stringResource(Res.string.ui_try_again))
                    }
                } else {
                    Button(
                        onClick = {
                            isInstalling = true
                            scope.launch {
                                runCatching { onInstall() }
                                    .onFailure { error = it.message ?: "Unknown error" }
                                isInstalling = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag(TestTags.SOURCE_TYPE_PICKER_CONFIRM),
                    ) {
                        Text(stringResource(Res.string.ui_add_source))
                    }
                }
            }
        }
    }
}

/** Resolves a display name for a [SourceType] without needing a [Source] instance. */
@Composable
private fun localizedSourceDisplayName(type: SourceType): String =
    stringResource(sourceDisplayNameRes(type))
