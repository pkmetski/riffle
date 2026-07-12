package com.riffle.app.navigation

import androidx.activity.compose.BackHandler
import com.riffle.core.domain.SourceType
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.riffle.app.feature.downloads.DownloadsScreen
import com.riffle.app.feature.library.CollectionDetailScreen
import com.riffle.app.feature.library.FacetType
import com.riffle.app.feature.library.FilteredBooksScreen
import com.riffle.app.feature.audiobook.AudiobookPlayerScreen
import com.riffle.app.feature.library.LibraryItemDetailScreen
import com.riffle.app.feature.library.AnnotationSearchResultsScreen
import com.riffle.app.feature.library.AudiobookBookmarkSearchResult
import com.riffle.app.feature.library.LibraryItemsScreen
import com.riffle.app.feature.library.LibraryItemsViewModel
import com.riffle.app.feature.library.LibrarySectionScreen
import com.riffle.app.feature.library.LibrarySectionType
import com.riffle.app.feature.library.SeriesDetailScreen
import com.riffle.app.feature.navigation.HomeScreen
import com.riffle.app.feature.navigation.NavigationDrawerViewModel
import com.riffle.app.feature.navigation.RiffleNavigationDrawer
import com.riffle.app.feature.reader.EpubReaderScreen
import com.riffle.app.feature.reader.PdfReaderScreen
import com.riffle.app.feature.reader.cbz.CbzReaderScreen
import com.riffle.app.feature.server.AddSourceScreen
import com.riffle.app.feature.server.SelectLibrariesScreen
import com.riffle.app.feature.server.SourceSetupViewModel
import com.riffle.app.feature.settings.SettingsScreen
import com.riffle.app.feature.settings.annotationsync.AnnotationSyncMaintenanceScreen
import com.riffle.app.feature.settings.annotationsync.AnnotationsSyncSettingsScreen
import com.riffle.app.feature.settings.debug.DebugLogScreen
import com.riffle.app.feature.settings.readaloud.ReadaloudMatchesScreen
import com.riffle.app.feature.settings.readaloud.ReadaloudSettingsScreen
import com.riffle.app.playback.NowPlaying
import com.riffle.app.ui.isTabletLayout
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.net.URLEncoder

private const val HOME = "home"
private const val SOURCE_SETUP_GRAPH = "source_setup"
private const val ADD_SOURCE_TYPE_PICKER = "add_source_type_picker"
private const val ADD_LOCAL_FILES = "add_local_files"
private const val ADD_CHITANKA = "add_chitanka"
private const val CHITANKA_BROWSE = "chitanka_browse/{libraryId}/{libraryName}"
private const val ADD_GUTENBERG = "add_gutenberg"
private const val GUTENBERG_BROWSE = "gutenberg_browse/{libraryId}/{libraryName}"
private const val ADD_SOURCE = "add_source"
private const val ADD_SOURCE_ROUTE = "add_source?type={type}&editId={editId}"

/**
 * Where the "Add source" picker routes each [SourceType] to — delegated to the descriptor
 * (ADR 0044). Adding a new source needs no edit here.
 */
private fun addSourceRouteFor(type: com.riffle.core.domain.SourceType): String =
    com.riffle.core.domain.WebSourceDescriptors.forTypeOrError(type).addRoute
private const val SELECT_LIBRARIES = "select_libraries"
private const val SETTINGS = "settings"
private const val READALOUD_SETTINGS = "settings/readaloud"
private const val ANNOTATIONS_SYNC_SETTINGS = "settings/annotation_sync"
private const val ANNOTATION_SYNC_MAINTENANCE = "settings/annotation_sync/maintenance"
private const val DEBUG_LOGS = "settings/debug_logs"
private const val READALOUD_MATCHES = "readaloud_matches/{sourceId}?pairBookId={pairBookId}"
private const val DOWNLOADS = "downloads"
private const val LIBRARY_ITEMS = "library_items/{libraryId}/{libraryName}"
private const val LIBRARY_SECTION = "library_section/{libraryId}/{libraryName}/{sectionType}"
private const val SERIES_DETAIL = "series_detail/{libraryId}/{seriesId}/{seriesName}"
private const val COLLECTION_DETAIL = "collection_detail/{libraryId}/{collectionId}/{collectionName}"
private const val FILTERED_BOOKS = "filtered_books/{libraryId}/{facetType}/{facetValue}"
private const val LIBRARY_ITEM_DETAIL = "library_item_detail/{itemId}"
private const val EPUB_READER =
    "epub_reader/{itemId}?startReadaloudAtSec={startReadaloudAtSec}&openAtCfi={openAtCfi}&startTocHref={startTocHref}&source={source}&sourceId={sourceId}"
private const val PDF_READER = "pdf_reader/{itemId}"
private const val CBZ_READER = "cbz_reader/{itemId}"
private const val ANNOTATION_SEARCH = "annotation_search/{libraryId}?query={query}"
private const val AUDIOBOOK_PLAYER = "audiobook_player/{itemId}?startAtSec={startAtSec}"

/**
 * URL-encodes each path segment in a series-detail route. seriesId is encoded because chitanka
 * series ids contain slashes (`serie/foo` per ADR 0042) and would otherwise splay across the
 * fixed [SERIES_DETAIL] template's `{seriesId}` slot, producing the "destination cannot be
 * found in the navigation graph" crash. Nav Compose auto-decodes path arguments so the receiver
 * (SeriesDetailViewModel) sees the original id.
 */
internal fun seriesDetailRoute(libraryId: String, seriesId: String, seriesName: String): String =
    "series_detail/$libraryId/${URLEncoder.encode(seriesId, "UTF-8")}/${URLEncoder.encode(seriesName, "UTF-8")}"

/** Same reasoning as [seriesDetailRoute] but for collection ids. */
internal fun collectionDetailRoute(libraryId: String, collectionId: String, collectionName: String): String =
    "collection_detail/$libraryId/${URLEncoder.encode(collectionId, "UTF-8")}/${URLEncoder.encode(collectionName, "UTF-8")}"

/**
 * Dispatches to the correct library entry point for [sourceType]:
 *   - Room-mirrored catalogues (ABS, LocalFiles) → `library_items/…`
 *   - Unbounded catalogues → the source's dedicated browse screen. Each unbounded Source owns
 *     its own remote-browse route (Chitanka, Gutenberg, …) because their pagination, chip
 *     strip, and item-cards diverge enough that a single generic screen would leak per-Source
 *     branches everywhere.
 *
 * Adding a new unbounded Source means: (1) flip [SourceType.isUnboundedCatalog], (2) add a
 * branch to this `when`, (3) register the composable at the NavHost. A null [sourceType]
 * (activeServer hasn't resolved yet on cold start) falls back to `library_items`; the drawer
 * will correct on the next selection.
 */
internal fun libraryEntryRoute(sourceType: SourceType?, libraryId: String, libraryName: String): String {
    val encoded = URLEncoder.encode(libraryName, "UTF-8")
    val prefix = sourceType
        ?.let { com.riffle.core.domain.WebSourceDescriptors.forType(it) }
        ?.browseRoutePrefix
    return if (prefix != null) "$prefix/$libraryId/$encoded" else "library_items/$libraryId/$encoded"
}

@Composable
fun MainScreen(
    windowSizeClass: WindowSizeClass,
    viewModel: NavigationDrawerViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // ADR 0019: the Tablet Layout activates only when the window is large in BOTH dimensions —
    // Expanded width (≥ 840dp) and non-Compact height. A large phone in landscape crosses the
    // Expanded width breakpoint but stays Compact in height, so it renders the phone UI (modal
    // drawer, single-column detail). Re-evaluated at composition time, so rotation / unfold /
    // resize switch automatically.
    val isTablet = windowSizeClass.isTabletLayout()

    val activeServer by viewModel.activeServer.collectAsState()
    val allServers by viewModel.allServers.collectAsState()
    val visibleLibraries by viewModel.visibleLibraries.collectAsState()
    val serverVersions by viewModel.serverVersions.collectAsState()
    val showDownloadsLink by viewModel.showDownloadsLink.collectAsState()

    val currentBackStack by navController.currentBackStackEntryAsState()
    val activeLibraryId = currentBackStack
        ?.takeIf { it.destination.route?.startsWith("library_items/") == true }
        ?.arguments?.getString("libraryId")
    val currentRoute = currentBackStack?.destination?.route
    val usePermanentDrawer = isTablet
    // Reader screens are immersive — collapse the permanent side panel so the book/PDF
    // fills the width, matching the modal drawer's gesture suppression on phones.
    val hidePermanentDrawerPanel = isReaderRoute(currentRoute)

    // Material3's ModalNavigationDrawer doesn't install its own BackHandler — so when the
    // drawer is open we add one here. Registered above the NavHost so screen-level handlers
    // (e.g. LibraryItemsScreen) don't override it.
    BackHandler(enabled = !usePermanentDrawer && drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    // A media-notification tap jumps to whatever is playing. launchSingleTop makes this a no-op when
    // that screen is already current (the common case, since audio only plays while its screen is) —
    // so playback is never restarted; otherwise it opens the right player.
    LaunchedEffect(Unit) {
        viewModel.openNowPlayingRequests.collect {
            val target = viewModel.currentNowPlaying() ?: return@collect
            val encoded = URLEncoder.encode(target.itemId, "UTF-8")
            val route = when (target) {
                is NowPlaying.Audiobook -> "audiobook_player/$encoded"
                is NowPlaying.Readaloud -> "epub_reader/$encoded"
            }
            navController.navigate(route) { launchSingleTop = true }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.redirectToLibrary.collect { library ->
            navController.navigateAsRoot(libraryEntryRoute(activeServer?.type, library.id, library.name))
            viewModel.setActiveLibrary(library.id)
        }
    }

    RiffleNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !isReaderRoute(currentRoute),
        usePermanentDrawer = usePermanentDrawer,
        hidePermanentDrawerPanel = hidePermanentDrawerPanel,
        activeServer = activeServer,
        allServers = allServers,
        visibleLibraries = visibleLibraries,
        activeLibraryId = activeLibraryId,
        serverVersions = serverVersions,
        showDownloadsLink = showDownloadsLink,
        onServerSelected = { server ->
            viewModel.setActiveServer(server.id)
            scope.launch { drawerState.close() }
            navController.navigateAsRoot(HOME)
        },
        onLibrarySelected = { library ->
            viewModel.setActiveLibrary(library.id)
            scope.launch { drawerState.close() }
            navController.navigateAsRoot(libraryEntryRoute(activeServer?.type, library.id, library.name))
        },
        onDownloadsSelected = {
            scope.launch { drawerState.close() }
            navController.navigate(DOWNLOADS)
        },
        onSettingsSelected = {
            scope.launch { drawerState.close() }
            navController.navigate(SETTINGS)
        },
    ) {
        NavHost(navController = navController, startDestination = HOME) {
            composable(HOME) {
                HomeScreen(
                    onNavigateToAddSource = {
                        navController.navigateAsRoot(ADD_SOURCE_TYPE_PICKER)
                    },
                    onNavigateToLibrary = { libraryId, libraryName ->
                        viewModel.setActiveLibrary(libraryId)
                        navController.navigateAsRoot(libraryEntryRoute(activeServer?.type, libraryId, libraryName))
                    },
                )
            }
            // The Source Type picker lives at NavHost level (not inside SOURCE_SETUP_GRAPH) so
            // that entering the setup graph directly for Storyteller/WebDAV/edit paths does NOT
            // implicitly push the picker as the graph's start destination onto the back stack.
            // With this shape the back stack for those flows stays [caller, ADD_SOURCE], and
            // `previousBackStackEntry.route == SETTINGS` remains the right predicate for
            // "should top-app-bar back pop to Settings?".
            composable(ADD_SOURCE_TYPE_PICKER) {
                val cameFromSettings = navController.previousBackStackEntry
                    ?.destination?.route == SETTINGS
                val pickerViewModel: com.riffle.app.feature.server.SourceTypePickerViewModel =
                    androidx.hilt.navigation.compose.hiltViewModel()
                val installedTypes by pickerViewModel.installedTypes.collectAsState()
                com.riffle.app.feature.server.SourceTypePickerScreen(
                    windowSizeClass = windowSizeClass,
                    onNavigateBack = {
                        if (cameFromSettings) navController.popBackStack()
                        else navController.navigateAsRoot(HOME)
                    },
                    onPick = { type ->
                        val route = addSourceRouteFor(type)
                        navController.navigate(route) {
                            // Drop the picker so back from the form returns to the caller.
                            popUpTo(ADD_SOURCE_TYPE_PICKER) { inclusive = true }
                        }
                    },
                    installedTypes = installedTypes,
                )
            }
            composable(
                route = CHITANKA_BROWSE,
                arguments = listOf(
                    navArgument("libraryId") { type = NavType.StringType },
                    navArgument("libraryName") { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val libraryName = backStackEntry.arguments?.getString("libraryName")
                    ?.let { URLDecoder.decode(it, "UTF-8") } ?: ""
                com.riffle.app.feature.source.chitanka.ChitankaBrowseScreen(
                    libraryName = libraryName,
                    windowSizeClass = windowSizeClass,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpenDetail = { itemId ->
                        val encodedId = URLEncoder.encode(itemId, "UTF-8")
                        navController.navigate("library_item_detail/$encodedId")
                    },
                    onAnnotatedBookClick = { sourceId, itemId ->
                        navController.navigate(annotationsBookClickRoute(sourceId, itemId))
                    },
                )
            }
            composable(ADD_CHITANKA) {
                val cameFromSettings = navController.previousBackStackEntry
                    ?.destination?.route == SETTINGS
                com.riffle.app.feature.source.chitanka.AddChitankaScreen(
                    windowSizeClass = windowSizeClass,
                    onDone = {
                        if (cameFromSettings) navController.popBackStack()
                        else navController.navigateAsRoot(HOME)
                    },
                    onNavigateBack = {
                        if (cameFromSettings) navController.popBackStack()
                        else navController.navigateAsRoot(HOME)
                    },
                )
            }
            composable(
                route = GUTENBERG_BROWSE,
                arguments = listOf(
                    navArgument("libraryId") { type = NavType.StringType },
                    navArgument("libraryName") { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val libraryName = backStackEntry.arguments?.getString("libraryName")
                    ?.let { URLDecoder.decode(it, "UTF-8") } ?: ""
                com.riffle.app.feature.source.gutenberg.GutenbergBrowseScreen(
                    libraryName = libraryName,
                    windowSizeClass = windowSizeClass,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpenDetail = { itemId ->
                        val encodedId = URLEncoder.encode(itemId, "UTF-8")
                        navController.navigate("library_item_detail/$encodedId")
                    },
                    onAnnotatedBookClick = { sourceId, itemId ->
                        navController.navigate(annotationsBookClickRoute(sourceId, itemId))
                    },
                )
            }
            composable(ADD_GUTENBERG) {
                val cameFromSettings = navController.previousBackStackEntry
                    ?.destination?.route == SETTINGS
                com.riffle.app.feature.source.gutenberg.AddGutenbergScreen(
                    windowSizeClass = windowSizeClass,
                    onDone = {
                        if (cameFromSettings) navController.popBackStack()
                        else navController.navigateAsRoot(HOME)
                    },
                    onNavigateBack = {
                        if (cameFromSettings) navController.popBackStack()
                        else navController.navigateAsRoot(HOME)
                    },
                )
            }
            composable(ADD_LOCAL_FILES) {
                val cameFromSettings = navController.previousBackStackEntry
                    ?.destination?.route == SETTINGS
                com.riffle.app.feature.source.localfiles.AddLocalFilesScreen(
                    windowSizeClass = windowSizeClass,
                    onDone = {
                        if (cameFromSettings) navController.popBackStack()
                        else navController.navigateAsRoot(HOME)
                    },
                    onNavigateBack = {
                        if (cameFromSettings) navController.popBackStack()
                        else navController.navigateAsRoot(HOME)
                    },
                )
            }
            navigation(startDestination = ADD_SOURCE_ROUTE, route = SOURCE_SETUP_GRAPH) {
                composable(
                    route = ADD_SOURCE_ROUTE,
                    arguments = listOf(
                        navArgument("type") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                        navArgument("editId") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                    ),
                ) { backStackEntry ->
                    val parentEntry = remember(backStackEntry) {
                        navController.getBackStackEntry(SOURCE_SETUP_GRAPH)
                    }
                    val setupVm: SourceSetupViewModel = hiltViewModel(parentEntry)
                    // Add-Source can be reached from the main Settings screen or from either of
                    // the settings drill-ins (Readaloud → Configure Storyteller; Annotations
                    // Sync → Configure WebDAV). All three should pop back to the caller when
                    // done; only cold entry (e.g. deep link) falls through to Home.
                    val cameFromSettings = navController.previousBackStackEntry
                        ?.destination?.route in setOf(SETTINGS, READALOUD_SETTINGS, ANNOTATIONS_SYNC_SETTINGS)
                    AddSourceScreen(
                        windowSizeClass = windowSizeClass,
                        onNavigateBack = {
                            if (cameFromSettings) navController.popBackStack()
                            else navController.navigateAsRoot(HOME)
                        },
                        onAuthenticated = { pending ->
                            setupVm.pendingServer = pending
                            navController.navigate(SELECT_LIBRARIES)
                        },
                        onAutoCompleted = {
                            if (cameFromSettings) navController.popBackStack()
                            else navController.navigateAsRoot(HOME)
                        },
                    )
                }
                composable(SELECT_LIBRARIES) { backStackEntry ->
                    val parentEntry = remember(backStackEntry) {
                        navController.getBackStackEntry(SOURCE_SETUP_GRAPH)
                    }
                    val setupVm: SourceSetupViewModel = hiltViewModel(parentEntry)
                    val pending = setupVm.pendingServer
                    if (pending == null) {
                        LaunchedEffect(Unit) { navController.popBackStack() }
                    } else {
                        SelectLibrariesScreen(
                            pending = pending,
                            windowSizeClass = windowSizeClass,
                            onNavigateBack = { navController.popBackStack() },
                            onContinueComplete = {
                                navController.navigateAsRoot(HOME)
                            },
                        )
                    }
                }
            }
            composable(SETTINGS) {
                SettingsScreen(
                    windowSizeClass = windowSizeClass,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToAddSource = { backend, editId ->
                        // The Source Type picker only fronts the "add a fresh ABS Source" flow.
                        // Storyteller/WebDAV are Services (not Sources) and deep-link straight
                        // to the form; editing an existing ABS Source also skips the picker
                        // (Source Type is already known).
                        val isNewAbs = backend == com.riffle.app.feature.server.AddSourceBackend.AUDIOBOOKSHELF && editId.isNullOrEmpty()
                        if (isNewAbs) {
                            navController.navigate(ADD_SOURCE_TYPE_PICKER)
                        } else {
                            val params = buildList {
                                add("type=${backend.name.lowercase()}")
                                if (!editId.isNullOrEmpty()) add("editId=${URLEncoder.encode(editId, "UTF-8")}")
                            }.joinToString("&")
                            navController.navigate("$ADD_SOURCE?$params")
                        }
                    },
                    onNavigateToAddLocalFolder = { navController.navigate(ADD_LOCAL_FILES) },
                    onNavigateToReadaloudSettings = { navController.navigate(READALOUD_SETTINGS) },
                    onNavigateToAnnotationsSyncSettings = { navController.navigate(ANNOTATIONS_SYNC_SETTINGS) },
                    onNavigateToDebugLogs = { navController.navigate(DEBUG_LOGS) },
                )
            }
            composable(READALOUD_SETTINGS) { backStackEntry ->
                // Reuse the parent Settings entry's SettingsViewModel so the drill-in shares the
                // main screen's already-warm caches (versionsCache, StateFlow subscriptions) —
                // otherwise every navigation instantiates a second ~20-dep VM and re-runs the
                // per-source version probes.
                val settingsEntry = remember(backStackEntry) {
                    navController.getBackStackEntry(SETTINGS)
                }
                val settingsVm: com.riffle.app.feature.settings.SettingsViewModel =
                    hiltViewModel(settingsEntry)
                ReadaloudSettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToAddSource = { backend, editId ->
                        val params = buildList {
                            add("type=${backend.name.lowercase()}")
                            if (!editId.isNullOrEmpty()) add("editId=${URLEncoder.encode(editId, "UTF-8")}")
                        }.joinToString("&")
                        navController.navigate("$ADD_SOURCE?$params")
                    },
                    onNavigateToReadaloudMatches = { sourceId ->
                        val encoded = URLEncoder.encode(sourceId, "UTF-8")
                        navController.navigate("readaloud_matches/$encoded")
                    },
                    viewModel = settingsVm,
                )
            }
            composable(ANNOTATIONS_SYNC_SETTINGS) { backStackEntry ->
                val settingsEntry = remember(backStackEntry) {
                    navController.getBackStackEntry(SETTINGS)
                }
                val settingsVm: com.riffle.app.feature.settings.SettingsViewModel =
                    hiltViewModel(settingsEntry)
                AnnotationsSyncSettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToAddSource = { backend, editId ->
                        val params = buildList {
                            add("type=${backend.name.lowercase()}")
                            if (!editId.isNullOrEmpty()) add("editId=${URLEncoder.encode(editId, "UTF-8")}")
                        }.joinToString("&")
                        navController.navigate("$ADD_SOURCE?$params")
                    },
                    onNavigateToMaintenance = { navController.navigate(ANNOTATION_SYNC_MAINTENANCE) },
                    viewModel = settingsVm,
                )
            }
            composable(ANNOTATION_SYNC_MAINTENANCE) {
                AnnotationSyncMaintenanceScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(DEBUG_LOGS) {
                DebugLogScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(
                route = READALOUD_MATCHES,
                arguments = listOf(
                    navArgument("sourceId") { type = NavType.StringType },
                    navArgument("pairBookId") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) {
                ReadaloudMatchesScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(DOWNLOADS) {
                DownloadsScreen(
                    windowSizeClass = windowSizeClass,
                    onNavigateBack = { navController.popBackStack() },
                    onItemSelected = { item ->
                        val encodedId = URLEncoder.encode(item.id, "UTF-8")
                        navController.navigate("library_item_detail/$encodedId")
                    },
                )
            }
            composable(
                route = LIBRARY_ITEMS,
                arguments = listOf(
                    navArgument("libraryId") { type = NavType.StringType },
                    navArgument("libraryName") { type = NavType.StringType },
                )
            ) { backStackEntry ->
                val libraryId = backStackEntry.arguments?.getString("libraryId") ?: ""
                val libraryName = URLDecoder.decode(
                    backStackEntry.arguments?.getString("libraryName") ?: "",
                    "UTF-8"
                )
                // Force the drawer fully closed when the library destination first composes.
                // Without this its closing animation can race a Back press and the drawer's
                // own BackHandler captures it instead of exiting the app (issue #60).
                LaunchedEffect(Unit) { drawerState.close() }
                LibraryItemsScreen(
                    libraryName = libraryName,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    backEnabled = !drawerState.isOpen,
                    onSeriesSelected = { series ->
                        navController.navigate(seriesDetailRoute(libraryId, series.id, series.name))
                    },
                    onCollectionSelected = { collection ->
                        navController.navigate(collectionDetailRoute(libraryId, collection.id, collection.name))
                    },
                    onItemSelected = { item ->
                        val encodedId = URLEncoder.encode(item.id, "UTF-8")
                        navController.navigate("library_item_detail/$encodedId")
                    },
                    onAnnotationSelected = { result ->
                        val encodedId = URLEncoder.encode(result.annotation.itemId, "UTF-8")
                        val encodedCfi = URLEncoder.encode(result.annotation.cfi, "UTF-8")
                        navController.navigate("epub_reader/$encodedId?openAtCfi=$encodedCfi")
                    },
                    onAudiobookBookmarkSelected = { result ->
                        val encodedId = URLEncoder.encode(result.bookmark.itemId, "UTF-8")
                        navController.navigate("audiobook_player/$encodedId?startAtSec=${result.bookmark.positionSec}")
                    },
                    onShowAllAnnotations = { query ->
                        val encodedQuery = URLEncoder.encode(query, "UTF-8")
                        navController.navigate("annotation_search/$libraryId?query=$encodedQuery")
                    },
                    onSectionSeeMore = { sectionType ->
                        val encodedName = URLEncoder.encode(libraryName, "UTF-8")
                        navController.navigate("library_section/$libraryId/$encodedName/${sectionType.name}")
                    },
                    onAnnotatedBookClick = { sourceId, itemId ->
                        navController.navigate(annotationsBookClickRoute(sourceId, itemId))
                    },
                )
            }
            composable(
                route = LIBRARY_SECTION,
                arguments = listOf(
                    navArgument("libraryId") { type = NavType.StringType },
                    navArgument("libraryName") { type = NavType.StringType },
                    navArgument("sectionType") { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val sectionType = LibrarySectionType.valueOf(
                    backStackEntry.arguments?.getString("sectionType") ?: LibrarySectionType.IN_PROGRESS.name
                )
                // Reuse the library-items ViewModel from the parent back stack entry instead of
                // creating a new one. A fresh LibraryItemsViewModel triggers a full library
                // refresh concurrently with the main screen's refresh, which can cause data races
                // in shared singletons (e.g. StorytellerReadaloudSyncer).
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry(LIBRARY_ITEMS)
                }
                LibrarySectionScreen(
                    sectionType = sectionType,
                    viewModel = hiltViewModel<LibraryItemsViewModel>(parentEntry),
                    onItemSelected = { item ->
                        val encodedId = URLEncoder.encode(item.id, "UTF-8")
                        navController.navigate("library_item_detail/$encodedId")
                    },
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(
                route = SERIES_DETAIL,
                arguments = listOf(
                    navArgument("libraryId") { type = NavType.StringType },
                    navArgument("seriesId") { type = NavType.StringType },
                    navArgument("seriesName") { type = NavType.StringType },
                )
            ) { backStackEntry ->
                val seriesName = URLDecoder.decode(
                    backStackEntry.arguments?.getString("seriesName") ?: "",
                    "UTF-8"
                )
                SeriesDetailScreen(
                    seriesName = seriesName,
                    onItemSelected = { item ->
                        val encodedId = URLEncoder.encode(item.id, "UTF-8")
                        navController.navigate("library_item_detail/$encodedId")
                    },
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(
                route = COLLECTION_DETAIL,
                arguments = listOf(
                    navArgument("libraryId") { type = NavType.StringType },
                    navArgument("collectionId") { type = NavType.StringType },
                    navArgument("collectionName") { type = NavType.StringType },
                )
            ) { backStackEntry ->
                val collectionName = URLDecoder.decode(
                    backStackEntry.arguments?.getString("collectionName") ?: "",
                    "UTF-8"
                )
                CollectionDetailScreen(
                    collectionName = collectionName,
                    onItemSelected = { item ->
                        val encodedId = URLEncoder.encode(item.id, "UTF-8")
                        navController.navigate("library_item_detail/$encodedId")
                    },
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(
                route = LIBRARY_ITEM_DETAIL,
                arguments = listOf(
                    navArgument("itemId") { type = NavType.StringType },
                )
            ) {
                LibraryItemDetailScreen(
                    windowSizeClass = windowSizeClass,
                    onNavigateBack = { navController.popBackStack() },
                    onReadItem = { item ->
                        readerRouteFor(item)?.let { navController.navigate(it) }
                    },
                    onListenItem = { item ->
                        val encodedId = URLEncoder.encode(item.id, "UTF-8")
                        navController.navigate("audiobook_player/$encodedId")
                    },
                    onReadItemAtHref = { item, href ->
                        val encodedId = URLEncoder.encode(item.id, "UTF-8")
                        val encodedHref = URLEncoder.encode(href, "UTF-8")
                        navController.navigate("epub_reader/$encodedId?startTocHref=$encodedHref")
                    },
                    onListenItemAtSec = { item, startSec ->
                        val encodedId = URLEncoder.encode(item.id, "UTF-8")
                        navController.navigate("audiobook_player/$encodedId?startAtSec=$startSec")
                    },
                    onNavigateToFacet = { libraryId, facet, value ->
                        val encoded = URLEncoder.encode(value, "UTF-8")
                        navController.navigate("filtered_books/$libraryId/${facet.name}/$encoded")
                    },
                    onNavigateToSeries = { libraryId, seriesId, seriesName ->
                        navController.navigate(seriesDetailRoute(libraryId, seriesId, seriesName))
                    },
                )
            }
            composable(
                route = FILTERED_BOOKS,
                arguments = listOf(
                    navArgument("libraryId") { type = NavType.StringType },
                    navArgument("facetType") { type = NavType.StringType },
                    navArgument("facetValue") { type = NavType.StringType },
                ),
            ) {
                FilteredBooksScreen(
                    onItemSelected = { item ->
                        val encodedId = URLEncoder.encode(item.id, "UTF-8")
                        navController.navigate("library_item_detail/$encodedId")
                    },
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(
                route = EPUB_READER,
                arguments = listOf(
                    navArgument("itemId") { type = NavType.StringType },
                    navArgument("startReadaloudAtSec") {
                        type = NavType.FloatType
                        defaultValue = -1f // -1 = opened normally, not an audiobook→readaloud handoff
                    },
                    navArgument("openAtCfi") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("startTocHref") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("source") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("sourceId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                )
            ) {
                val viewModel: com.riffle.app.feature.reader.EpubReaderViewModel = hiltViewModel()
                // Highlights mode's "Open in book" (Task 9, ADR 0041): leaves the elided reader and
                // opens the full-book reader at the tapped highlight's CFI. Handled at the nav-host
                // level (not inside EpubReaderScreen) since it pops this route off the back stack.
                LaunchedEffect(viewModel) {
                    viewModel.readerNavEvents.collect { event ->
                        when (event) {
                            is com.riffle.app.feature.reader.ReaderNavEvent.OpenInSourceBook -> {
                                val encodedId = URLEncoder.encode(event.itemId, "UTF-8")
                                val encodedCfi = URLEncoder.encode(event.cfi, "UTF-8")
                                navController.popBackStack()
                                navController.navigate("epub_reader/$encodedId?openAtCfi=$encodedCfi")
                            }
                            com.riffle.app.feature.reader.ReaderNavEvent.CloseEmptyHighlights -> {
                                navController.popBackStack()
                            }
                        }
                    }
                }
                EpubReaderScreen(
                    windowSizeClass = windowSizeClass,
                    onNavigateBack = { navController.popBackStack() },
                    viewModel = viewModel,
                )
            }
            composable(
                route = ANNOTATION_SEARCH,
                arguments = listOf(
                    navArgument("libraryId") { type = NavType.StringType },
                    navArgument("query") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                AnnotationSearchResultsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onAnnotationSelected = { result ->
                        val encodedId = URLEncoder.encode(result.annotation.itemId, "UTF-8")
                        val encodedCfi = URLEncoder.encode(result.annotation.cfi, "UTF-8")
                        navController.navigate("epub_reader/$encodedId?openAtCfi=$encodedCfi")
                    },
                    onAudiobookBookmarkSelected = { result ->
                        val encodedId = URLEncoder.encode(result.bookmark.itemId, "UTF-8")
                        navController.navigate("audiobook_player/$encodedId?startAtSec=${result.bookmark.positionSec}")
                    },
                )
            }
            composable(
                route = PDF_READER,
                arguments = listOf(
                    navArgument("itemId") { type = NavType.StringType },
                )
            ) {
                PdfReaderScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(
                route = CBZ_READER,
                arguments = listOf(
                    navArgument("itemId") { type = NavType.StringType },
                )
            ) {
                CbzReaderScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(
                route = AUDIOBOOK_PLAYER,
                arguments = listOf(
                    navArgument("itemId") { type = NavType.StringType },
                    navArgument("startAtSec") {
                        type = NavType.FloatType
                        defaultValue = -1f // -1 = opened normally; >=0 = readaloud→audiobook handoff
                    },
                )
            ) {
                AudiobookPlayerScreen(
                    windowSizeClass = windowSizeClass,
                    onNavigateBack = { navController.popBackStack() },
                    // Swipe down → switch to the readaloud reader for the linked ebook, continuing from
                    // the audiobook position. Pop the player off the stack so leaving readaloud doesn't
                    // land back on a dead player, and its onCleared stops audio + flushes progress.
                    onSwitchToReadaloud = { ebookItemId, atSec ->
                        // Audiobook opened from the library (not from the reader overlay). Navigate
                        // to the reader, replacing the player so Back doesn't return to a dead session.
                        val encoded = URLEncoder.encode(ebookItemId, "UTF-8")
                        navController.navigate("epub_reader/$encoded?startReadaloudAtSec=$atSec") {
                            popUpTo(AUDIOBOOK_PLAYER) { inclusive = true }
                        }
                    },
                )
            }
        }
    }
}

// Switches the active surface, keeping HOME as the permanent base of the back stack so [route]
// sits directly on top of it. Used by every "switch the active surface" navigation — launch
// router, server/library switch, redirect, server-setup completion.
//
// popUpTo(HOME) with inclusive = FALSE is deliberate and load-bearing:
//   * It clears everything ABOVE home but never removes home, so the stack is always [home, route]
//     — never a single sole entry. Back from the root therefore pops to home (which re-routes to a
//     library) instead of emptying the NavHost. An empty NavHost renders a blank white screen, and
//     that is exactly what happened when this used popUpTo(graph.id) { inclusive = true }: a Back
//     reaching the NavHost's own callback (e.g. while the drawer is animating open, when the
//     screen-level handlers don't own it) popped the lone root to nothing.
//   * Because home is never removed, popUpTo(HOME) always matches, so each switch REPLACES the
//     previous surface instead of stacking a duplicate root — the earlier popUpTo(HOME) {
//     inclusive = true } bug (which DID remove home, making later popUpTo(HOME) a no-op that piled
//     up duplicate roots) cannot recur here.
internal fun NavController.navigateAsRoot(route: String) {
    navigate(route) {
        popUpTo(HOME) { inclusive = false }
        launchSingleTop = true
    }
}

internal fun isReaderRoute(route: String?): Boolean =
    route?.startsWith(EPUB_READER.substringBefore("{")) == true ||
        route?.startsWith(PDF_READER.substringBefore("{")) == true ||
        route?.startsWith(CBZ_READER.substringBefore("{")) == true
