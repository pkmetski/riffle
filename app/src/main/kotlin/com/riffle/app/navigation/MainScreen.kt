package com.riffle.app.navigation

import com.riffle.feature.navigation.committedTopRoute
import com.riffle.feature.navigation.guardedNavigateBack
import com.riffle.feature.navigation.isReaderRoute
import com.riffle.feature.navigation.libraryEntryRoute
import com.riffle.feature.navigation.LibraryNav
import com.riffle.feature.navigation.openItemForReading
import com.riffle.feature.navigation.openReadItemForReading
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.riffle.app.BuildConfig
import com.riffle.app.ui.isTabletLayout
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.usecase.RecordItemOpened
import com.riffle.feature.designsystem.NoOp
import com.riffle.feature.downloads.DownloadsViewModel
import com.riffle.feature.library.RiffleViewModel
import com.riffle.feature.library.ui.AppSection
import com.riffle.feature.library.ui.AppShell
import com.riffle.feature.library.ui.DownloadsActions
import com.riffle.feature.library.ui.DownloadsScreen
import com.riffle.feature.library.ui.LibraryItemDetailActions
import com.riffle.feature.library.ui.LibraryItemDetailScreen
import com.riffle.feature.library.ui.RiffleScreen
import java.net.URLEncoder

internal const val HOME = "home"
internal const val RIFFLE = "riffle"
internal const val SOURCE_SETUP_GRAPH = "source_setup"
internal const val ADD_SOURCE_TYPE_PICKER = "add_source_type_picker"
internal const val ADD_LOCAL_FILES = "add_local_files"
internal const val ADD_CHITANKA = "add_chitanka"
internal const val CHITANKA_BROWSE = "chitanka_browse/{libraryId}/{libraryName}"
internal const val ADD_GUTENBERG = "add_gutenberg"
internal const val GUTENBERG_BROWSE = "gutenberg_browse/{libraryId}/{libraryName}"
internal const val ADD_RADIO_ES = "add_radio_es"
internal const val RADIO_ES_BROWSE = "radio_es_browse/{libraryId}/{libraryName}"
internal const val OREILLY_BROWSE = "oreilly_browse/{libraryId}/{libraryName}"
internal const val ADD_OREILLY = "add_oreilly"
internal const val ADD_SOURCE = "add_source"
internal const val ADD_SOURCE_ROUTE = "add_source?type={type}&editId={editId}"

internal const val SELECT_LIBRARIES = "select_libraries"
internal const val SETTINGS = "settings"
internal const val READALOUD_SETTINGS = "settings/readaloud"
internal const val ANNOTATIONS_SYNC_SETTINGS = "settings/annotation_sync"
internal const val DICTIONARY_PACKS_SETTINGS = "settings/dictionary_packs"
internal const val CHANGELOG = "settings/changelog"
internal const val ANNOTATION_SYNC_MAINTENANCE = "settings/annotation_sync/maintenance"
internal const val DEBUG_LOGS = "settings/debug_logs"
internal const val DEVELOPER_OPTIONS = "settings/developer_options"
internal const val READALOUD_MATCHES = "readaloud_matches/{sourceId}?pairBookId={pairBookId}"
internal const val DOWNLOADS = "downloads"
internal const val LIBRARY_ITEMS = "library_items/{libraryId}/{libraryName}"
internal const val LIBRARY_SECTION = "library_section/{libraryId}/{libraryName}/{sectionType}"
internal const val SERIES_DETAIL = "series_detail/{libraryId}/{seriesId}/{seriesName}"
internal const val COLLECTION_DETAIL = "collection_detail/{libraryId}/{collectionId}/{collectionName}"
internal const val FILTERED_BOOKS = "filtered_books/{libraryId}/{facetType}/{facetValue}"
internal const val LIBRARY_ITEM_DETAIL = "library_item_detail/{itemId}?sourceId={sourceId}"
internal const val PLAYLIST_DETAIL = "playlist_detail/{libraryId}/{playlistId}/{playlistName}"
internal const val ANNOTATION_SEARCH = "annotation_search/{libraryId}?query={query}"
internal const val AUDIOBOOK_PLAYER = "audiobook_player/{sourceId}/{itemId}?startAtSec={startAtSec}&playlistId={playlistId}&libraryId={libraryId}&userPlay={userPlay}"


@Composable
fun MainScreen(
    windowSizeClass: WindowSizeClass,
) {
    val startupUpdateVm: com.riffle.app.feature.update.StartupUpdateViewModel = koinViewModel()
    val updateDialogState by startupUpdateVm.dialogState.collectAsState()
    val updateDownloadState by startupUpdateVm.downloadState.collectAsState()

    val isTablet = windowSizeClass.isTabletLayout()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var seenStop = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> seenStop = true
                Lifecycle.Event.ON_START -> if (seenStop) startupUpdateVm.checkNow()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AppShell(
        isTabletLayout = isTablet,
        appVersion = BuildConfig.VERSION_NAME,
        appSha = BuildConfig.GIT_SHA,
        libraryContent = {
            val libraryNavController = rememberNavController()
            val libCommittedRoute = libraryNavController.committedTopRouteAsState()
            val libBackEnabled = libCommittedRoute?.startsWith("library_items/") == true

            val currentBackStack by libraryNavController.currentBackStackEntryAsState()
            val currentRoute = currentBackStack?.destination?.route
            LaunchedEffect(currentRoute) {
                onReaderActiveChanged(isReaderRoute(currentRoute))
            }

            LaunchedEffect(pendingLibraryNav) {
                val nav = pendingLibraryNav ?: return@LaunchedEffect
                val route = libraryNavToAndroidRoute(nav)
                    ?: if (nav is LibraryNav.ItemDetail) {
                        val encodedId = URLEncoder.encode(nav.itemId, "UTF-8")
                        val sourceParam = nav.sourceId?.let { "?sourceId=${URLEncoder.encode(it, "UTF-8")}" } ?: ""
                        "library_item_detail/$encodedId$sourceParam"
                    } else null
                route?.let { r -> libraryNavController.navigate(r) { launchSingleTop = true } }
                onPendingLibraryNavHandled()
            }

            NavHost(
                navController = libraryNavController,
                startDestination = HOME,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None },
            ) {
                libraryNavGraph(
                    navController = libraryNavController,
                    onOpenDrawer = onOpenDrawer,
                    libBackEnabled = libBackEnabled,
                    windowSizeClass = windowSizeClass,
                    onSetActiveLibrary = { onActiveLibraryChanged(it) },
                    onNavigateToRiffle = { onSwitchToSection(AppSection.Riffle) },
                )
                sourceNavGraph(
                    navController = libraryNavController,
                    windowSizeClass = windowSizeClass,
                    onOpenDrawer = onOpenDrawer,
                )
                readerNavGraph(navController = libraryNavController, windowSizeClass = windowSizeClass)
            }
        },
        riffleContent = {
            val riffleViewModel: RiffleViewModel = koinViewModel()
            RiffleScreen(
                viewModel = riffleViewModel,
                onOpenDrawer = onOpenDrawer,
                onItemSelected = { sourceId, itemId ->
                    onNavigateToLibraryWith(LibraryNav.ItemDetail(itemId, sourceId.ifEmpty { null }))
                },
                onAnnotatedBookClick = { sourceId, itemId ->
                    onNavigateToLibraryWith(LibraryNav.ElidedReader(itemId, sourceId.ifEmpty { null }))
                },
            )
        },
        downloadsContent = {
            val downloadsViewModel: DownloadsViewModel = koinViewModel()
            val applicationScope = koinInject<ApplicationScope>()
            val recordItemOpened = koinInject<RecordItemOpened>()
            var downloadsDetailNav by remember { mutableStateOf<LibraryNav.ItemDetail?>(null) }
            val detail = downloadsDetailNav
            if (detail != null) {
                LibraryItemDetailScreen(
                    itemId = detail.itemId,
                    sourceId = detail.sourceId,
                    actions = LibraryItemDetailActions(
                        onBack = { downloadsDetailNav = null },
                        onRead = { item ->
                            openReadItemForReading(item, applicationScope, recordItemOpened::invoke)
                                ?.let { onNavigateToLibraryWith(it); downloadsDetailNav = null }
                        },
                        onListen = { item ->
                            openItemForReading(item, applicationScope, recordItemOpened::invoke)
                                ?.let { onNavigateToLibraryWith(it); downloadsDetailNav = null }
                        },
                        onReadItemAtHref = { item, href ->
                            onNavigateToLibraryWith(LibraryNav.Reader(item, startHref = href))
                            downloadsDetailNav = null
                        },
                        onListenItemAtSec = { item, sec ->
                            onNavigateToLibraryWith(LibraryNav.AudiobookPlayer(item, startAtSec = sec))
                            downloadsDetailNav = null
                        },
                        onFacetSelected = NoOp.facetNavUnavailableInDownloads,
                        onNavigateToSeries = NoOp.seriesNavUnavailableInDownloads,
                    ),
                )
            } else {
                DownloadsScreen(
                    viewModel = downloadsViewModel,
                    isExpandedWidth = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded,
                    actions = DownloadsActions(
                        onNavigateBack = onNavigateBack,
                        onItemSelected = { item ->
                            downloadsDetailNav = LibraryNav.ItemDetail(item.id, item.sourceId.ifEmpty { null })
                        },
                    ),
                )
            }
        },
        settingsContent = {
            val settingsNavController = rememberNavController()
            NavHost(
                navController = settingsNavController,
                startDestination = SETTINGS,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None },
            ) {
                settingsNavGraph(
                    navController = settingsNavController,
                    windowSizeClass = windowSizeClass,
                    onNavigateBack = onNavigateBack,
                )
                sourceNavGraph(
                    navController = settingsNavController,
                    windowSizeClass = windowSizeClass,
                    onOpenDrawer = onOpenDrawer,
                )
                // Source setup navigation from within settings completes by navigating to HOME.
                // In the settings NavHost HOME is not a real destination, so intercept and
                // return to SETTINGS instead.
                composable(HOME) {
                    LaunchedEffect(Unit) {
                        settingsNavController.navigate(SETTINGS) {
                            popUpTo(SETTINGS) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }
            }
        },
        updateDialogContent = {
            updateDialogState?.let { dialogState ->
                com.riffle.app.feature.update.UpdateAvailableDialog(
                    state = dialogState,
                    downloadState = updateDownloadState,
                    onIgnore = startupUpdateVm::ignoreVersion,
                    onUpdate = startupUpdateVm::startUpdate,
                    onDismiss = startupUpdateVm::dismissDialog,
                )
            }
        },
    )
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

private fun NavController.isCommittedTop(backStackEntry: NavBackStackEntry): Boolean =
    currentBackStackEntry == backStackEntry

internal fun NavController.popBackStackIfTop(backStackEntry: NavBackStackEntry): Boolean =
    guardedNavigateBack(
        isStillTop = { isCommittedTop(backStackEntry) },
        action = { popBackStack() },
    )

internal fun NavController.navigateAsRootIfTop(backStackEntry: NavBackStackEntry, route: String): Boolean =
    guardedNavigateBack(
        isStillTop = { isCommittedTop(backStackEntry) },
        action = { navigateAsRoot(route) },
    )

// Navigating to HOME on every source switch lets getStartDestination() pick the correct library
// for the new source (last-opened per source, falling back to the first in the list).
internal fun shouldNavigateHomeOnSourceSwitch(): Boolean = true

/**
 * Returns the committed top route as Compose state, recomposing whenever the back stack changes.
 *
 * [NavController.currentBackStack] is used intentionally: [currentBackStackEntryAsState] reflects
 * the *preview* destination during a predictive-back gesture, which would incorrectly disable
 * [libraryItemsBackEnabled] while the gesture is still in progress. Reading the committed back
 * stack via [NavController.currentBackStack] avoids this.
 *
 * `@SuppressLint("RestrictedApi")` is required because [NavController.currentBackStack] is
 * annotated `@RestrictedApi` in `org.jetbrains.androidx.navigation` 2.9.x (as it is in
 * `androidx.navigation`). The annotation is still present in the JetBrains fork — suppress it
 * rather than routing around the API.
 */
@android.annotation.SuppressLint("RestrictedApi")
@Composable
internal fun NavController.committedTopRouteAsState(): String? {
    val backStack by currentBackStack.collectAsState()
    return committedTopRoute(backStack.map { it.destination.route })
}
