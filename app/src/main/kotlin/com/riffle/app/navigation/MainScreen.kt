package com.riffle.app.navigation

import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.riffle.app.feature.downloads.DownloadsScreen
import com.riffle.app.feature.library.CollectionDetailScreen
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
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.net.URLEncoder

private const val HOME = "home"
private const val SERVER_SETUP_GRAPH = "server_setup"
private const val ADD_SERVER = "add_server"
private const val SELECT_LIBRARIES = "select_libraries"
private const val SETTINGS = "settings"
private const val DOWNLOADS = "downloads"
private const val LIBRARY_ITEMS = "library_items/{libraryId}/{libraryName}"
private const val LIBRARY_SECTION = "library_section/{libraryId}/{libraryName}/{sectionType}"
private const val SERIES_DETAIL = "series_detail/{libraryId}/{seriesId}/{seriesName}"
private const val COLLECTION_DETAIL = "collection_detail/{libraryId}/{collectionId}/{collectionName}"
private const val LIBRARY_ITEM_DETAIL = "library_item_detail/{itemId}"
private const val EPUB_READER = "epub_reader/{itemId}"
private const val PDF_READER = "pdf_reader/{itemId}"

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
    // fills the width, matching the modal drawer's gesture suppression on phones.
    val hidePermanentDrawerPanel = isReaderRoute(currentRoute)

    LaunchedEffect(Unit) {
        viewModel.redirectToLibrary.collect { library ->
            val encoded = URLEncoder.encode(library.name, "UTF-8")
            navController.navigate("library_items/${library.id}/$encoded") {
                popUpTo(HOME) { inclusive = true }
            }
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
            navController.navigate(HOME) { popUpTo(HOME) { inclusive = true } }
        },
        onLibrarySelected = { library ->
            viewModel.setActiveLibrary(library.id)
            scope.launch { drawerState.close() }
            val encoded = URLEncoder.encode(library.name, "UTF-8")
            navController.navigate("library_items/${library.id}/$encoded") {
                popUpTo(HOME) { inclusive = true }
            }
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
                        navController.navigate(ADD_SERVER) {
                            popUpTo(HOME) { inclusive = true }
                        }
                    },
                    onNavigateToLibrary = { libraryId, libraryName ->
                        viewModel.setActiveLibrary(libraryId)
                        val encoded = URLEncoder.encode(libraryName, "UTF-8")
                        navController.navigate("library_items/$libraryId/$encoded") {
                            popUpTo(HOME) { inclusive = true }
                        }
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
                            navController.navigate(HOME) { popUpTo(HOME) { inclusive = true } }
                        },
                        onAuthenticated = { pending ->
                            setupVm.pendingServer = pending
                            navController.navigate(SELECT_LIBRARIES)
                        },
                        onAutoCompleted = {
                            navController.navigate(HOME) { popUpTo(HOME) { inclusive = true } }
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
                                navController.navigate(HOME) { popUpTo(HOME) { inclusive = true } }
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
                LibraryItemsScreen(
                    libraryName = libraryName,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
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
                )
            }
            composable(
                route = EPUB_READER,
                arguments = listOf(
                    navArgument("itemId") { type = NavType.StringType },
                )
            ) {
                EpubReaderScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(
                route = PDF_READER,
                arguments = listOf(
                    navArgument("itemId") { type = NavType.StringType },
                )
            ) {
                PdfReaderScreen(onNavigateBack = { navController.popBackStack() })
            }
        }
    }
}

internal fun isReaderRoute(route: String?): Boolean =
    route?.startsWith(EPUB_READER.substringBefore("{")) == true ||
        route?.startsWith(PDF_READER.substringBefore("{")) == true
