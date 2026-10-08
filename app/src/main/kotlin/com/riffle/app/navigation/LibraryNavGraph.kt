package com.riffle.app.navigation

import com.riffle.feature.navigation.collectionDetailRoute
import com.riffle.feature.navigation.libraryEntryRoute
import com.riffle.feature.navigation.libraryItemDetailRoute
import com.riffle.feature.navigation.librarySectionRoute
import com.riffle.feature.navigation.seriesDetailRoute
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.riffle.feature.designsystem.BookCoverTile
import com.riffle.feature.designsystem.coverGridMinCell
import com.riffle.feature.library.LibrarySectionType
import com.riffle.feature.library.ui.CollectionDetailScreen
import com.riffle.feature.library.ui.DownloadsScreen
import com.riffle.feature.library.ui.LibraryItemDetailScreen
import com.riffle.feature.library.ui.LibraryItemsScreen
import com.riffle.feature.library.ui.LibrarySectionScreen
import com.riffle.feature.library.RiffleViewModel
import com.riffle.feature.library.ui.RiffleScreen
import com.riffle.feature.library.ui.SeriesDetailScreen
import com.riffle.feature.library.ui.annotationSearchLabels
import com.riffle.feature.library.ui.filteredBooksLabels
import com.riffle.feature.library.ui.playlistLabels
import com.riffle.app.feature.navigation.HomeScreen
import com.riffle.feature.downloads.DownloadsViewModel
import com.riffle.feature.library.AnnotationSearchViewModel
import com.riffle.feature.library.AnnotationsListViewModel
import com.riffle.feature.library.CollectionDetailViewModel
import com.riffle.feature.library.FilteredBooksViewModel
import com.riffle.feature.library.HomeViewModel
import com.riffle.feature.library.LibraryItemDetailViewModel
import com.riffle.feature.library.LibraryItemsViewModel
import com.riffle.feature.library.LibrarySectionViewModel
import com.riffle.feature.library.PlaylistDetailViewModel
import com.riffle.feature.library.SeriesDetailViewModel
import com.riffle.feature.library.ui.AnnotationSearchResultsScreen
import com.riffle.feature.library.ui.FilteredBooksScreen
import com.riffle.feature.library.ui.PlaylistDetailScreen
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun NavGraphBuilder.libraryNavGraph(
    navController: NavController,
    drawerState: DrawerState,
    scope: CoroutineScope,
    onSetActiveLibrary: (String) -> Unit,
) {
    composable(RIFFLE) {
        RiffleScreen(
            viewModel = koinViewModel<RiffleViewModel>(),
            onOpenDrawer = { scope.launch { drawerState.open() } },
            onItemSelected = { sourceId, itemId ->
                val encodedId = URLEncoder.encode(itemId, "UTF-8")
                val encodedSourceId = URLEncoder.encode(sourceId, "UTF-8")
                navController.navigate("library_item_detail/$encodedId?sourceId=$encodedSourceId")
            },
            onAnnotatedBookClick = { sourceId, itemId ->
                navController.navigate(annotationsBookClickRoute(sourceId, itemId))
            },
        )
    }
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
            onNavigateToRiffle = {
                navController.navigateAsRoot(RIFFLE)
            },
        )
    }
    composable(DOWNLOADS) { backStackEntry ->
        DownloadsScreen(
            viewModel = koinViewModel<DownloadsViewModel>(),
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
            libraryId = libraryId,
            libraryName = libraryName,
            viewModel = koinViewModel<LibraryItemsViewModel>(parameters = { parametersOf(libraryId) }),
            annotationsViewModel = koinViewModel<AnnotationsListViewModel>(parameters = { parametersOf(libraryId) }),
            onOpenDrawer = { scope.launch { drawerState.open() } },
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
                val encodedSourceId = URLEncoder.encode(result.bookmark.sourceId, "UTF-8")
                val encodedId = URLEncoder.encode(result.bookmark.itemId, "UTF-8")
                navController.navigate("audiobook_player/$encodedSourceId/$encodedId?startAtSec=${result.bookmark.positionSec}&userPlay=true")
            },
            onShowAllAnnotations = { query ->
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                navController.navigate("annotation_search/$libraryId?query=$encodedQuery")
            },
            onSectionSeeMore = { sectionType ->
                navController.navigate(librarySectionRoute(libraryId, libraryName, sectionType))
            },
            onAnnotatedBookSelected = { sourceId, itemId ->
                navController.navigate(annotationsBookClickRoute(sourceId, itemId))
            },
            onPlaylistSelected = { playlist ->
                val encodedName = URLEncoder.encode(playlist.name, "UTF-8")
                val encodedId = URLEncoder.encode(playlist.id, "UTF-8")
                navController.navigate("playlist_detail/$libraryId/$encodedId/$encodedName")
            },
            onSearchAnnotations = { query ->
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                navController.navigate("annotation_search/$libraryId?query=$encodedQuery")
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
            viewModel = koinViewModel<PlaylistDetailViewModel>(),
            labels = playlistLabels(),
            onNavigateBack = { navController.popBackStackIfTop(backStackEntry) },
            onItemSelected = { item ->
                navController.navigate(libraryItemDetailRoute(item))
            },
            // Play launches the first item into the audiobook player carrying the playlist
            // context (`playlistId` + `libraryId`) — the player VM uses those to look up
            // the next item on end-of-book and hop straight into it (auto-advance).
            onPlayItem = { item ->
                val encodedSourceId = URLEncoder.encode(item.sourceId, "UTF-8")
                val encodedId = URLEncoder.encode(item.id, "UTF-8")
                val plQ = URLEncoder.encode(playlistIdArg, "UTF-8")
                val libQ = URLEncoder.encode(playlistLibraryId, "UTF-8")
                navController.navigate(
                    "audiobook_player/$encodedSourceId/$encodedId?playlistId=$plQ&libraryId=$libQ&userPlay=true"
                )
            },
            itemContent = { item, token, onClick ->
                BookCoverTile(item = item, token = token, onClick = onClick)
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
        val sectionLibraryId = backStackEntry.arguments?.getString("libraryId").orEmpty()
        val sectionType = LibrarySectionType.valueOf(
            backStackEntry.arguments?.getString("sectionType") ?: LibrarySectionType.IN_PROGRESS.name
        )
        LibrarySectionScreen(
            libraryId = sectionLibraryId,
            sectionType = sectionType,
            viewModel = koinViewModel<LibrarySectionViewModel>(parameters = { parametersOf(sectionLibraryId, sectionType) }),
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
        val seriesLibraryId = backStackEntry.arguments?.getString("libraryId").orEmpty()
        val seriesId = backStackEntry.arguments?.getString("seriesId").orEmpty()
        val seriesName = URLDecoder.decode(
            backStackEntry.arguments?.getString("seriesName") ?: "",
            "UTF-8"
        )
        SeriesDetailScreen(
            seriesId = seriesId,
            libraryId = seriesLibraryId,
            seriesName = seriesName,
            viewModel = koinViewModel<SeriesDetailViewModel>(parameters = { parametersOf(seriesId, seriesLibraryId) }),
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
        val collectionLibraryId = backStackEntry.arguments?.getString("libraryId").orEmpty()
        val collectionId = backStackEntry.arguments?.getString("collectionId").orEmpty()
        val collectionName = URLDecoder.decode(
            backStackEntry.arguments?.getString("collectionName") ?: "",
            "UTF-8"
        )
        CollectionDetailScreen(
            collectionId = collectionId,
            libraryId = collectionLibraryId,
            collectionName = collectionName,
            viewModel = koinViewModel<CollectionDetailViewModel>(parameters = { parametersOf(collectionId, collectionLibraryId) }),
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
        val itemId = backStackEntry.arguments?.getString("itemId").orEmpty()
        val sourceId = backStackEntry.arguments?.getString("sourceId")
        LibraryItemDetailScreen(
            itemId = itemId,
            sourceId = sourceId,
            vm = koinViewModel<LibraryItemDetailViewModel>(parameters = { parametersOf(itemId, sourceId) }),
            onBack = { navController.popBackStackIfTop(backStackEntry) },
            onRead = { item ->
                readerRouteFor(item)?.let { navController.navigate(it) }
            },
            onListen = { item ->
                val encodedSourceId = URLEncoder.encode(item.sourceId, "UTF-8")
                val encodedId = URLEncoder.encode(item.id, "UTF-8")
                navController.navigate("audiobook_player/$encodedSourceId/$encodedId?userPlay=true")
            },
            onReadItemAtHref = { item, href ->
                val encodedId = URLEncoder.encode(item.id, "UTF-8")
                val encodedHref = URLEncoder.encode(href, "UTF-8")
                val sourceParam = if (item.sourceId.isNotBlank()) {
                    "&sourceId=${URLEncoder.encode(item.sourceId, "UTF-8")}"
                } else {
                    ""
                }
                navController.navigate("epub_reader/$encodedId?startTocHref=$encodedHref$sourceParam")
            },
            onListenItemAtSec = { item, startSec ->
                val encodedSourceId = URLEncoder.encode(item.sourceId, "UTF-8")
                val encodedId = URLEncoder.encode(item.id, "UTF-8")
                navController.navigate("audiobook_player/$encodedSourceId/$encodedId?startAtSec=$startSec&userPlay=true")
            },
            onFacetSelected = { libId, facet, value ->
                val encoded = URLEncoder.encode(value, "UTF-8")
                navController.navigate("filtered_books/$libId/${facet.name}/$encoded")
            },
            onNavigateToSeries = { libId, seriesId, seriesName ->
                navController.navigate(seriesDetailRoute(libId, seriesId, seriesName))
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
            viewModel = koinViewModel<FilteredBooksViewModel>(),
            labels = filteredBooksLabels(),
            minCellSize = coverGridMinCell(),
            onItemSelected = { item ->
                navController.navigate(libraryItemDetailRoute(item))
            },
            onNavigateBack = { navController.popBackStackIfTop(backStackEntry) },
            tileContent = { item, token, onClick ->
                BookCoverTile(item = item, token = token, onClick = onClick)
            },
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
            viewModel = koinViewModel<AnnotationSearchViewModel>(),
            labels = annotationSearchLabels(),
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
                val encodedSourceId = URLEncoder.encode(result.bookmark.sourceId, "UTF-8")
                val encodedId = URLEncoder.encode(result.bookmark.itemId, "UTF-8")
                navController.navigate("audiobook_player/$encodedSourceId/$encodedId?startAtSec=${result.bookmark.positionSec}&userPlay=true")
            },
        )
    }
}
