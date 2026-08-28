package com.riffle.app.navigation

import androidx.compose.material3.DrawerState
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navArgument
import com.riffle.app.feature.server.AddSourceScreen
import com.riffle.app.feature.server.SelectLibrariesScreen
import com.riffle.app.feature.server.SourceSetupViewModel
import com.riffle.app.feature.server.SourceTypePickerScreen
import com.riffle.app.feature.server.SourceTypePickerViewModel
import com.riffle.app.feature.source.chitanka.AddChitankaScreen
import com.riffle.app.feature.source.chitanka.ChitankaBrowseScreen
import com.riffle.app.feature.source.gutenberg.AddGutenbergScreen
import com.riffle.app.feature.source.gutenberg.GutenbergBrowseScreen
import com.riffle.app.feature.source.localfiles.AddLocalFilesScreen
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun NavGraphBuilder.sourceNavGraph(
    navController: NavController,
    windowSizeClass: WindowSizeClass,
    drawerState: DrawerState,
    scope: CoroutineScope,
) {
    // The Source Type picker lives at NavHost level (not inside SOURCE_SETUP_GRAPH) so
    // that entering the setup graph directly for Storyteller/WebDAV/edit paths does NOT
    // implicitly push the picker as the graph's start destination onto the back stack.
    // With this shape the back stack for those flows stays [caller, ADD_SOURCE], and
    // `previousBackStackEntry.route == SETTINGS` remains the right predicate for
    // "should top-app-bar back pop to Settings?".
    composable(ADD_SOURCE_TYPE_PICKER) { backStackEntry ->
        val cameFromSettings = navController.previousBackStackEntry
            ?.destination?.route == SETTINGS
        val pickerViewModel: SourceTypePickerViewModel = hiltViewModel()
        val installedTypes by pickerViewModel.installedTypes.collectAsState()
        SourceTypePickerScreen(
            windowSizeClass = windowSizeClass,
            onNavigateBack = {
                if (cameFromSettings) navController.popBackStackIfTop(backStackEntry)
                else navController.navigateAsRootIfTop(backStackEntry, HOME)
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
        val libraryId = backStackEntry.arguments?.getString("libraryId") ?: ""
        val libraryName = backStackEntry.arguments?.getString("libraryName")
            ?.let { URLDecoder.decode(it, "UTF-8") } ?: ""
        ChitankaBrowseScreen(
            libraryName = libraryName,
            windowSizeClass = windowSizeClass,
            onOpenDrawer = { scope.launch { drawerState.open() } },
            onSectionSeeMore = { sectionType ->
                navController.navigate(librarySectionRoute(libraryId, libraryName, sectionType))
            },
            onOpenDetail = { itemId ->
                val encodedId = URLEncoder.encode(itemId, "UTF-8")
                navController.navigate("library_item_detail/$encodedId")
            },
            onAnnotatedBookClick = { sourceId, itemId ->
                navController.navigate(annotationsBookClickRoute(sourceId, itemId))
            },
        )
    }
    composable(ADD_CHITANKA) { backStackEntry ->
        val cameFromSettings = navController.previousBackStackEntry
            ?.destination?.route == SETTINGS
        AddChitankaScreen(
            windowSizeClass = windowSizeClass,
            onDone = {
                if (cameFromSettings) navController.popBackStackIfTop(backStackEntry)
                else navController.navigateAsRootIfTop(backStackEntry, HOME)
            },
            onNavigateBack = {
                if (cameFromSettings) navController.popBackStackIfTop(backStackEntry)
                else navController.navigateAsRootIfTop(backStackEntry, HOME)
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
        val libraryId = backStackEntry.arguments?.getString("libraryId") ?: ""
        val libraryName = backStackEntry.arguments?.getString("libraryName")
            ?.let { URLDecoder.decode(it, "UTF-8") } ?: ""
        GutenbergBrowseScreen(
            libraryName = libraryName,
            windowSizeClass = windowSizeClass,
            onOpenDrawer = { scope.launch { drawerState.open() } },
            onSectionSeeMore = { sectionType ->
                navController.navigate(librarySectionRoute(libraryId, libraryName, sectionType))
            },
            onOpenDetail = { itemId ->
                val encodedId = URLEncoder.encode(itemId, "UTF-8")
                navController.navigate("library_item_detail/$encodedId")
            },
            onAnnotatedBookClick = { sourceId, itemId ->
                navController.navigate(annotationsBookClickRoute(sourceId, itemId))
            },
        )
    }
    composable(ADD_GUTENBERG) { backStackEntry ->
        val cameFromSettings = navController.previousBackStackEntry
            ?.destination?.route == SETTINGS
        AddGutenbergScreen(
            windowSizeClass = windowSizeClass,
            onDone = {
                if (cameFromSettings) navController.popBackStackIfTop(backStackEntry)
                else navController.navigateAsRootIfTop(backStackEntry, HOME)
            },
            onNavigateBack = {
                if (cameFromSettings) navController.popBackStackIfTop(backStackEntry)
                else navController.navigateAsRootIfTop(backStackEntry, HOME)
            },
        )
    }
    composable(ADD_LOCAL_FILES) { backStackEntry ->
        val cameFromSettings = navController.previousBackStackEntry
            ?.destination?.route == SETTINGS
        AddLocalFilesScreen(
            windowSizeClass = windowSizeClass,
            onDone = {
                if (cameFromSettings) navController.popBackStackIfTop(backStackEntry)
                else navController.navigateAsRootIfTop(backStackEntry, HOME)
            },
            onNavigateBack = {
                if (cameFromSettings) navController.popBackStackIfTop(backStackEntry)
                else navController.navigateAsRootIfTop(backStackEntry, HOME)
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
                ?.destination?.route in setOf(SETTINGS, READALOUD_SETTINGS, ANNOTATIONS_SYNC_SETTINGS, CHANGELOG)
            AddSourceScreen(
                windowSizeClass = windowSizeClass,
                onNavigateBack = {
                    if (cameFromSettings) navController.popBackStackIfTop(backStackEntry)
                    else navController.navigateAsRootIfTop(backStackEntry, HOME)
                },
                onAuthenticated = { pending ->
                    setupVm.pendingServer = pending
                    navController.navigate(SELECT_LIBRARIES)
                },
                onAutoCompleted = {
                    if (cameFromSettings) navController.popBackStackIfTop(backStackEntry)
                    else navController.navigateAsRootIfTop(backStackEntry, HOME)
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
                LaunchedEffect(Unit) { navController.popBackStackIfTop(backStackEntry) }
            } else {
                SelectLibrariesScreen(
                    pending = pending,
                    windowSizeClass = windowSizeClass,
                    onNavigateBack = { navController.popBackStackIfTop(backStackEntry) },
                    onContinueComplete = {
                        navController.navigateAsRootIfTop(backStackEntry, HOME)
                    },
                )
            }
        }
    }
}
