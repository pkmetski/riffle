package com.riffle.app.navigation

import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.riffle.app.feature.library.CollectionDetailScreen
import com.riffle.app.feature.library.LibraryItemsScreen
import com.riffle.app.feature.library.SeriesDetailScreen
import com.riffle.app.feature.navigation.HomeScreen
import com.riffle.app.feature.navigation.NavigationDrawerViewModel
import com.riffle.app.feature.navigation.RiffleNavigationDrawer
import com.riffle.app.feature.reader.EpubReaderScreen
import com.riffle.app.feature.reader.PdfReaderScreen
import com.riffle.app.feature.server.AddServerScreen
import com.riffle.app.feature.settings.SettingsScreen
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.net.URLEncoder

private const val HOME = "home"
private const val ADD_SERVER = "add_server"
private const val SETTINGS = "settings"
private const val LIBRARY_ITEMS = "library_items/{libraryId}/{libraryName}"
private const val SERIES_DETAIL = "series_detail/{libraryId}/{seriesId}/{seriesName}"
private const val COLLECTION_DETAIL = "collection_detail/{libraryId}/{collectionId}/{collectionName}"
private const val EPUB_READER = "epub_reader/{itemId}"
private const val PDF_READER = "pdf_reader/{itemId}"

@Composable
fun MainScreen(
    viewModel: NavigationDrawerViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val activeServer by viewModel.activeServer.collectAsState()
    val allServers by viewModel.allServers.collectAsState()
    val visibleLibraries by viewModel.visibleLibraries.collectAsState()

    val currentBackStack by navController.currentBackStackEntryAsState()
    val activeLibraryId = currentBackStack
        ?.takeIf { it.destination.route?.startsWith("library_items/") == true }
        ?.arguments?.getString("libraryId")

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
        activeServer = activeServer,
        allServers = allServers,
        visibleLibraries = visibleLibraries,
        activeLibraryId = activeLibraryId,
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
            composable(ADD_SERVER) {
                AddServerScreen(
                    onNavigateBack = {
                        navController.navigate(HOME) {
                            popUpTo(HOME) { inclusive = true }
                        }
                    }
                )
            }
            composable(SETTINGS) {
                SettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToAddServer = { navController.navigate(ADD_SERVER) },
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
                        readerRouteFor(item)?.let { navController.navigate(it) }
                    },
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
                        readerRouteFor(item)?.let { navController.navigate(it) }
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
                        readerRouteFor(item)?.let { navController.navigate(it) }
                    },
                    onNavigateBack = { navController.popBackStack() },
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
