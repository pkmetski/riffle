package com.riffle.feature.source.ui.localfiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.riffle.feature.designsystem.RiffleIcons
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.source.ui.generated.resources.Res
import com.riffle.feature.source.ui.generated.resources.ui_add_local_folder
import com.riffle.feature.source.ui.generated.resources.ui_added_books_count
import com.riffle.feature.source.ui.generated.resources.ui_back
import com.riffle.feature.source.ui.generated.resources.ui_cancel
import com.riffle.feature.source.ui.generated.resources.ui_couldn_t_add_that_folder
import com.riffle.feature.source.ui.generated.resources.ui_done
import com.riffle.feature.source.ui.generated.resources.ui_items_could_not_be_read_count
import com.riffle.feature.source.ui.generated.resources.ui_no_folder_picked
import com.riffle.feature.source.ui.generated.resources.ui_no_supported_books_found_in_folder
import com.riffle.feature.source.ui.generated.resources.ui_opening_folder_picker
import com.riffle.feature.source.ui.generated.resources.ui_pick_a_folder
import com.riffle.feature.source.ui.generated.resources.ui_scanning_your_folder
import com.riffle.feature.source.ui.generated.resources.ui_try_again
import org.jetbrains.compose.resources.stringResource

/**
 * Shared Add-Local-Files onboarding screen, used by both Android and iOS.
 *
 * [onPickFolder] asks the caller to invoke the platform folder picker; the caller calls
 * [viewModel].onFolderPicked with the result. The screen renders the post-pick states
 * (Installing / Success / Error / Cancelled).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddLocalFilesScreen(
    onDone: () -> Unit,
    onNavigateBack: () -> Unit,
    onPickFolder: () -> Unit,
    viewModel: AddLocalFilesCommonViewModel,
) {
    val state by viewModel.state.collectAsState()

    var pickerLaunched by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!pickerLaunched && state is AddLocalFilesCommonViewModel.State.Idle) {
            pickerLaunched = true
            onPickFolder()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.ui_add_local_folder)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(RiffleIcons.ArrowBack, contentDescription = stringResource(Res.string.ui_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            when (val s = state) {
                AddLocalFilesCommonViewModel.State.Idle -> {
                    Text(stringResource(Res.string.ui_opening_folder_picker))
                }
                AddLocalFilesCommonViewModel.State.Cancelled -> CancelledView(
                    onPickAgain = onPickFolder,
                    onNavigateBack = onNavigateBack,
                )
                AddLocalFilesCommonViewModel.State.Installing -> InstallingView()
                is AddLocalFilesCommonViewModel.State.Success -> SuccessView(
                    added = s.added,
                    failures = s.failures,
                    onDone = onDone,
                )
                is AddLocalFilesCommonViewModel.State.Error -> ErrorView(
                    message = s.message,
                    onRetry = onPickFolder,
                    onNavigateBack = onNavigateBack,
                )
            }
        }
    }
}

@Composable
private fun InstallingView() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.size(16.dp))
        Text(stringResource(Res.string.ui_scanning_your_folder))
    }
}

@Composable
private fun SuccessView(added: Int, failures: Int, onDone: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(Res.string.ui_added_books_count, added),
            style = MaterialTheme.typography.titleMedium,
        )
        if (failures > 0) {
            Text(
                stringResource(Res.string.ui_items_could_not_be_read_count, failures),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (added == 0) {
            Text(
                stringResource(Res.string.ui_no_supported_books_found_in_folder),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onDone, modifier = Modifier.testTag(TestTags.ADD_LOCAL_FILES_DONE)) {
            Text(stringResource(Res.string.ui_done))
        }
    }
}

@Composable
private fun CancelledView(onPickAgain: () -> Unit, onNavigateBack: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(Res.string.ui_no_folder_picked), style = MaterialTheme.typography.titleMedium)
        Button(onClick = onPickAgain, modifier = Modifier.testTag(TestTags.ADD_LOCAL_FILES_PICK_AGAIN)) {
            Text(stringResource(Res.string.ui_pick_a_folder))
        }
        Button(onClick = onNavigateBack) { Text(stringResource(Res.string.ui_cancel)) }
    }
}

@Composable
private fun ErrorView(message: String, onRetry: () -> Unit, onNavigateBack: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(Res.string.ui_couldn_t_add_that_folder), style = MaterialTheme.typography.titleMedium)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRetry) { Text(stringResource(Res.string.ui_try_again)) }
        Button(onClick = onNavigateBack) { Text(stringResource(Res.string.ui_cancel)) }
    }
}
