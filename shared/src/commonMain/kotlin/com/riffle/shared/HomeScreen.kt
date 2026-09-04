package com.riffle.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.core.data.localfiles.FolderPickerInterface
import com.riffle.core.data.localfiles.LocalFilesInstallerInterface
import com.riffle.feature.library.HomeViewModel
import com.riffle.feature.library.LibrarySectionType
import com.riffle.shared.library.LibraryItemsScreen
import com.riffle.shared.library.LibrarySectionScreen
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private sealed interface LibraryNav {
    data object Items : LibraryNav
    data class Section(val sectionType: LibrarySectionType) : LibraryNav
}

@Composable
fun HomeScreen() {
    val viewModel = koinInject<HomeViewModel>()
    val folderPicker = koinInject<FolderPickerInterface>()
    val installer = koinInject<LocalFilesInstallerInterface>()
    val scope = rememberCoroutineScope()

    var destination by remember { mutableStateOf<HomeViewModel.StartDestination?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    var installing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshKey) {
        destination = viewModel.getStartDestination()
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val dest = destination) {
            null -> BasicText("Loading…")
            is HomeViewModel.StartDestination.AddSource -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    BasicText("Add a source to get started")
                    if (installing) {
                        BasicText("Scanning folder…")
                    } else {
                        BasicText(
                            text = "Add Local Files",
                            modifier = Modifier
                                .clickable {
                                    folderPicker.pickFolder { uri ->
                                        if (uri == null) return@pickFolder
                                        installing = true
                                        message = null
                                        scope.launch {
                                            val result = runCatching { installer.installFolder(uri) }
                                            installing = false
                                            message = result.fold(
                                                onSuccess = { "Added ${it.added} books" },
                                                onFailure = { "Error: ${it.message}" },
                                            )
                                            if (result.isSuccess) {
                                                refreshKey++
                                            }
                                        }
                                    }
                                }
                                .padding(12.dp),
                        )
                        message?.let { BasicText(it) }
                    }
                }
            }
            is HomeViewModel.StartDestination.NoLibraries -> BasicText("No libraries found")
            is HomeViewModel.StartDestination.Library -> LibraryHost(
                libraryId = dest.libraryId,
                libraryName = dest.libraryName,
            )
        }
    }
}

@Composable
private fun LibraryHost(libraryId: String, libraryName: String) {
    var nav by rememberSaveable { mutableStateOf<LibraryNav>(LibraryNav.Items) }

    when (val current = nav) {
        is LibraryNav.Items -> LibraryItemsScreen(
            libraryId = libraryId,
            libraryName = libraryName,
            onOpenDrawer = {},
            onItemSelected = {},
            onSeriesSelected = {},
            onSectionSeeMore = { sectionType -> nav = LibraryNav.Section(sectionType) },
        )
        is LibraryNav.Section -> LibrarySectionScreen(
            libraryId = libraryId,
            sectionType = current.sectionType,
            onBack = { nav = LibraryNav.Items },
            onItemSelected = {},
        )
    }
}
