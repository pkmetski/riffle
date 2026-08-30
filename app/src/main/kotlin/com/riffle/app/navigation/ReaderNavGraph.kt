package com.riffle.app.navigation

import android.content.Intent
import android.widget.Toast
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.koin.androidx.compose.koinViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.riffle.app.R
import com.riffle.app.feature.audiobook.AudiobookPlayerScreen
import com.riffle.app.feature.reader.EpubReaderScreen
import com.riffle.app.feature.reader.EpubReaderViewModel
import com.riffle.app.feature.reader.PdfReaderScreen
import com.riffle.app.feature.reader.ReaderNavEvent
import com.riffle.app.feature.reader.cbz.CbzReaderScreen
import java.net.URLEncoder

internal fun NavGraphBuilder.readerNavGraph(
    navController: NavController,
    windowSizeClass: WindowSizeClass,
) {
    composable(
        route = EPUB_READER,
        arguments = listOf(
            navArgument("itemId") { type = NavType.StringType },
            navArgument("startReadaloudAtSec") {
                type = NavType.FloatType
                defaultValue = -1f // -1 = opened normally, not an audiobook→readaloud handoff
            },
            navArgument("openAtCfi") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
            navArgument("openAnnotationId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
            navArgument("startTocHref") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
            navArgument("source") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
            navArgument("sourceId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        )
    ) { backStackEntry ->
        val viewModel: EpubReaderViewModel = koinViewModel()
        val context = LocalContext.current
        val exportErrorMessage = stringResource(R.string.export_pdf_error)
        // Highlights mode's "Open in book" (Task 9, ADR 0048): leaves the elided reader and
        // opens the full-book reader at the tapped highlight's CFI. Handled at the nav-host
        // level (not inside EpubReaderScreen) since it pops this route off the back stack.
        LaunchedEffect(viewModel) {
            viewModel.readerNavEvents.collect { event ->
                when (event) {
                    is ReaderNavEvent.OpenInSourceBook -> {
                        val encodedId = URLEncoder.encode(event.itemId, "UTF-8")
                        val encodedCfi = URLEncoder.encode(event.cfi, "UTF-8")
                        val encodedAnnotationId = URLEncoder.encode(event.annotationId, "UTF-8")
                        if (navController.popBackStackIfTop(backStackEntry)) {
                            navController.navigate(
                                "epub_reader/$encodedId?openAtCfi=$encodedCfi&openAnnotationId=$encodedAnnotationId"
                            )
                        }
                    }
                    ReaderNavEvent.CloseEmptyHighlights -> {
                        navController.popBackStackIfTop(backStackEntry)
                    }
                    is ReaderNavEvent.ShareHighlights -> {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/pdf"
                            putExtra(Intent.EXTRA_STREAM, event.uri)
                            putExtra(Intent.EXTRA_TITLE, event.fileName)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, null))
                    }
                    ReaderNavEvent.ExportError -> {
                        Toast.makeText(
                            context,
                            exportErrorMessage,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
        EpubReaderScreen(
            windowSizeClass = windowSizeClass,
            onNavigateBack = { navController.popBackStackIfTop(backStackEntry) },
            viewModel = viewModel,
        )
    }

    composable(
        route = PDF_READER,
        arguments = listOf(
            navArgument("itemId") { type = NavType.StringType },
        )
    ) { backStackEntry ->
        PdfReaderScreen(onNavigateBack = { navController.popBackStackIfTop(backStackEntry) })
    }

    composable(
        route = CBZ_READER,
        arguments = listOf(
            navArgument("itemId") { type = NavType.StringType },
        )
    ) { backStackEntry ->
        CbzReaderScreen(onNavigateBack = { navController.popBackStackIfTop(backStackEntry) })
    }

    composable(
        route = AUDIOBOOK_PLAYER,
        arguments = listOf(
            navArgument("itemId") { type = NavType.StringType },
            navArgument("startAtSec") {
                type = NavType.FloatType
                defaultValue = -1f // -1 = opened normally; >=0 = readaloud→audiobook handoff
            },
            // Optional playlist context — set when the player was opened from the Playlists
            // tab (via [PlaylistDetailScreen]'s Play button). On end-of-book the VM uses
            // these to look up the next item and emit PlaylistAdvance; without them Finished
            // pops the player as before.
            navArgument("playlistId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
            navArgument("libraryId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        )
    ) { backStackEntry ->
        val currentPlaylistId = backStackEntry.arguments?.getString("playlistId")
        val currentLibraryId = backStackEntry.arguments?.getString("libraryId")
        AudiobookPlayerScreen(
            windowSizeClass = windowSizeClass,
            onNavigateBack = { navController.popBackStackIfTop(backStackEntry) },
            // End-of-book with a playlist context: hop straight to the next item's player,
            // popping the current player entry so Back returns to the playlist detail
            // (rather than an ever-growing stack of dead player entries).
            onPlaylistAdvance = { nextItemId ->
                val encoded = URLEncoder.encode(nextItemId, "UTF-8")
                val pl = URLEncoder.encode(currentPlaylistId.orEmpty(), "UTF-8")
                val lib = URLEncoder.encode(currentLibraryId.orEmpty(), "UTF-8")
                // No startAtSec override — default -1 = "resume from saved position", so a
                // partially-listened next item picks up where the user left off. (The
                // earlier chain-runs-away failure mode when the next item was already at
                // 100% is now handled by [AudiobookController.clearEndOfBookCache] wiping
                // the STATE_ENDED replay before the incoming VM subscribes.)
                navController.navigate(
                    "audiobook_player/$encoded?playlistId=$pl&libraryId=$lib"
                ) {
                    popUpTo(AUDIOBOOK_PLAYER) { inclusive = true }
                }
            },
            // Swipe down → switch to the readaloud reader for the linked ebook, continuing from
            // the audiobook position. Pop the player off the stack so leaving readaloud doesn't
            // land back on a dead player, and its onCleared stops audio + flushes progress.
            onSwitchToReadaloud = { ebookItemId, atSec ->
                // Audiobook opened from the library (not from the reader overlay). Navigate
                // to the reader, replacing the player so Back doesn't return to a dead session.
                val encoded = URLEncoder.encode(ebookItemId, "UTF-8")
                navController.navigate("epub_reader/$encoded?startReadaloudAtSec=$atSec") {
                    popUpTo(AUDIOBOOK_PLAYER) { inclusive = true }
                }
            },
        )
    }
}
