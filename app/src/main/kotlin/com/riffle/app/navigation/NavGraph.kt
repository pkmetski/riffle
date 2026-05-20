package com.riffle.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.riffle.app.feature.server.AddServerScreen
import com.riffle.app.feature.server.ServerListScreen

private const val SERVER_LIST = "server_list"
private const val ADD_SERVER = "add_server"

@Composable
fun RiffleNavGraph() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = SERVER_LIST) {
        composable(SERVER_LIST) {
            ServerListScreen(onAddServer = { navController.navigate(ADD_SERVER) })
        }
        composable(ADD_SERVER) {
            AddServerScreen(onNavigateBack = { navController.popBackStack() })
        }
    }
}
