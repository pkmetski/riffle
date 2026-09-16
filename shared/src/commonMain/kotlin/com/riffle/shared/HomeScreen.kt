package com.riffle.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.WebSourceDescriptors
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.Library
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Source
import com.riffle.feature.library.HomeViewModel
import com.riffle.feature.library.LibrarySectionType
import com.riffle.feature.source.ui.localizedSourceDisplayName
import com.riffle.shared.audiobook.AudiobookPlayerScreen
import com.riffle.shared.downloads.DownloadsScreen
import com.riffle.shared.library.CollectionDetailScreen
import com.riffle.shared.library.LibraryItemDetailScreen
import com.riffle.shared.library.LibraryItemsScreen
import com.riffle.shared.library.LibrarySectionScreen
import com.riffle.shared.library.RiffleScreen
import com.riffle.shared.library.SeriesDetailScreen
import com.riffle.shared.reader.CbzReaderScreen
import com.riffle.shared.reader.EpubReaderScreen
import com.riffle.shared.reader.PdfReaderScreen
import com.riffle.shared.settings.SettingsScreen
import com.riffle.shared.source.SourceOnboardingHost
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.compose.koinInject

private enum class AppSection { Library, Settings, Downloads, Riffle }

internal sealed interface LibraryNav {
    data object Items : LibraryNav
    data class Section(val sectionType: LibrarySectionType) : LibraryNav
    data class ItemDetail(val item: LibraryItem) : LibraryNav
    data class SeriesDetail(val seriesId: String, val seriesLibraryId: String, val seriesName: String) : LibraryNav
    data class CollectionDetail(val collectionId: String, val collectionLibraryId: String, val collectionName: String) : LibraryNav
    data class Reader(val item: LibraryItem) : LibraryNav
    data class PdfReader(val item: LibraryItem) : LibraryNav
    data class CbzReader(val item: LibraryItem) : LibraryNav
    data class AudiobookPlayer(val item: LibraryItem) : LibraryNav
}

internal fun readerNavForItem(item: LibraryItem): LibraryNav? = when {
    item.isListenable -> LibraryNav.AudiobookPlayer(item)
    item.ebookFormat == EbookFormat.Pdf -> LibraryNav.PdfReader(item)
    item.ebookFormat == EbookFormat.Cbz -> LibraryNav.CbzReader(item)
    item.isReadable -> LibraryNav.Reader(item)
    else -> null
}

@Composable
fun HomeScreen() {
    val viewModel = koinInject<HomeViewModel>()
    val drawerViewModel = koinInject<DrawerViewModel>()

    val scope = rememberCoroutineScope()
    var appSection by rememberSaveable { mutableStateOf(AppSection.Library) }
    var destination by remember { mutableStateOf<HomeViewModel.StartDestination?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    var activeLibraryId by remember { mutableStateOf<String?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)

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

    val drawerEnabled = appSection == AppSection.Library || appSection == AppSection.Riffle

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerEnabled,
        drawerContent = {
            ModalDrawerSheet {
                DrawerSheetContent(
                    activeServer = activeServer,
                    allServers = allServers,
                    visibleLibraries = visibleLibraries,
                    activeLibraryId = activeLibraryId,
                    isRiffleActive = appSection == AppSection.Riffle,
                    onNavigateToRiffle = {
                        scope.launch { drawerState.close() }
                        drawerViewModel.setRiffleActive()
                        appSection = AppSection.Riffle
                    },
                    onServerSelected = { source ->
                        scope.launch { drawerState.close() }
                        appSection = AppSection.Library
                        drawerViewModel.setActiveServer(source.id)
                        scope.launch {
                            withTimeoutOrNull(5_000) {
                                drawerViewModel.activeServer.first { it?.id == source.id }
                            }
                            refreshKey++
                        }
                    },
                    onLibrarySelected = { library ->
                        scope.launch { drawerState.close() }
                        activeLibraryId = library.id
                        drawerViewModel.setActiveLibrary(library.id)
                        destination = HomeViewModel.StartDestination.Library(
                            sourceType = activeServer?.type ?: return@DrawerSheetContent,
                            libraryId = library.id,
                            libraryName = library.name,
                        )
                    },
                    onNavigateToSettings = {
                        scope.launch { drawerState.close() }
                        appSection = AppSection.Settings
                    },
                    onNavigateToDownloads = {
                        scope.launch { drawerState.close() }
                        appSection = AppSection.Downloads
                    },
                )
            }
        },
    ) {
        when (appSection) {
            AppSection.Settings -> SettingsScreen(onBack = { appSection = AppSection.Library })
            AppSection.Downloads -> DownloadsScreen(onBack = { appSection = AppSection.Library })
            AppSection.Riffle -> RiffleScreen(
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onBack = { appSection = AppSection.Library },
            )
            AppSection.Library -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (val dest = destination) {
                    null -> Text("Loading…")
                    is HomeViewModel.StartDestination.AddSource ->
                        SourceOnboardingHost(
                            onFinished = { refreshKey++ },
                            onCancelled = { refreshKey++ },
                        )
                    is HomeViewModel.StartDestination.Riffle -> {
                        LaunchedEffect(Unit) { appSection = AppSection.Riffle }
                    }
                    is HomeViewModel.StartDestination.NoLibraries -> Text("No libraries found")
                    is HomeViewModel.StartDestination.Library -> {
                        LaunchedEffect(dest.libraryId) {
                            if (activeLibraryId == null) activeLibraryId = dest.libraryId
                        }
                        LibraryHost(
                            libraryId = dest.libraryId,
                            libraryName = dest.libraryName,
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                        )
                    }
                }
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
    isRiffleActive: Boolean = false,
    onNavigateToRiffle: () -> Unit = {},
    onServerSelected: (Source) -> Unit,
    onLibrarySelected: (Library) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToDownloads: () -> Unit,
) {
    var switcherExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))

        if (allServers.size >= 2) {
            NavigationDrawerItem(
                label = { Text("Riffle") },
                selected = isRiffleActive,
                onClick = onNavigateToRiffle,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        // Server switcher header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { switcherExpanded = !switcherExpanded }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = activeServer?.let { localizedSourceDisplayName(it) } ?: "No source",
                style = MaterialTheme.typography.titleMedium,
            )
            // Only show the host for sources that have a real network address; zero-config
            // singletons (Chitanka, Gutenberg, radio.es) carry a fake `.invalid` host.
            val host = activeServer
                ?.takeIf { WebSourceDescriptors.forType(it.type)?.hasCredentials == true }
                ?.url?.authority()
            if (host != null) {
                Text(
                    text = host,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = if (switcherExpanded) "▲ Switch source" else "▼ Switch source",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (switcherExpanded) {
            allServers.forEach { server ->
                NavigationDrawerItem(
                    label = {
                        Column {
                            Text(localizedSourceDisplayName(server), style = MaterialTheme.typography.bodyMedium)
                            val rowHost = server
                                .takeIf { WebSourceDescriptors.forType(it.type)?.hasCredentials == true }
                                ?.url?.authority()
                            if (rowHost != null) {
                                Text(rowHost, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    selected = server.isActive,
                    onClick = { onServerSelected(server) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        // Library list
        visibleLibraries.forEach { library ->
            NavigationDrawerItem(
                label = { Text(library.name) },
                selected = library.id == activeLibraryId,
                onClick = { onLibrarySelected(library) },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        NavigationDrawerItem(
            label = { Text("Downloads") },
            selected = false,
            onClick = onNavigateToDownloads,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        NavigationDrawerItem(
            label = { Text("Settings") },
            selected = false,
            onClick = onNavigateToSettings,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(12.dp))
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
                readerNavForItem(current.item)?.let { nav = it }
            },
        )
        is LibraryNav.Reader -> EpubReaderScreen(
            item = current.item,
            onBack = { nav = LibraryNav.Items },
        )
        is LibraryNav.PdfReader -> PdfReaderScreen(
            item = current.item,
            onBack = { nav = LibraryNav.Items },
        )
        is LibraryNav.CbzReader -> CbzReaderScreen(
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
            onItemSelected = { item -> nav = LibraryNav.ItemDetail(item) },
            onNavigateBack = { nav = LibraryNav.Items },
        )
        is LibraryNav.CollectionDetail -> CollectionDetailScreen(
            collectionId = current.collectionId,
            libraryId = current.collectionLibraryId,
            collectionName = current.collectionName,
            onItemSelected = { item -> nav = LibraryNav.ItemDetail(item) },
            onNavigateBack = { nav = LibraryNav.Items },
        )
    }
}
