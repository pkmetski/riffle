package com.riffle.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.riffle.app.feature.library.CollectionDetailScreen
import com.riffle.app.feature.library.LibraryItemsScreen
import com.riffle.app.feature.library.LibraryListScreen
import com.riffle.app.feature.library.SeriesDetailScreen
import com.riffle.app.feature.reader.EpubReaderScreen
import com.riffle.app.feature.reader.PdfReaderScreen
import com.riffle.app.feature.server.AddServerScreen
import com.riffle.app.feature.server.ServerListScreen
import com.riffle.app.feature.settings.SettingsScreen
import java.net.URLDecoder
import java.net.URLEncoder

private const val SERVER_LIST = "server_list"
private const val ADD_SERVER = "add_server"
private const val SETTINGS = "settings"
private const val LIBRARY_LIST = "library_list"
private const val LIBRARY_ITEMS = "library_items/{libraryId}/{libraryName}"
private const val SERIES_DETAIL = "series_detail/{libraryId}/{seriesId}/{seriesName}"
private const val COLLECTION_DETAIL = "collection_detail/{libraryId}/{collectionId}/{collectionName}"
private const val EPUB_READER = "epub_reader/{itemId}"
private const val PDF_READER = "pdf_reader/{itemId}"

@Composable
fun RiffleNavGraph() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = SERVER_LIST) {
        composable(SERVER_LIST) {
            ServerListScreen(
                onAddServer = { navController.navigate(ADD_SERVER) },
                onBrowseLibrary = { navController.navigate(LIBRARY_LIST) },
                onOpenSettings = { navController.navigate(SETTINGS) },
            )
        }
        composable(ADD_SERVER) {
            AddServerScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(SETTINGS) {
            SettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(LIBRARY_LIST) {
            LibraryListScreen(
                onLibrarySelected = { library ->
                    val encoded = URLEncoder.encode(library.name, "UTF-8")
                    navController.navigate("library_items/${library.id}/$encoded")
                }
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
