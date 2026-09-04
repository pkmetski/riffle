package com.riffle.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riffle.core.data.localfiles.FolderPickerInterface
import com.riffle.core.data.localfiles.LocalFilesInstallerInterface
import com.riffle.core.models.Library
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Source
import com.riffle.feature.library.HomeViewModel
import com.riffle.feature.library.LibrarySectionType
import com.riffle.shared.audiobook.AudiobookPlayerScreen
import com.riffle.shared.library.CollectionDetailScreen
import com.riffle.shared.library.LibraryItemDetailScreen
import com.riffle.shared.library.LibraryItemsScreen
import com.riffle.shared.library.LibrarySectionScreen
import com.riffle.shared.library.SeriesDetailScreen
import com.riffle.shared.reader.EpubReaderScreen
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private sealed interface LibraryNav {
    data object Items : LibraryNav
    data class Section(val sectionType: LibrarySectionType) : LibraryNav
    data class ItemDetail(val item: LibraryItem) : LibraryNav
    data class SeriesDetail(val seriesId: String, val seriesLibraryId: String, val seriesName: String) : LibraryNav
    data class CollectionDetail(val collectionId: String, val collectionLibraryId: String, val collectionName: String) : LibraryNav
    data class Reader(val item: LibraryItem) : LibraryNav
    data class AudiobookPlayer(val item: LibraryItem) : LibraryNav
}

@Composable
fun HomeScreen() {
    val viewModel = koinInject<HomeViewModel>()
    val drawerViewModel = koinInject<DrawerViewModel>()
    val folderPicker = koinInject<FolderPickerInterface>()
    val installer = koinInject<LocalFilesInstallerInterface>()
    val scope = rememberCoroutineScope()

    var destination by remember { mutableStateOf<HomeViewModel.StartDestination?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    var installing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var activeLibraryId by remember { mutableStateOf<String?>(null) }
    var drawerOpen by remember { mutableStateOf(false) }

    val allServers by drawerViewModel.allServers.collectAsState()
    val activeServer by drawerViewModel.activeServer.collectAsState()
    val visibleLibraries by drawerViewModel.visibleLibraries.collectAsState()

    LaunchedEffect(refreshKey) {
        destination = viewModel.getStartDestination()
    }

    LaunchedEffect(drawerViewModel.redirectToLibrary) {
        drawerViewModel.redirectToLibrary.collect { library ->
            val srcType = activeServer?.type ?: return@collect
            destination = HomeViewModel.StartDestination.Library(
                sourceType = srcType,
                libraryId = library.id,
                libraryName = library.name,
            )
            activeLibraryId = library.id
            drawerViewModel.setActiveLibrary(library.id)
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Main content
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
                is HomeViewModel.StartDestination.Library -> {
                    LaunchedEffect(dest.libraryId) {
                        if (activeLibraryId == null) activeLibraryId = dest.libraryId
                    }
                    LibraryHost(
                        libraryId = dest.libraryId,
                        libraryName = dest.libraryName,
                        onOpenDrawer = { drawerOpen = true },
                    )
                }
            }
        }

        // Drawer overlay
        if (drawerOpen) {
            // Scrim
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable { drawerOpen = false },
            )
            // Drawer panel
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(280.dp)
                    .background(Color.White)
                    .align(Alignment.TopStart),
            ) {
                DrawerSheetContent(
                    activeServer = activeServer,
                    allServers = allServers,
                    visibleLibraries = visibleLibraries,
                    activeLibraryId = activeLibraryId,
                    onServerSelected = { source ->
                        drawerOpen = false
                        drawerViewModel.setActiveServer(source.id)
                        refreshKey++
                    },
                    onLibrarySelected = { library ->
                        drawerOpen = false
                        activeLibraryId = library.id
                        drawerViewModel.setActiveLibrary(library.id)
                        destination = HomeViewModel.StartDestination.Library(
                            sourceType = activeServer?.type ?: return@DrawerSheetContent,
                            libraryId = library.id,
                            libraryName = library.name,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun DrawerSheetContent(
    activeServer: Source?,
    allServers: List<Source>,
    visibleLibraries: List<Library>,
    activeLibraryId: String?,
    onServerSelected: (Source) -> Unit,
    onLibrarySelected: (Library) -> Unit,
) {
    var switcherExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxHeight()) {
        // Server header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { switcherExpanded = !switcherExpanded }
                .padding(16.dp),
        ) {
            BasicText(
                text = activeServer?.serverType?.label ?: "No source",
                style = TextStyle(fontSize = 16.sp),
            )
            val host = activeServer?.url?.authority()
            if (host != null) {
                BasicText(
                    text = host,
                    style = TextStyle(fontSize = 13.sp, color = Color.Gray),
                )
            }
            BasicText(
                text = if (switcherExpanded) "▲ Switch source" else "▼ Switch source",
                style = TextStyle(fontSize = 12.sp, color = Color.Gray),
            )
        }

        if (switcherExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF5F5F5)),
            ) {
                allServers.forEach { server ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onServerSelected(server) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            BasicText(
                                text = server.serverType.label,
                                style = TextStyle(fontSize = 14.sp),
                            )
                            BasicText(
                                text = server.url.authority(),
                                style = TextStyle(fontSize = 12.sp, color = Color.Gray),
                            )
                        }
                        if (server.isActive) {
                            BasicText(
                                text = "✓",
                                style = TextStyle(fontSize = 14.sp),
                            )
                        }
                    }
                }
            }
        }

        // Library list
        Box(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(Modifier.fillMaxWidth()) {
                visibleLibraries.forEach { library ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (library.id == activeLibraryId) Color(0xFFE8E8E8) else Color.Transparent,
                            )
                            .clickable { onLibrarySelected(library) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        BasicText(
                            text = library.name,
                            style = TextStyle(fontSize = 15.sp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryHost(
    libraryId: String,
    libraryName: String,
    onOpenDrawer: () -> Unit,
) {
    var nav by rememberSaveable { mutableStateOf<LibraryNav>(LibraryNav.Items) }

    when (val current = nav) {
        is LibraryNav.Items -> LibraryItemsScreen(
            libraryId = libraryId,
            libraryName = libraryName,
            onOpenDrawer = onOpenDrawer,
            onItemSelected = { item -> nav = LibraryNav.ItemDetail(item) },
            onSeriesSelected = { series ->
                nav = LibraryNav.SeriesDetail(
                    seriesId = series.id,
                    seriesLibraryId = series.libraryId,
                    seriesName = series.name,
                )
            },
            onCollectionSelected = { collection ->
                nav = LibraryNav.CollectionDetail(
                    collectionId = collection.id,
                    collectionLibraryId = collection.libraryId,
                    collectionName = collection.name,
                )
            },
            onSectionSeeMore = { sectionType -> nav = LibraryNav.Section(sectionType) },
        )
        is LibraryNav.Section -> LibrarySectionScreen(
            libraryId = libraryId,
            sectionType = current.sectionType,
            onBack = { nav = LibraryNav.Items },
            onItemSelected = { item -> nav = LibraryNav.ItemDetail(item) },
        )
        is LibraryNav.ItemDetail -> LibraryItemDetailScreen(
            itemId = current.item.id,
            sourceId = current.item.sourceId.ifEmpty { null },
            onBack = { nav = LibraryNav.Items },
            onReadNotSupported = {
                when {
                    current.item.isListenable -> nav = LibraryNav.AudiobookPlayer(current.item)
                    current.item.isReadable -> nav = LibraryNav.Reader(current.item)
                }
            },
        )
        is LibraryNav.Reader -> EpubReaderScreen(
            item = current.item,
            onBack = { nav = LibraryNav.Items },
        )
        is LibraryNav.AudiobookPlayer -> AudiobookPlayerScreen(
            item = current.item,
            onBack = { nav = LibraryNav.Items },
        )
        is LibraryNav.SeriesDetail -> SeriesDetailScreen(
            seriesId = current.seriesId,
            libraryId = current.seriesLibraryId,
            seriesName = current.seriesName,
            onItemSelected = {},
            onNavigateBack = { nav = LibraryNav.Items },
        )
        is LibraryNav.CollectionDetail -> CollectionDetailScreen(
            collectionId = current.collectionId,
            libraryId = current.collectionLibraryId,
            collectionName = current.collectionName,
            onItemSelected = {},
            onNavigateBack = { nav = LibraryNav.Items },
        )
    }
}
