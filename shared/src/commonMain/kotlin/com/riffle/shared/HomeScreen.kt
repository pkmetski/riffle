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
import androidx.compose.runtime.DisposableEffect
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
import com.riffle.feature.designsystem.KoFiDrawerButton
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.WebSourceDescriptors
import com.riffle.core.domain.usecase.RecordItemOpened
import com.riffle.core.models.Library
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.feature.library.AnnotationSearchViewModel
import com.riffle.feature.library.FilteredBooksViewModel
import com.riffle.feature.library.HomeViewModel
import com.riffle.feature.library.PlaylistDetailViewModel
import com.riffle.feature.library.shouldShowRiffleSource
import com.riffle.feature.library.ui.AnnotationSearchLabels
import com.riffle.feature.library.ui.AnnotationSearchResultsScreen
import com.riffle.feature.library.ui.FilteredBooksLabels
import com.riffle.feature.library.ui.FilteredBooksScreen
import com.riffle.feature.library.ui.PlaylistDetailScreen
import com.riffle.feature.library.ui.PlaylistItemRow
import com.riffle.feature.library.ui.PlaylistLabels
import com.riffle.feature.source.ui.localizedSourceDisplayName
import com.riffle.shared.downloads.DownloadsScreen
import com.riffle.feature.designsystem.BookCoverTile
import com.riffle.feature.designsystem.coverGridMinCell
import com.riffle.shared.library.CollectionDetailScreen
import com.riffle.shared.library.LibraryItemDetailScreen
import com.riffle.shared.library.LibraryItemsScreen
import com.riffle.shared.library.LibrarySectionScreen
import com.riffle.shared.library.RiffleScreen
import com.riffle.shared.library.SeriesDetailScreen
import com.riffle.shared.settings.SettingsScreen
import com.riffle.shared.source.SourceOnboardingHost
import com.riffle.shared.source.UnboundedBrowseScreen
import com.riffle.shared.source.shouldRenderUnboundedBrowse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.compose.getKoin
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

private enum class AppSection { Library, Settings, Downloads, Riffle }

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
                            sourceType = dest.sourceType,
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

        if (shouldShowRiffleSource(allServers.size)) {
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
        KoFiDrawerButton()
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun LibraryHost(
    sourceType: SourceType?,
    libraryId: String,
    libraryName: String,
    onOpenDrawer: () -> Unit,
) {
    var nav by rememberSaveable { mutableStateOf<LibraryNav>(LibraryNav.Items) }
    val applicationScope = koinInject<ApplicationScope>()
    val recordItemOpened = koinInject<RecordItemOpened>()
    val unboundedType = sourceType.takeIf { shouldRenderUnboundedBrowse(it) }

    when (val current = nav) {
        // Unbounded catalogues have no `library_items` mirror to render (ADR 0051), so they get
        // the browse surface instead of the Room-backed library screen — the same fork Android's
        // `NavRoutes.libraryEntryRoute` makes off `SourceType.isUnboundedCatalog`. Without it the
        // iOS host rendered `LibraryItemsScreen` for every library and Chitanka / Gutenberg /
        // radio.es showed a permanently empty one (#1071 §17).
        is LibraryNav.Items -> if (unboundedType != null) {
            UnboundedBrowseScreen(
                sourceType = unboundedType,
                libraryId = libraryId,
                libraryName = libraryName,
                onOpenDrawer = onOpenDrawer,
                onOpenDetail = { itemId -> nav = LibraryNav.ItemDetail(itemId, null) },
            )
        } else {
            LibraryItemsScreen(
                libraryId = libraryId,
                libraryName = libraryName,
                onOpenDrawer = onOpenDrawer,
                onItemSelected = { item -> nav = LibraryNav.ItemDetail(item.id, item.sourceId.ifEmpty { null }) },
                onAnnotatedBookSelected = { sourceId, itemId ->
                    nav = LibraryNav.ItemDetail(itemId, sourceId.ifEmpty { null })
                },
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
                onSearchAnnotations = { query -> nav = LibraryNav.AnnotationSearch(libraryId, query) },
                onPlaylistSelected = { playlist ->
                    nav = LibraryNav.PlaylistDetail(
                        playlistId = playlist.id,
                        playlistName = playlist.name,
                        // The playlist's own rootId, not the host's libraryId: the Playlists tab
                        // is only visible on an ABS audiobook root and the two are the same
                        // today, but every PlaylistsRepository call keys on the playlist's root.
                        playlistLibraryId = playlist.rootId.ifEmpty { libraryId },
                    )
                },
            )
        }
        is LibraryNav.Section -> LibrarySectionScreen(
            libraryId = libraryId,
            sectionType = current.sectionType,
            onBack = { nav = LibraryNav.Items },
            onItemSelected = { item -> nav = LibraryNav.ItemDetail(item.id, item.sourceId.ifEmpty { null }) },
        )
        is LibraryNav.ItemDetail -> LibraryItemDetailScreen(
            itemId = current.itemId,
            sourceId = current.sourceId,
            onBack = { nav = LibraryNav.Items },
            // Stay on the sheet when the format has no iOS reader rather than dismissing it.
            onRead = { item ->
                openItemForReading(item, applicationScope, recordItemOpened::invoke)?.let { nav = it }
            },
            onFacetSelected = { facetLibraryId, facet, value ->
                nav = LibraryNav.FilteredBooks(facetLibraryId, facet, value)
            },
        )
        is LibraryNav.FilteredBooks -> FilteredBooksHost(
            destination = current,
            onBack = { nav = LibraryNav.Items },
            onItemSelected = { item -> nav = LibraryNav.ItemDetail(item.id, item.sourceId.ifEmpty { null }) },
        )
        is LibraryNav.AnnotationSearch -> AnnotationSearchHost(
            destination = current,
            onBack = { nav = LibraryNav.Items },
            // Android opens the reader at the annotation's CFI; iOS's reader has no
            // open-at-annotation entry point yet (#1072 §2 — the whole annotation seam is
            // missing there), so a result opens the book's detail sheet, which is the furthest
            // the iOS reader can currently be driven from outside.
            onOpenBook = { sourceId, itemId ->
                nav = LibraryNav.ItemDetail(itemId, sourceId.ifEmpty { null })
            },
        )
        is LibraryNav.ReaderDestination -> {
            // End-of-book inside a playlist: the ViewModel has already found the next item id;
            // this resolves it to a LibraryItem and re-enters the player carrying the same
            // playlist context, so the chain continues. Android does the equivalent by
            // navigating to the next player route with `popUpTo(AUDIOBOOK_PLAYER)`; replacing
            // `nav` in place is this host's equivalent of that pop.
            val libraryObserver = koinInject<LibraryObserver>()
            var advanceToItemId by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(advanceToItemId) {
                val nextItemId = advanceToItemId ?: return@LaunchedEffect
                val context = current as? LibraryNav.AudiobookPlayer ?: return@LaunchedEffect
                val rootId = context.playlistLibraryId ?: return@LaunchedEffect
                advanceToItemId = null
                val next = libraryObserver.observeLibraryItems(rootId).first()
                    .firstOrNull { it.id == nextItemId }
                nav = playlistAdvanceNav(next, context)
            }
            ReaderHost(
                destination = current,
                onBack = { nav = LibraryNav.Items },
                onPlaylistAdvance = { _, nextItemId -> advanceToItemId = nextItemId },
            )
        }
        is LibraryNav.SeriesDetail -> SeriesDetailScreen(
            seriesId = current.seriesId,
            libraryId = current.seriesLibraryId,
            seriesName = current.seriesName,
            onItemSelected = { item -> nav = LibraryNav.ItemDetail(item.id, item.sourceId.ifEmpty { null }) },
            onNavigateBack = { nav = LibraryNav.Items },
        )
        is LibraryNav.CollectionDetail -> CollectionDetailScreen(
            collectionId = current.collectionId,
            libraryId = current.collectionLibraryId,
            collectionName = current.collectionName,
            onItemSelected = { item -> nav = LibraryNav.ItemDetail(item.id, item.sourceId.ifEmpty { null }) },
            onNavigateBack = { nav = LibraryNav.Items },
        )
        is LibraryNav.PlaylistDetail -> PlaylistDetailHost(
            destination = current,
            onBack = { nav = LibraryNav.Items },
            onItemSelected = { item -> nav = LibraryNav.ItemDetail(item.id, item.sourceId.ifEmpty { null }) },
            // "Play" carries the playlist context into the player, which is what makes
            // AudiobookPlayerViewModel's end-of-book auto-advance reachable on iOS at all.
            onPlayItem = { item -> nav = playlistPlayerNav(item, current) },
        )
    }
}

/**
 * Screen-scoped host for [FilteredBooksScreen].
 *
 * Like the playlist host: the ViewModel is a Koin `factory` keyed on the facet, and iOS has no
 * navigation-provided `ViewModelStoreOwner` to call `onCleared()`.
 *
 * `internal` rather than private because both iOS hosts reach it: the per-library browser here
 * and the Riffle hub's detail sheet, whose facet chips drill into the same screen.
 */
@Composable
internal fun FilteredBooksHost(
    destination: LibraryNav.FilteredBooks,
    onBack: () -> Unit,
    onItemSelected: (LibraryItem) -> Unit,
) {
    val koin = getKoin()
    val key = "${destination.facetLibraryId}/${destination.facetType}/${destination.facetValue}"
    val host = remember(key) { ScreenScopedViewModelHost() }
    val viewModel: FilteredBooksViewModel = remember(key) {
        host.adopt(
            koin.get {
                parametersOf(
                    destination.facetLibraryId,
                    destination.facetType.name,
                    destination.facetValue,
                )
            },
        )
    }
    DisposableEffect(key) { onDispose { host.clear() } }
    FilteredBooksScreen(
        viewModel = viewModel,
        labels = FilteredBooksLabels.English,
        minCellSize = coverGridMinCell(),
        onItemSelected = onItemSelected,
        onNavigateBack = onBack,
        tileContent = { item, token, onClick ->
            BookCoverTile(item = item, token = token, onClick = onClick)
        },
    )
}

/**
 * Screen-scoped host for [AnnotationSearchResultsScreen].
 *
 * Same reason as the other two hosts: the ViewModel is a Koin `factory` keyed on the query and
 * iOS has no navigation-provided `ViewModelStoreOwner`.
 */
@Composable
internal fun AnnotationSearchHost(
    destination: LibraryNav.AnnotationSearch,
    onBack: () -> Unit,
    onOpenBook: (sourceId: String, itemId: String) -> Unit,
) {
    val koin = getKoin()
    val key = "${destination.searchLibraryId}/${destination.query}"
    val host = remember(key) { ScreenScopedViewModelHost() }
    val viewModel: AnnotationSearchViewModel = remember(key) {
        host.adopt(koin.get { parametersOf(destination.searchLibraryId, destination.query) })
    }
    DisposableEffect(key) { onDispose { host.clear() } }
    AnnotationSearchResultsScreen(
        viewModel = viewModel,
        labels = AnnotationSearchLabels.English,
        onNavigateBack = onBack,
        onAnnotationSelected = { result -> onOpenBook(result.annotation.sourceId, result.annotation.itemId) },
        onAudiobookBookmarkSelected = { result -> onOpenBook(result.bookmark.sourceId, result.bookmark.itemId) },
    )
}

/**
 * Screen-scoped host for [PlaylistDetailScreen].
 *
 * `PlaylistDetailViewModel` is a Koin `factory` keyed on the playlist's route arguments and iOS
 * has no navigation-provided `ViewModelStoreOwner`, so without [ScreenScopedViewModelHost]
 * nothing would ever call `onCleared()` and each visit would leak a live `stateIn` collector.
 */
@Composable
private fun PlaylistDetailHost(
    destination: LibraryNav.PlaylistDetail,
    onBack: () -> Unit,
    onItemSelected: (LibraryItem) -> Unit,
    onPlayItem: (LibraryItem) -> Unit,
) {
    val koin = getKoin()
    val key = destination.playlistLibraryId + "/" + destination.playlistId
    val host = remember(key) { ScreenScopedViewModelHost() }
    val viewModel: PlaylistDetailViewModel = remember(key) {
        host.adopt(
            koin.get {
                parametersOf(destination.playlistLibraryId, destination.playlistId, destination.playlistName)
            },
        )
    }
    DisposableEffect(key) { onDispose { host.clear() } }
    PlaylistDetailScreen(
        viewModel = viewModel,
        labels = PlaylistLabels.English,
        onNavigateBack = onBack,
        onItemSelected = onItemSelected,
        onPlayItem = onPlayItem,
        itemContent = { item, _, onClick -> PlaylistItemRow(item = item, onClick = onClick) },
    )
}
