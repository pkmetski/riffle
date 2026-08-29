package com.riffle.app.navigation

import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.riffle.app.feature.downloads.DownloadsScreen
import com.riffle.app.feature.library.AnnotationSearchResultsScreen
import com.riffle.app.feature.library.CollectionDetailScreen
import com.riffle.app.feature.library.FilteredBooksScreen
import com.riffle.app.feature.library.LibraryItemDetailScreen
import com.riffle.app.feature.library.LibraryItemsScreen
import com.riffle.app.feature.library.LibrarySectionScreen
import com.riffle.app.feature.library.LibrarySectionType
import com.riffle.app.feature.library.SeriesDetailScreen
import com.riffle.app.feature.library.playlists.PlaylistDetailScreen
import com.riffle.app.feature.navigation.HomeScreen
import com.riffle.app.feature.navigation.HomeViewModel
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun NavGraphBuilder.libraryNavGraph(
    navController: NavController,
    windowSizeClass: WindowSizeClass,
    drawerState: DrawerState,
    scope: CoroutineScope,
    libBackEnabled: Boolean,
    onSetActiveLibrary: (String) -> Unit,
) {
    composable(HOME) {
        HomeScreen(
            onNavigateToAddSource = {
                navController.navigateAsRoot(ADD_SOURCE_TYPE_PICKER)
            },
            onNavigateToLibrary = { sourceType, libraryId, libraryName ->
                onSetActiveLibrary(libraryId)
                navController.navigateAsRoot(
                    libraryEntryRoute(
                        HomeViewModel.StartDestination.Library(sourceType, libraryId, libraryName),
                    ),
                )
            },
        )
    }
    composable(DOWNLOADS) { backStackEntry ->
        DownloadsScreen(
            windowSizeClass = windowSizeClass,
            onNavigateBack = { navController.popBackStackIfTop(backStackEntry) },
            onItemSelected = { item ->
                navController.navigate(libraryItemDetailRoute(item))
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
        // Close the drawer when the library destination first enters composition if it
        // is open or animating open — e.g. the user tapped a library in the drawer and
        // the composable re-enters before the close animation finishes. Skip the call
        // when the drawer is already Closed: even a settled Closed state still triggers
        // a spring animation that takes 14–125 ms, unnecessarily holding backEnabled=true
        // during that window and creating a timing hazard on Back.
        LaunchedEffect(Unit) {
            if (drawerState.currentValue == DrawerValue.Open || drawerState.targetValue == DrawerValue.Open) {
                drawerState.close()
            }
        }
        LibraryItemsScreen(
            libraryName = libraryName,
            windowSizeClass = windowSizeClass,
            onOpenDrawer = { scope.launch { drawerState.open() } },
            backEnabled = libBackEnabled,
            // Read the committed back stack synchronously at handler-fire time (not via
            // Compose state) so we correctly distinguish: library_items is the committed
            // top (run library back action) vs. a sub-screen is the committed top but
            // library_items shows as the predictive-back preview or the recomposition
            // window hasn't caught up yet (pop the sub-screen instead).
            isCommittedOnLibraryItems = {
                committedTopRoute(navController.currentBackStackSnapshot().map { it.destination.route })
                    ?.startsWith("library_items/") == true
            },
            onNavigateBack = { navController.popCommittedTopFromLibraryPreview() },
            onSeriesSelected = { series ->
                navController.navigate(seriesDetailRoute(libraryId, series.id, series.name))
            },
            onCollectionSelected = { collection ->
                navController.navigate(collectionDetailRoute(libraryId, collection.id, collection.name))
            },
            onItemSelected = { item ->
                navController.navigate(libraryItemDetailRoute(item))
            },
            onAnnotationSelected = { result ->
                val encodedId = URLEncoder.encode(result.annotation.itemId, "UTF-8")
                val encodedCfi = URLEncoder.encode(result.annotation.cfi, "UTF-8")
                val encodedAnnotationId = URLEncoder.encode(result.annotation.id, "UTF-8")
                navController.navigate(
                    "epub_reader/$encodedId?openAtCfi=$encodedCfi&openAnnotationId=$encodedAnnotationId"
                )
            },
            onAudiobookBookmarkSelected = { result ->
                val encodedId = URLEncoder.encode(result.bookmark.itemId, "UTF-8")
                navController.navigate("audiobook_player/$encodedId?startAtSec=${result.bookmark.positionSec}")
            },
            onShowAllAnnotations = { query ->
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                navController.navigate("annotation_search/$libraryId?query=$encodedQuery")
            },
            onSectionSeeMore = { sectionType ->
                navController.navigate(librarySectionRoute(libraryId, libraryName, sectionType))
            },
            onAnnotatedBookClick = { sourceId, itemId ->
                navController.navigate(annotationsBookClickRoute(sourceId, itemId))
            },
            onPlaylistSelected = { playlist ->
                val encodedName = URLEncoder.encode(playlist.name, "UTF-8")
                val encodedId = URLEncoder.encode(playlist.id, "UTF-8")
                navController.navigate("playlist_detail/$libraryId/$encodedId/$encodedName")
            },
        )
    }
    composable(
        route = PLAYLIST_DETAIL,
        arguments = listOf(
            navArgument("libraryId") { type = NavType.StringType },
            navArgument("playlistId") { type = NavType.StringType },
            navArgument("playlistName") { type = NavType.StringType },
        ),
    ) { backStackEntry ->
        val playlistLibraryId = backStackEntry.arguments?.getString("libraryId").orEmpty()
        val playlistIdArg = backStackEntry.arguments?.getString("playlistId").orEmpty()
        PlaylistDetailScreen(
            onNavigateBack = { navController.popBackStackIfTop(backStackEntry) },
            onItemSelected = { item ->
                navController.navigate(libraryItemDetailRoute(item))
            },
            // Play launches the first item into the audiobook player carrying the playlist
            // context (`playlistId` + `libraryId`) — the player VM uses those to look up
            // the next item on end-of-book and hop straight into it (auto-advance).
            onPlayItem = { item ->
                val encodedId = URLEncoder.encode(item.id, "UTF-8")
                val plQ = URLEncoder.encode(playlistIdArg, "UTF-8")
                val libQ = URLEncoder.encode(playlistLibraryId, "UTF-8")
                navController.navigate(
                    "audiobook_player/$encodedId?playlistId=$plQ&libraryId=$libQ"
                )
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
                navController.navigate(libraryItemDetailRoute(item))
            },
            onNavigateBack = { navController.popBackStackIfTop(backStackEntry) },
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
                navController.navigate(libraryItemDetailRoute(item))
            },
            onNavigateBack = { navController.popBackStackIfTop(backStackEntry) },
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
                navController.navigate(libraryItemDetailRoute(item))
            },
            onNavigateBack = { navController.popBackStackIfTop(backStackEntry) },
        )
    }
    composable(
        route = LIBRARY_ITEM_DETAIL,
        arguments = listOf(
            navArgument("itemId") { type = NavType.StringType },
            navArgument("sourceId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        )
    ) { backStackEntry ->
        LibraryItemDetailScreen(
            windowSizeClass = windowSizeClass,
            onNavigateBack = { navController.popBackStackIfTop(backStackEntry) },
            onReadItem = { item ->
                readerRouteFor(item)?.let { navController.navigate(it) }
            },
            onListenItem = { item ->
                val encodedId = URLEncoder.encode(item.id, "UTF-8")
                navController.navigate("audiobook_player/$encodedId")
            },
            onReadItemAtHref = { item, href ->
                val encodedId = URLEncoder.encode(item.id, "UTF-8")
                val encodedHref = URLEncoder.encode(href, "UTF-8")
                navController.navigate("epub_reader/$encodedId?startTocHref=$encodedHref")
            },
            onListenItemAtSec = { item, startSec ->
                val encodedId = URLEncoder.encode(item.id, "UTF-8")
                navController.navigate("audiobook_player/$encodedId?startAtSec=$startSec")
            },
            onNavigateToFacet = { libraryId, facet, value ->
                val encoded = URLEncoder.encode(value, "UTF-8")
                navController.navigate("filtered_books/$libraryId/${facet.name}/$encoded")
            },
            onNavigateToSeries = { libraryId, seriesId, seriesName ->
                navController.navigate(seriesDetailRoute(libraryId, seriesId, seriesName))
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
    ) { backStackEntry ->
        FilteredBooksScreen(
            onItemSelected = { item ->
                navController.navigate(libraryItemDetailRoute(item))
            },
            onNavigateBack = { navController.popBackStackIfTop(backStackEntry) },
        )
    }
    composable(
        route = ANNOTATION_SEARCH,
        arguments = listOf(
            navArgument("libraryId") { type = NavType.StringType },
            navArgument("query") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) { backStackEntry ->
        AnnotationSearchResultsScreen(
            onNavigateBack = { navController.popBackStackIfTop(backStackEntry) },
            onAnnotationSelected = { result ->
                val encodedId = URLEncoder.encode(result.annotation.itemId, "UTF-8")
                val encodedCfi = URLEncoder.encode(result.annotation.cfi, "UTF-8")
                val encodedAnnotationId = URLEncoder.encode(result.annotation.id, "UTF-8")
                navController.navigate(
                    "epub_reader/$encodedId?openAtCfi=$encodedCfi&openAnnotationId=$encodedAnnotationId"
                )
            },
            onAudiobookBookmarkSelected = { result ->
                val encodedId = URLEncoder.encode(result.bookmark.itemId, "UTF-8")
                navController.navigate("audiobook_player/$encodedId?startAtSec=${result.bookmark.positionSec}")
            },
        )
    }
}
