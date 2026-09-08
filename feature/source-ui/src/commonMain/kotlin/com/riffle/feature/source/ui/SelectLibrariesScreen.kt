package com.riffle.feature.source.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.PendingSource
import com.riffle.feature.source.ui.generated.resources.Res
import com.riffle.feature.source.ui.generated.resources.ui_back
import com.riffle.feature.source.ui.generated.resources.ui_choose_which_libraries_to_show_in_riffle_you_can_change_this_later_in_settings
import com.riffle.feature.source.ui.generated.resources.ui_continue
import com.riffle.feature.source.ui.generated.resources.ui_go_back
import com.riffle.feature.source.ui.generated.resources.ui_select_at_least_one_library
import com.riffle.feature.source.ui.generated.resources.ui_select_libraries
import com.riffle.feature.source.ui.generated.resources.ui_this_source_doesn_t_expose_any_book_libraries
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectLibrariesScreen(
    isExpandedWidth: Boolean,
    pending: PendingSource,
    onNavigateBack: () -> Unit,
    onContinueComplete: () -> Unit,
    viewModel: SelectLibrariesViewModel,
) {
    LaunchedEffect(pending) { viewModel.bind(pending) }
    LaunchedEffect(Unit) { viewModel.navigateHome.collect { onContinueComplete() } }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel.errorMessage) {
        viewModel.errorMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.ui_select_libraries)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(SourceUiIcons.ArrowBack, contentDescription = stringResource(Res.string.ui_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        TabletContentWidthContainer(
            isExpandedWidth = isExpandedWidth,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            if (viewModel.libraries.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(Res.string.ui_this_source_doesn_t_expose_any_book_libraries),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = onNavigateBack) { Text(stringResource(Res.string.ui_go_back)) }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Text(
                        text = stringResource(
                            Res.string.ui_choose_which_libraries_to_show_in_riffle_you_can_change_this_later_in_settings,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    )

                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(viewModel.libraries, key = { it.id }) { lib ->
                            ListItem(
                                headlineContent = { Text(lib.name) },
                                trailingContent = {
                                    Switch(
                                        checked = lib.id in viewModel.selectedIds,
                                        onCheckedChange = { viewModel.toggle(lib.id) },
                                    )
                                },
                            )
                            HorizontalDivider()
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (viewModel.selectedIds.isEmpty()) {
                            Text(
                                stringResource(Res.string.ui_select_at_least_one_library),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Button(
                            onClick = viewModel::onContinue,
                            enabled = viewModel.canContinue,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(Res.string.ui_continue)) }
                    }
                }
            }
        }
    }
}
