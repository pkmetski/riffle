package com.riffle.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
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
import com.riffle.app.feature.library.LibraryItemsScreen
import com.riffle.app.feature.library.LibrarySectionScreen
import com.riffle.app.feature.library.LibrarySectionType
import com.riffle.app.feature.library.SeriesDetailScreen
import com.riffle.app.feature.navigation.HomeScreen
import com.riffle.app.feature.navigation.NavigationDrawerViewModel
import com.riffle.app.feature.navigation.RiffleNavigationDrawer
import com.riffle.app.feature.reader.EpubReaderScreen
import com.riffle.app.feature.reader.PdfReaderScreen
import com.riffle.app.feature.server.AddServerScreen
import com.riffle.app.feature.server.SelectLibrariesScreen
import com.riffle.app.feature.server.ServerSetupViewModel
import com.riffle.app.feature.settings.SettingsScreen
import com.riffle.app.feature.settings.readaloud.ReadaloudMatchesScreen
import com.riffle.app.playback.NowPlaying
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.net.URLEncoder

private const val HOME = "home"
private const val SERVER_SETUP_GRAPH = "server_setup"
private const val ADD_SERVER = "add_server"
private const val SELECT_LIBRARIES = "select_libraries"
private const val SETTINGS = "settings"
private const val READALOUD_MATCHES = "readaloud_matches/{serverId}?pairBookId={pairBookId}"
private const val DOWNLOADS = "downloads"
private const val LIBRARY_ITEMS = "library_items/{libraryId}/{libraryName}"
private const val LIBRARY_SECTION = "library_section/{libraryId}/{libraryName}/{sectionType}"
private const val SERIES_DETAIL = "series_detail/{libraryId}/{seriesId}/{seriesName}"
private const val COLLECTION_DETAIL = "collection_detail/{libraryId}/{collectionId}/{collectionName}"
private const val FILTERED_BOOKS = "filtered_books/{libraryId}/{facetType}/{facetValue}"
private const val LIBRARY_ITEM_DETAIL = "library_item_detail/{itemId}"
private const val EPUB_READER = "epub_reader/{itemId}?startReadaloudAtSec={startReadaloudAtSec}"
private const val PDF_READER = "pdf_reader/{itemId}"
private const val AUDIOBOOK_PLAYER = "audiobook_player/{itemId}?startAtSec={startAtSec}"

@Composable
fun MainScreen(
    windowSizeClass: WindowSizeClass,
    viewModel: NavigationDrawerViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // ADR 0019: Tablet Layout activates only on Expanded (≥ 840dp). Compact and Medium
    // both render the phone UI. The threshold is evaluated at composition time so
    // configuration changes (rotation, foldable unfold, ChromeOS resize, split-screen)
    // re-evaluate automatically.
    val isExpanded = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded
    // A large phone in landscape crosses the Expanded width breakpoint (so it gets the tablet
    // layouts) but stays Compact in height (< 480dp) — a real tablet is taller in both
    // orientations. This pair distinguishes "phone landscape" from "tablet" so the detail and
    // player screens can reclaim the drawer's width without touching the tablet view.
    val isPhoneLandscape = isExpanded &&
        windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact

    val activeServer by viewModel.activeServer.collectAsState()
    val allServers by viewModel.allServers.collectAsState()
    val visibleLibraries by viewModel.visibleLibraries.collectAsState()
    val serverVersions by viewModel.serverVersions.collectAsState()

    val currentBackStack by navController.currentBackStackEntryAsState()
    val activeLibraryId = currentBackStack
        ?.takeIf { it.destination.route?.startsWith("library_items/") == true }
        ?.arguments?.getString("libraryId")
    val currentRoute = currentBackStack?.destination?.route
    val usePermanentDrawer = isExpanded
    // Reader screens are immersive — collapse the permanent side panel so the book/PDF
    // fills the width, matching the modal drawer's gesture suppression on phones. The item
    // detail and audiobook player additionally collapse it on a phone in landscape, where the
    // 280dp sheet would crowd the (short, wide) screen — but only there, so the tablet keeps it.
    val hidePermanentDrawerPanel = isReaderRoute(currentRoute) ||
        (isPhoneLandscape && isDetailOrPlayerRoute(currentRoute))

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
            val encoded = URLEncoder.encode(library.name, "UTF-8")
            navController.navigateAsRoot("library_items/${library.id}/$encoded")
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
        onServerSelected = { server ->
            viewModel.setActiveServer(server.id)
            scope.launch { drawerState.close() }
            navController.navigateAsRoot(HOME)
        },
        onLibrarySelected = { library ->
            viewModel.setActiveLibrary(library.id)
            scope.launch { drawerState.close() }
            val encoded = URLEncoder.encode(library.name, "UTF-8")
            navController.navigateAsRoot("library_items/${library.id}/$encoded")
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
                    onNavigateToAddServer = {
                        navController.navigateAsRoot(ADD_SERVER)
                    },
                    onNavigateToLibrary = { libraryId, libraryName ->
                        viewModel.setActiveLibrary(libraryId)
                        val encoded = URLEncoder.encode(libraryName, "UTF-8")
                        navController.navigateAsRoot("library_items/$libraryId/$encoded")
                    },
                )
            }
            navigation(startDestination = ADD_SERVER, route = SERVER_SETUP_GRAPH) {
                composable(ADD_SERVER) { backStackEntry ->
                    val parentEntry = remember(backStackEntry) {
                        navController.getBackStackEntry(SERVER_SETUP_GRAPH)
                    }
                    val setupVm: ServerSetupViewModel = hiltViewModel(parentEntry)
                    AddServerScreen(
                        windowSizeClass = windowSizeClass,
                        onNavigateBack = {
                            navController.navigateAsRoot(HOME)
                        },
                        onAuthenticated = { pending ->
                            setupVm.pendingServer = pending
                            navController.navigate(SELECT_LIBRARIES)
                        },
                        onAutoCompleted = {
                            navController.navigateAsRoot(HOME)
                        },
                    )
                }
                composable(SELECT_LIBRARIES) { backStackEntry ->
                    val parentEntry = remember(backStackEntry) {
                        navController.getBackStackEntry(SERVER_SETUP_GRAPH)
                    }
                    val setupVm: ServerSetupViewModel = hiltViewModel(parentEntry)
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
                    onNavigateToAddServer = { navController.navigate(ADD_SERVER) },
                    onNavigateToReadaloudMatches = { serverId ->
                        val encoded = URLEncoder.encode(serverId, "UTF-8")
                        navController.navigate("readaloud_matches/$encoded")
                    },
                )
            }
            composable(
                route = READALOUD_MATCHES,
                arguments = listOf(
                    navArgument("serverId") { type = NavType.StringType },
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
                        val encodedName = URLEncoder.encode(series.name, "UTF-8")
                        navController.navigate("series_detail/$libraryId/${series.id}/$encodedName")
                    },
                    onCollectionSelected = { collection ->
                        val encodedName = URLEncoder.encode(collection.name, "UTF-8")
                        navController.navigate("collection_detail/$libraryId/${collection.id}/$encodedName")
                    },
                    onItemSelected = { item ->
                        val encodedId = URLEncoder.encode(item.id, "UTF-8")
                        navController.navigate("library_item_detail/$encodedId")
                    },
                    onSectionSeeMore = { sectionType ->
                        val encodedName = URLEncoder.encode(libraryName, "UTF-8")
                        navController.navigate("library_section/$libraryId/$encodedName/${sectionType.name}")
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
                LibrarySectionScreen(
                    sectionType = sectionType,
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
                    onNavigateToFacet = { libraryId, facet, value ->
                        val encoded = URLEncoder.encode(value, "UTF-8")
                        navController.navigate("filtered_books/$libraryId/${facet.name}/$encoded")
                    },
                    onNavigateToSeries = { libraryId, seriesId, seriesName ->
                        val encodedName = URLEncoder.encode(seriesName, "UTF-8")
                        navController.navigate("series_detail/$libraryId/$seriesId/$encodedName")
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
                )
            ) {
                EpubReaderScreen(
                    onNavigateBack = { navController.popBackStack() },
                    // Swipe up → switch to the single large player (the audiobook), continuing from the
                    // listen position. Replace the reader (popUpTo inclusive) so the two surfaces swap
                    // rather than stack; the reader's onCleared releases the shared player without
                    // stopping it so the audiobook keeps playing.
                    onSwitchToAudiobook = { audiobookItemId, atSec ->
                        val encoded = URLEncoder.encode(audiobookItemId, "UTF-8")
                        navController.navigate("audiobook_player/$encoded?startAtSec=$atSec") {
                            popUpTo(EPUB_READER) { inclusive = true }
                        }
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
        route?.startsWith(PDF_READER.substringBefore("{")) == true

// The two full-screen surfaces that reclaim the permanent drawer's width on a phone in landscape.
internal fun isDetailOrPlayerRoute(route: String?): Boolean =
    route?.startsWith(LIBRARY_ITEM_DETAIL.substringBefore("{")) == true ||
        route?.startsWith(AUDIOBOOK_PLAYER.substringBefore("{")) == true
