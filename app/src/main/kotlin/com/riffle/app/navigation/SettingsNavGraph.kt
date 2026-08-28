package com.riffle.app.navigation

import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.riffle.app.feature.settings.SettingsScreen
import com.riffle.app.feature.settings.SettingsViewModel
import com.riffle.app.feature.settings.annotationsync.AnnotationSyncMaintenanceScreen
import com.riffle.app.feature.settings.annotationsync.AnnotationsSyncSettingsScreen
import com.riffle.app.feature.settings.debug.DebugLogScreen
import com.riffle.app.feature.settings.developer.DeveloperOptionsScreen
import com.riffle.app.feature.settings.dictionary.DictionaryPacksScreen
import com.riffle.app.feature.settings.readaloud.ReadaloudMatchesScreen
import com.riffle.app.feature.settings.readaloud.ReadaloudSettingsScreen
import com.riffle.app.feature.update.ChangelogScreen
import java.net.URLEncoder

internal fun NavGraphBuilder.settingsNavGraph(
    navController: NavController,
    windowSizeClass: WindowSizeClass,
) {
    composable(SETTINGS) { backStackEntry ->
        SettingsScreen(
            windowSizeClass = windowSizeClass,
            onNavigateBack = { navController.popBackStackIfTop(backStackEntry) },
            // Storyteller/WebDAV are Services (not Sources) and deep-link straight to the
            // form from their respective Settings drill-ins; editing an existing ABS
            // Source also skips the picker (the Source Type is already known). All three
            // paths still flow through `onNavigateToAddSource(backend, editId)`.
            onNavigateToAddSource = { backend, editId ->
                val params = buildList {
                    add("type=${backend.routeType}")
                    if (!editId.isNullOrEmpty()) add("editId=${URLEncoder.encode(editId, "UTF-8")}")
                }.joinToString("&")
                navController.navigate("$ADD_SOURCE?$params")
            },
            // The "Add source" button in the Sources section always launches the picker;
            // once a SourceType is picked the picker itself routes to the type's addRoute
            // (see [addSourceRouteFor]).
            onNavigateToAddSourcePicker = { navController.navigate(ADD_SOURCE_TYPE_PICKER) },
            onNavigateToAddLocalFolder = { navController.navigate(ADD_LOCAL_FILES) },
            onNavigateToReadaloudSettings = { navController.navigate(READALOUD_SETTINGS) },
            onNavigateToAnnotationsSyncSettings = { navController.navigate(ANNOTATIONS_SYNC_SETTINGS) },
            onNavigateToDeveloperOptions = { navController.navigate(DEVELOPER_OPTIONS) },
            onNavigateToDictionaryPacks = { navController.navigate(DICTIONARY_PACKS_SETTINGS) },
            onNavigateToDebugLogs = { navController.navigate(DEBUG_LOGS) },
            onNavigateToChangelog = { navController.navigate(CHANGELOG) },
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
        val settingsVm: SettingsViewModel = hiltViewModel(settingsEntry)
        ReadaloudSettingsScreen(
            onNavigateBack = { navController.popBackStackIfTop(backStackEntry) },
            onNavigateToAddSource = { backend, editId ->
                val params = buildList {
                    add("type=${backend.routeType}")
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
    composable(CHANGELOG) { backStackEntry ->
        ChangelogScreen(
            onNavigateBack = { navController.popBackStackIfTop(backStackEntry) },
        )
    }
    composable(ANNOTATIONS_SYNC_SETTINGS) { backStackEntry ->
        val settingsEntry = remember(backStackEntry) {
            navController.getBackStackEntry(SETTINGS)
        }
        val settingsVm: SettingsViewModel = hiltViewModel(settingsEntry)
        AnnotationsSyncSettingsScreen(
            onNavigateBack = { navController.popBackStackIfTop(backStackEntry) },
            onNavigateToAddSource = { backend, editId ->
                val params = buildList {
                    add("type=${backend.routeType}")
                    if (!editId.isNullOrEmpty()) add("editId=${URLEncoder.encode(editId, "UTF-8")}")
                }.joinToString("&")
                navController.navigate("$ADD_SOURCE?$params")
            },
            onNavigateToMaintenance = { navController.navigate(ANNOTATION_SYNC_MAINTENANCE) },
            viewModel = settingsVm,
        )
    }
    composable(DICTIONARY_PACKS_SETTINGS) { backStackEntry ->
        DictionaryPacksScreen(
            onNavigateBack = { navController.popBackStackIfTop(backStackEntry) },
        )
    }
    composable(ANNOTATION_SYNC_MAINTENANCE) { backStackEntry ->
        AnnotationSyncMaintenanceScreen(
            onNavigateBack = { navController.popBackStackIfTop(backStackEntry) },
        )
    }
    composable(DEVELOPER_OPTIONS) { backStackEntry ->
        val settingsEntry = remember(backStackEntry) {
            navController.getBackStackEntry(SETTINGS)
        }
        val settingsVm: SettingsViewModel = hiltViewModel(settingsEntry)
        DeveloperOptionsScreen(
            onNavigateBack = { navController.popBackStackIfTop(backStackEntry) },
            onNavigateToDebugLogs = { navController.navigate(DEBUG_LOGS) },
            viewModel = settingsVm,
        )
    }
    composable(DEBUG_LOGS) { backStackEntry ->
        DebugLogScreen(onNavigateBack = { navController.popBackStackIfTop(backStackEntry) })
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
    ) { backStackEntry ->
        ReadaloudMatchesScreen(
            onNavigateBack = { navController.popBackStackIfTop(backStackEntry) },
        )
    }
}
