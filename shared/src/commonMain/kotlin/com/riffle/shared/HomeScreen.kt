package com.riffle.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import com.riffle.core.data.localfiles.FolderPickerInterface
import com.riffle.core.data.localfiles.LocalFilesInstallerInterface
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.usecase.RecordItemOpened
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.SourceType
import com.riffle.feature.designsystem.BookCoverTile
import com.riffle.feature.designsystem.coverGridMinCell
import com.riffle.feature.downloads.DownloadsViewModel
import com.riffle.feature.library.AnnotationSearchViewModel
import com.riffle.feature.library.FilteredBooksViewModel
import com.riffle.feature.library.HomeViewModel
import com.riffle.feature.library.PlaylistDetailViewModel
import com.riffle.feature.library.RiffleViewModel
import com.riffle.feature.library.ui.AnnotationSearchLabels
import com.riffle.feature.library.ui.AnnotationSearchResultsScreen
import com.riffle.feature.library.ui.CollectionDetailScreen
import com.riffle.feature.library.ui.DownloadsScreen
import com.riffle.feature.library.ui.FilteredBooksLabels
import com.riffle.feature.library.ui.FilteredBooksScreen
import com.riffle.feature.library.ui.LibraryItemsScreen
import com.riffle.feature.library.ui.LibrarySectionScreen
import com.riffle.feature.library.ui.PlaylistDetailScreen
import com.riffle.feature.library.ui.PlaylistItemRow
import com.riffle.feature.library.ui.PlaylistLabels
import com.riffle.feature.library.ui.RiffleNavigationDrawer
import com.riffle.feature.library.ui.RiffleScreen
import com.riffle.feature.library.ui.SeriesDetailScreen
import com.riffle.feature.library.ui.generated.resources.Res
import com.riffle.feature.library.ui.generated.resources.ui_retry
import com.riffle.feature.library.ui.generated.resources.ui_unable_to_connect_to_source
import com.riffle.feature.navigation.NavigationDrawerViewModel
import com.riffle.feature.reader.highlights.ReaderSource
import com.riffle.feature.settings.ui.DefaultPlatformSettingsHooks
import com.riffle.feature.settings.ui.SettingsScreen
import com.riffle.feature.settings.ui.annotationsync.AnnotationsSyncSettingsScreen
import com.riffle.feature.settings.ui.changelog.ChangelogScreen
import com.riffle.feature.settings.ui.changelog.ChangelogViewModel
import com.riffle.feature.settings.ui.readaloud.ReadaloudSettingsScreen
import com.riffle.shared.library.LibraryItemDetailScreen
import com.riffle.shared.reader.EpubReaderScreen
import com.riffle.shared.source.SourceOnboardingHost
import com.riffle.shared.source.UnboundedBrowseScreen
import com.riffle.shared.source.shouldRenderUnboundedBrowse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.getKoin
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

private enum class AppSection { Library, Settings, Downloads, Riffle }

private sealed interface IosSettingsSubScreen {
    data object AddSource : IosSettingsSubScreen
    data object AnnotationsSync : IosSettingsSubScreen
    data object ReadaloudSettings : IosSettingsSubScreen
    data object Changelog : IosSettingsSubScreen
}

@Composable
fun HomeScreen() {
    val viewModel = koinInject<HomeViewModel>()
    val drawerViewModel = koinInject<NavigationDrawerViewModel>()

    val scope = rememberCoroutineScope()
    var appSection by rememberSaveable { mutableStateOf(AppSection.Library) }
    var settingsSubScreen by remember { mutableStateOf<IosSettingsSubScreen?>(null) }
    var destination by remember { mutableStateOf<HomeViewModel.StartDestination?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    var activeLibraryId by remember { mutableStateOf<String?>(null) }
    var isInReaderDestination by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    val density = LocalDensity.current.density
    val containerSize = LocalWindowInfo.current.containerSize
    val isTabletLayout = (containerSize.width / density) >= 840f && (containerSize.height / density) >= 480f

    val allServers by drawerViewModel.allServers.collectAsState()
    val activeServer by drawerViewModel.activeServer.collectAsState()
    val visibleLibraries by drawerViewModel.visibleLibraries.collectAsState()
    val isRiffleMode by drawerViewModel.isRiffleMode.collectAsState()
    val showDownloadsLink by drawerViewModel.showDownloadsLink.collectAsState()
    val serverVersions by drawerViewModel.serverVersions.collectAsState()

    LaunchedEffect(refreshKey) {
        destination = viewModel.getStartDestination()
    }

    LaunchedEffect(drawerViewModel) {
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

    RiffleNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerEnabled,
        usePermanentDrawer = isTabletLayout,
        hidePermanentDrawerPanel = isTabletLayout && isInReaderDestination,
        activeServer = activeServer,
        allServers = allServers,
        visibleLibraries = visibleLibraries,
        activeLibraryId = activeLibraryId,
        serverVersions = serverVersions,
        showDownloadsLink = showDownloadsLink,
        isRiffleActive = appSection == AppSection.Riffle || isRiffleMode,
        onRiffleSelected = {
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
            val srcType = activeServer?.type
            if (srcType != null) {
                activeLibraryId = library.id
                drawerViewModel.setActiveLibrary(library.id)
                destination = HomeViewModel.StartDestination.Library(
                    sourceType = srcType,
                    libraryId = library.id,
                    libraryName = library.name,
                )
            }
        },
        onSettingsSelected = {
            scope.launch { drawerState.close() }
            appSection = AppSection.Settings
        },
        onDownloadsSelected = {
            scope.launch { drawerState.close() }
            appSection = AppSection.Downloads
        },
    ) {
        when (appSection) {
            AppSection.Settings -> {
                val folderPicker = koinInject<FolderPickerInterface>()
                val localFilesInstaller = koinInject<LocalFilesInstallerInterface>()
                when (val sub = settingsSubScreen) {
                    IosSettingsSubScreen.AddSource -> SourceOnboardingHost(
                        onFinished = { settingsSubScreen = null },
                        onCancelled = { settingsSubScreen = null },
                    )
                    IosSettingsSubScreen.AnnotationsSync -> AnnotationsSyncSettingsScreen(
                        onNavigateBack = { settingsSubScreen = null },
                        onNavigateToAddSource = { _, _ -> settingsSubScreen = IosSettingsSubScreen.AddSource },
                        onNavigateToMaintenance = { /* no-op: maintenance screen is Android-only for now */ },
                    )
                    IosSettingsSubScreen.ReadaloudSettings -> ReadaloudSettingsScreen(
                        onNavigateBack = { settingsSubScreen = null },
                        onNavigateToAddSource = { _, _ -> settingsSubScreen = IosSettingsSubScreen.AddSource },
                        onNavigateToReadaloudMatches = { /* no-op on iOS */ },
                    )
                    IosSettingsSubScreen.Changelog -> {
                        val changelogViewModel = koinInject<ChangelogViewModel>()
                        ChangelogScreen(
                            onNavigateBack = { settingsSubScreen = null },
                            viewModel = changelogViewModel,
                        )
                    }
                    null -> SettingsScreen(
                        isExpandedWidth = false,
                        onNavigateBack = { appSection = AppSection.Library },
                        onNavigateToAddSource = { _, _ -> settingsSubScreen = IosSettingsSubScreen.AddSource },
                        onNavigateToAddSourcePicker = { settingsSubScreen = IosSettingsSubScreen.AddSource },
                        onNavigateToAddLocalFolder = {
                            folderPicker.pickFolder { uri ->
                                if (uri != null) scope.launch { runCatching { localFilesInstaller.installFolder(uri) } }
                            }
                        },
                        onNavigateToReadaloudSettings = { settingsSubScreen = IosSettingsSubScreen.ReadaloudSettings },
                        onNavigateToAnnotationsSyncSettings = { settingsSubScreen = IosSettingsSubScreen.AnnotationsSync },
                        onNavigateToDeveloperOptions = { /* no-op: developer options is Android-only */ },
                        onNavigateToDictionaryPacks = { /* no-op: dictionary packs is Android-only */ },
                        onNavigateToDebugLogs = { /* no-op: debug logs is Android-only */ },
                        onNavigateToChangelog = { settingsSubScreen = IosSettingsSubScreen.Changelog },
                        platformHooks = DefaultPlatformSettingsHooks,
                    )
                }
            }
            AppSection.Downloads -> {
                val downloadsViewModel = koinInject<DownloadsViewModel>()
                DownloadsScreen(
                    onNavigateBack = { appSection = AppSection.Library },
                    onItemSelected = { item ->
                        // Navigate to item detail via library section
                        appSection = AppSection.Library
                    },
                    viewModel = downloadsViewModel,
                )
            }
            AppSection.Riffle -> {
                val riffleViewModel = koinInject<RiffleViewModel>()
                val riffleApplicationScope = koinInject<ApplicationScope>()
                val riffleRecordItemOpened = koinInject<RecordItemOpened>()
                var riffleNav by remember { mutableStateOf<LibraryNav?>(null) }
                when (val current = riffleNav) {
                    is LibraryNav.ItemDetail -> LibraryItemDetailScreen(
                        itemId = current.itemId,
                        sourceId = current.sourceId,
                        onBack = { riffleNav = null },
                        onRead = { item ->
                            openItemForReading(item, riffleApplicationScope, riffleRecordItemOpened::invoke)?.let { riffleNav = it }
                        },
                        onFacetSelected = { facetLibraryId, facet, value ->
                            riffleNav = LibraryNav.FilteredBooks(facetLibraryId, facet, value)
                        },
                    )
                    is LibraryNav.FilteredBooks -> FilteredBooksHost(
                        destination = current,
                        onBack = { riffleNav = null },
                        onItemSelected = { item -> riffleNav = LibraryNav.ItemDetail(item.id, item.sourceId.ifEmpty { null }) },
                    )
                    is LibraryNav.ReaderDestination -> ReaderHost(
                        destination = current,
                        onBack = { riffleNav = null },
                        onPlaylistAdvance = { _, _ -> },
                    )
                    else -> RiffleScreen(
                        viewModel = riffleViewModel,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onItemSelected = { sourceId, itemId ->
                            riffleNav = LibraryNav.ItemDetail(itemId, sourceId.ifEmpty { null })
                        },
                    )
                }
            }
            AppSection.Library -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (val dest = destination) {
                    null -> CircularProgressIndicator()
                    is HomeViewModel.StartDestination.AddSource ->
                        SourceOnboardingHost(
                            onFinished = { refreshKey++ },
                            onCancelled = { refreshKey++ },
                            canNavigateBack = false,
                        )
                    is HomeViewModel.StartDestination.Riffle -> {
                        LaunchedEffect(Unit) { appSection = AppSection.Riffle }
                    }
                    is HomeViewModel.StartDestination.NoLibraries -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(Res.string.ui_unable_to_connect_to_source))
                        Button(onClick = { refreshKey++ }) { Text(stringResource(Res.string.ui_retry)) }
                    }
                    is HomeViewModel.StartDestination.Library -> {
                        LaunchedEffect(dest.libraryId) {
                            if (activeLibraryId == null) activeLibraryId = dest.libraryId
                        }
                        LibraryHost(
                            sourceType = dest.sourceType,
                            libraryId = dest.libraryId,
                            libraryName = dest.libraryName,
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onReaderActiveChanged = { isInReaderDestination = it },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryHost(
    sourceType: SourceType?,
    libraryId: String,
    libraryName: String,
    onOpenDrawer: () -> Unit,
    onReaderActiveChanged: (Boolean) -> Unit = {},
) {
    // rememberSaveable cannot be used here: LibraryNav.ReaderDestination carries a LibraryItem
    // which is not Parcelable/Serializable, so the stack would crash on process death.
    // The tradeoff (back stack reset on process kill) is acceptable for iOS.
    var navStack by remember { mutableStateOf(listOf<LibraryNav>(LibraryNav.Items)) }
    val applicationScope = koinInject<ApplicationScope>()
    val recordItemOpened = koinInject<RecordItemOpened>()
    val unboundedType = sourceType.takeIf { shouldRenderUnboundedBrowse(it) }

    fun push(dest: LibraryNav) {
        navStack = navStack + dest
    }
    fun pop() {
        navStack = navStack.dropLast(1).ifEmpty { listOf(LibraryNav.Items) }
    }

    SideEffect { onReaderActiveChanged(navStack.last() is LibraryNav.ReaderDestination) }
    DisposableEffect(Unit) { onDispose { onReaderActiveChanged(false) } }

    when (val current = navStack.last()) {
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
                onOpenDetail = { itemId -> push(LibraryNav.ItemDetail(itemId, null)) },
                onSearchAnnotations = { query -> push(LibraryNav.AnnotationSearch(libraryId, query)) },
            )
        } else {
            LibraryItemsScreen(
                libraryId = libraryId,
                libraryName = libraryName,
                onOpenDrawer = onOpenDrawer,
                showRecentlyAdded = sourceType?.isWebSource != true,
                onItemSelected = { item -> push(LibraryNav.ItemDetail(item.id, item.sourceId.ifEmpty { null })) },
                onAnnotatedBookSelected = { sourceId, itemId ->
                    push(LibraryNav.ElidedReader(itemId, sourceId.ifEmpty { null }))
                },
                onSeriesSelected = { series ->
                    push(
                        LibraryNav.SeriesDetail(
                            seriesId = series.id,
                            seriesLibraryId = series.libraryId,
                            seriesName = series.name,
                        )
                    )
                },
                onCollectionSelected = { collection ->
                    push(
                        LibraryNav.CollectionDetail(
                            collectionId = collection.id,
                            collectionLibraryId = collection.libraryId,
                            collectionName = collection.name,
                        )
                    )
                },
                onSectionSeeMore = { sectionType -> push(LibraryNav.Section(sectionType)) },
                onSearchAnnotations = { query -> push(LibraryNav.AnnotationSearch(libraryId, query)) },
                onPlaylistSelected = { playlist ->
                    push(
                        LibraryNav.PlaylistDetail(
                            playlistId = playlist.id,
                            playlistName = playlist.name,
                            // The playlist's own rootId, not the host's libraryId: the Playlists tab
                            // is only visible on an ABS audiobook root and the two are the same
                            // today, but every PlaylistsRepository call keys on the playlist's root.
                            playlistLibraryId = playlist.rootId.ifEmpty { libraryId },
                        )
                    )
                },
            )
        }
        is LibraryNav.Section -> LibrarySectionScreen(
            libraryId = libraryId,
            sectionType = current.sectionType,
            onNavigateBack = ::pop,
            onItemSelected = { item -> push(LibraryNav.ItemDetail(item.id, item.sourceId.ifEmpty { null })) },
        )
        is LibraryNav.ItemDetail -> LibraryItemDetailScreen(
            itemId = current.itemId,
            sourceId = current.sourceId,
            onBack = ::pop,
            // Stay on the sheet when the format has no iOS reader rather than dismissing it.
            onRead = { item ->
                openItemForReading(item, applicationScope, recordItemOpened::invoke)?.let { push(it) }
            },
            onFacetSelected = { facetLibraryId, facet, value ->
                push(LibraryNav.FilteredBooks(facetLibraryId, facet, value))
            },
        )
        is LibraryNav.FilteredBooks -> FilteredBooksHost(
            destination = current,
            onBack = ::pop,
            onItemSelected = { item -> push(LibraryNav.ItemDetail(item.id, item.sourceId.ifEmpty { null })) },
        )
        is LibraryNav.AnnotationSearch -> AnnotationSearchHost(
            destination = current,
            onBack = ::pop,
            onOpenBook = { sourceId, itemId ->
                push(LibraryNav.ElidedReader(itemId, sourceId.ifEmpty { null }))
            },
        )
        is LibraryNav.ElidedReader -> ElidedReaderLoader(
            itemId = current.itemId,
            sourceId = current.sourceId,
            onBack = ::pop,
        )
        is LibraryNav.ReaderDestination -> {
            // End-of-book inside a playlist: the ViewModel has already found the next item id;
            // this resolves it to a LibraryItem and re-enters the player carrying the same
            // playlist context, so the chain continues. Android does the equivalent by
            // navigating to the next player route with `popUpTo(AUDIOBOOK_PLAYER)`; replacing
            // the stack top in place is this host's equivalent of that pop.
            val libraryObserver = koinInject<LibraryObserver>()
            var advanceToItemId by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(advanceToItemId) {
                val nextItemId = advanceToItemId ?: return@LaunchedEffect
                val context = current as? LibraryNav.AudiobookPlayer ?: return@LaunchedEffect
                val rootId = context.playlistLibraryId ?: return@LaunchedEffect
                advanceToItemId = null
                val next = libraryObserver.observeLibraryItems(rootId).first()
                    .firstOrNull { it.id == nextItemId }
                val nextNav = playlistAdvanceNav(next, context)
                navStack = navStack.dropLast(1) + nextNav
            }
            ReaderHost(
                destination = current,
                onBack = ::pop,
                onPlaylistAdvance = { _, nextItemId -> advanceToItemId = nextItemId },
            )
        }
        is LibraryNav.SeriesDetail -> SeriesDetailScreen(
            seriesId = current.seriesId,
            libraryId = current.seriesLibraryId,
            seriesName = current.seriesName,
            onItemSelected = { item -> push(LibraryNav.ItemDetail(item.id, item.sourceId.ifEmpty { null })) },
            onNavigateBack = ::pop,
        )
        is LibraryNav.CollectionDetail -> CollectionDetailScreen(
            collectionId = current.collectionId,
            libraryId = current.collectionLibraryId,
            collectionName = current.collectionName,
            onItemSelected = { item -> push(LibraryNav.ItemDetail(item.id, item.sourceId.ifEmpty { null })) },
            onNavigateBack = ::pop,
        )
        is LibraryNav.PlaylistDetail -> PlaylistDetailHost(
            destination = current,
            onBack = ::pop,
            onItemSelected = { item -> push(LibraryNav.ItemDetail(item.id, item.sourceId.ifEmpty { null })) },
            // "Play" carries the playlist context into the player, which is what makes
            // AudiobookPlayerViewModel's end-of-book auto-advance reachable on iOS at all.
            onPlayItem = { item -> push(playlistPlayerNav(item, current)) },
        )
    }
}

/**
 * Loads a [LibraryItem] by [itemId]/[sourceId] and opens the elided Annotations View.
 *
 * The annotations tab only exposes `(sourceId, itemId)`, not a full [LibraryItem]. This loader
 * bridges that gap: it suspends on [LibraryRepository.observeItem] until the item arrives, then
 * passes it straight to [EpubReaderScreen] with [ReaderSource.Highlights]. A spinner is shown
 * while the item is in flight (typically one DB read, <10 ms).
 */
@Composable
internal fun ElidedReaderLoader(
    itemId: String,
    sourceId: String?,
    onBack: () -> Unit,
) {
    val libraryObserver = koinInject<LibraryObserver>()
    var item by remember { mutableStateOf<LibraryItem?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(itemId, sourceId) {
        item =
            if (sourceId != null) {
                libraryObserver.getItem(sourceId, itemId)
            } else {
                libraryObserver.getItem(itemId)
            }
        loaded = true
    }
    val loadedItem = item
    when {
        loadedItem != null -> EpubReaderScreen(item = loadedItem, onBack = onBack, source = ReaderSource.Highlights)
        loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Text("Book not found")
        }
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
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
