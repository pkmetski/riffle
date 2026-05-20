package com.riffle.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.riffle.app.feature.library.LibraryItemsScreen
import com.riffle.app.feature.library.LibraryListScreen
import com.riffle.app.feature.server.AddServerScreen
import com.riffle.app.feature.server.ServerListScreen
import java.net.URLDecoder
import java.net.URLEncoder

private const val SERVER_LIST = "server_list"
private const val ADD_SERVER = "add_server"
private const val LIBRARY_LIST = "library_list"
private const val LIBRARY_ITEMS = "library_items/{libraryId}/{libraryName}"

@Composable
fun RiffleNavGraph() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = SERVER_LIST) {
        composable(SERVER_LIST) {
            ServerListScreen(
                onAddServer = { navController.navigate(ADD_SERVER) },
                onBrowseLibrary = { navController.navigate(LIBRARY_LIST) },
            )
        }
        composable(ADD_SERVER) {
            AddServerScreen(onNavigateBack = { navController.popBackStack() })
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
            val libraryName = URLDecoder.decode(
                backStackEntry.arguments?.getString("libraryName") ?: "",
                "UTF-8"
            )
            LibraryItemsScreen(
                libraryName = libraryName,
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}
