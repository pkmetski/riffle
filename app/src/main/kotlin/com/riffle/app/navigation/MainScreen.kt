package com.riffle.app.navigation

import com.riffle.feature.navigation.committedTopRoute
import com.riffle.feature.navigation.guardedNavigateBack
import com.riffle.feature.navigation.isReaderRoute
import com.riffle.feature.navigation.libraryEntryRoute
import com.riffle.feature.navigation.libraryItemsBackEnabled
import com.riffle.feature.navigation.NavigationDrawerViewModel
import com.riffle.feature.navigation.shouldInterceptBackForDrawer
import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import org.koin.androidx.compose.koinViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.riffle.feature.library.LibrarySectionType
import com.riffle.feature.library.HomeViewModel
import com.riffle.app.feature.navigation.RiffleNavigationDrawer
import com.riffle.feature.player.NowPlaying
import com.riffle.app.ui.isTabletLayout
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.SourceType
import java.net.URLEncoder
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    viewModel: NavigationDrawerViewModel = koinViewModel(),
) {
    val startupUpdateVm: com.riffle.app.feature.update.StartupUpdateViewModel = koinViewModel()
    val updateDialogState by startupUpdateVm.dialogState.collectAsState()
    val updateDownloadState by startupUpdateVm.downloadState.collectAsState()

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
    val isRiffleMode by viewModel.isRiffleMode.collectAsState()

    val currentBackStack by navController.currentBackStackEntryAsState()
    val activeLibraryId = currentBackStack
        ?.takeIf { it.destination.route?.startsWith("library_items/") == true }
        ?.arguments?.getString("libraryId")
    val currentRoute = currentBackStack?.destination?.route

    val drawerCurrentOpen = drawerState.currentValue == DrawerValue.Open
    val drawerTargetOpen = drawerState.targetValue == DrawerValue.Open

    // Committed top (synchronously up-to-date StateFlow; recomposes when the stack mutates).
    // Using the COMMITTED route (not the preview from currentBackStackEntryAsState) keeps
    // libBackEnabled=true even while a predictive-back gesture from library_items is in
    // progress (the committed top stays library_items until the gesture commits), so the
    // BackHandler intercepts and runs ClearSearch/ResetTab/Exit instead of letting NavHost
    // pop library_items and flash the HOME spinner.
    val committedRoute = navController.committedTopRouteAsState()
    val libBackEnabled = libraryItemsBackEnabled(committedRoute, isTablet, drawerCurrentOpen, drawerTargetOpen)

    val usePermanentDrawer = isTablet
    // Reader screens are immersive — collapse the permanent side panel so the book/PDF
    // fills the width, matching the modal drawer's gesture suppression on phones.
    val hidePermanentDrawerPanel = isReaderRoute(currentRoute)

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // Gate on seenStop so that rotation (which replays ON_START on the new observer
        // with a fresh seenStop=false) doesn't re-show the dialog after the user dismissed
        // it. Only a genuine background→foreground transition sets seenStop=true first.
        // Cold start is handled by StartupUpdateViewModel.init; this observer only covers
        // subsequent foreground returns.
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

    // A media-notification tap jumps to whatever is playing. launchSingleTop makes this a no-op when
    // that screen is already current (the common case, since audio only plays while its screen is) —
    // so playback is never restarted; otherwise it opens the right player.
    LaunchedEffect(Unit) {
        viewModel.openNowPlayingRequests.collect {
            val target = viewModel.currentNowPlaying() ?: return@collect
            val encoded = URLEncoder.encode(target.itemId, "UTF-8")
            val route = when (target) {
                is NowPlaying.Audiobook -> {
                    val encodedSource = URLEncoder.encode(target.sourceId, "UTF-8")
                    "audiobook_player/$encodedSource/$encoded"
                }
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
            // Wait for setActiveServer's coroutine to commit the DB write before navigating.
            // Navigating to HOME immediately causes getStartDestination() to run before the
            // new source is active in the DB, returning NoLibraries → "Unable to connect" screen.
            // drop(1) on activeServer is also unreliable in Riffle mode (all sources inactive →
            // null→null deduplicated, so drop(1) is never consumed). Waiting here is the correct fix.
            scope.launch {
                // 5 s safety valve: if the source was deleted between drawer-open and tap,
                // setActiveAtomic writes 0 rows and activeServer never emits a matching value.
                // Timeout ensures we still navigate (getStartDestination handles the no-source case).
                withTimeoutOrNull(5_000) {
                    viewModel.activeServer.filterNotNull().first { it.id == server.id }
                }
                navController.navigateAsRoot(HOME)
            }
        },
        onLibrarySelected = { library ->
            viewModel.setActiveLibrary(library.id)
            scope.launch { drawerState.close() }
            navController.navigateAsRoot(libraryEntryRoute(activeServer?.type, library.id, library.name))
        },
        onDownloadsSelected = {
            scope.launch { drawerState.close() }
            if (navController.currentDestination?.route != DOWNLOADS) navController.navigate(DOWNLOADS)
        },
        onSettingsSelected = {
            scope.launch { drawerState.close() }
            if (navController.currentDestination?.route != SETTINGS) navController.navigate(SETTINGS)
        },
        onRiffleSelected = {
            viewModel.setRiffleActive()
            scope.launch { drawerState.close() }
            if (navController.currentDestination?.route != RIFFLE) navController.navigateAsRoot(RIFFLE)
        },
        isRiffleActive = isRiffleMode,
    ) {
        NavHost(
            navController = navController,
            startDestination = HOME,
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            libraryNavGraph(
                navController = navController,
                windowSizeClass = windowSizeClass,
                drawerState = drawerState,
                scope = scope,
                libBackEnabled = libBackEnabled,
                onSetActiveLibrary = { viewModel.setActiveLibrary(it) },
            )
            sourceNavGraph(
                navController = navController,
                windowSizeClass = windowSizeClass,
                drawerState = drawerState,
                scope = scope,
            )
            settingsNavGraph(
                navController = navController,
                windowSizeClass = windowSizeClass,
            )
            readerNavGraph(
                navController = navController,
                windowSizeClass = windowSizeClass,
            )
        }
    }

    // Material3's ModalNavigationDrawer doesn't install its own BackHandler — so when the
    // drawer is open or animating we add one here. Must be registered AFTER RiffleNavigationDrawer
    // (and thus after NavHost and the NavController) so it has higher priority in the
    // OnBackPressedDispatcher (last-registered wins). A position before NavHost gives the
    // NavController's callback higher priority, causing it to pop library_items and trigger a
    // library reload instead of closing the drawer.
    //
    // Check both currentValue and targetValue: targetValue flips to Open the instant
    // drawerState.open() is called (catches Back during the open animation), and currentValue
    // stays Open until the close animation finishes (catches Back during the close animation).
    // Using only targetValue misses the close-animation window; using only isOpen (currentValue)
    // misses the open-animation window that caused the burger-tap white-screen freeze.
    BackHandler(enabled = shouldInterceptBackForDrawer(
        usePermanentDrawer,
        drawerCurrentOpen = drawerState.currentValue == DrawerValue.Open,
        drawerTargetOpen = drawerState.targetValue == DrawerValue.Open,
    )) {
        scope.launch { drawerState.close() }
    }

    updateDialogState?.let { dialogState ->
        com.riffle.app.feature.update.UpdateAvailableDialog(
            state = dialogState,
            downloadState = updateDownloadState,
            onIgnore = startupUpdateVm::ignoreVersion,
            onUpdate = startupUpdateVm::startUpdate,
            onDismiss = startupUpdateVm::dismissDialog,
        )
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

// LibraryItemsScreen can remain composed as the predictive-back preview while a sub-screen is
// still the committed top. In that one case, popping the actual committed top is intentional.
internal fun NavController.popCommittedTopFromLibraryPreview(): Boolean = popBackStack()

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

/**
 * Reads [NavController.currentBackStack] synchronously.
 *
 * `@SuppressLint("RestrictedApi")` is required — see [committedTopRouteAsState] for rationale.
 */
@android.annotation.SuppressLint("RestrictedApi")
internal fun NavController.currentBackStackSnapshot(): List<NavBackStackEntry> =
    currentBackStack.value.toList()
