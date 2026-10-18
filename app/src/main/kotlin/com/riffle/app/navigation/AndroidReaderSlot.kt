package com.riffle.app.navigation

import android.content.Intent
import android.widget.Toast
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.riffle.app.R
import com.riffle.app.feature.audiobook.AudiobookPlayerScreen
import com.riffle.app.feature.reader.EpubReaderScreen
import com.riffle.app.feature.reader.EpubReaderViewModel
import com.riffle.app.feature.reader.PdfReaderScreen
import com.riffle.app.feature.reader.ReaderNavEvent
import com.riffle.app.feature.reader.cbz.CbzReaderScreen
import com.riffle.feature.library.ui.ScreenScopedViewModelHost
import com.riffle.feature.navigation.LibraryNav
import com.riffle.feature.player.AudiobookPlayerViewModel
import com.riffle.feature.player.ui.PlayerHostActions
import com.riffle.feature.reader.CbzReaderViewModel
import com.riffle.feature.reader.PdfReaderViewModel
import com.riffle.feature.reader.highlights.ReaderSource
import com.riffle.feature.reader.ui.ReaderHostActions
import org.koin.core.parameter.parametersOf
import org.koin.mp.KoinPlatform

/**
 * Android implementation of the [AppShell]'s readerHost slot.
 *
 * Dispatches a [LibraryNav.ReaderDestination] to the correct platform-specific screen and wires
 * navigation events (OpenInSourceBook, CloseEmptyHighlights, ShareHighlights, ExportError) back
 * to the [ReaderHostActions] and [PlayerHostActions] callbacks provided by the shell.
 *
 * Each reader screen gets a fresh ViewModel scoped to its composition lifetime via
 * [ScreenScopedViewModelHost]. This ensures proper cleanup when the reader is closed and
 * a fresh instance when it is reopened — matching the per-destination scoping that a
 * NavController NavBackStackEntry would have provided.
 */
@Composable
fun AndroidReaderSlot(
    destination: LibraryNav.ReaderDestination,
    readerActions: ReaderHostActions,
    playerActions: PlayerHostActions,
    windowSizeClass: WindowSizeClass,
) {
    when (destination) {
        is LibraryNav.Reader -> AndroidEpubReaderSlot(
            destination = destination,
            readerActions = readerActions,
            windowSizeClass = windowSizeClass,
        )

        is LibraryNav.PdfReader -> {
            val item = destination.item
            val koin = KoinPlatform.getKoin()
            val host = remember { ScreenScopedViewModelHost() }
            val viewModel: PdfReaderViewModel = remember {
                host.adopt(koin.get { parametersOf(item.id, item.sourceId.ifEmpty { null }) })
            }
            DisposableEffect(Unit) { onDispose { host.clear() } }
            PdfReaderScreen(
                onNavigateBack = readerActions.onBack,
                viewModel = viewModel,
            )
        }

        is LibraryNav.CbzReader -> {
            val item = destination.item
            val koin = KoinPlatform.getKoin()
            val host = remember { ScreenScopedViewModelHost() }
            val viewModel: CbzReaderViewModel = remember {
                host.adopt(koin.get { parametersOf(item.id, item.sourceId.ifEmpty { null }) })
            }
            DisposableEffect(Unit) { onDispose { host.clear() } }
            CbzReaderScreen(
                onNavigateBack = readerActions.onBack,
                viewModel = viewModel,
            )
        }

        is LibraryNav.AudiobookPlayer -> {
            val item = destination.item
            val koin = KoinPlatform.getKoin()
            val host = remember { ScreenScopedViewModelHost() }
            val viewModel: AudiobookPlayerViewModel = remember {
                host.adopt(
                    koin.get {
                        parametersOf(
                            item.id,
                            item.sourceId,
                            destination.playlistId,
                            destination.playlistLibraryId,
                            destination.startAtSec.toFloat(),
                        )
                    },
                )
            }
            DisposableEffect(Unit) { onDispose { host.clear() } }
            AudiobookPlayerScreen(
                windowSizeClass = windowSizeClass,
                onNavigateBack = playerActions.onBack,
                onPlaylistAdvance = playerActions.onPlaylistAdvance,
                onSwitchToReadaloud = playerActions.onSwitchToReadaloud ?: { _, _ -> },
                viewModel = viewModel,
            )
        }
    }
}

@Composable
private fun AndroidEpubReaderSlot(
    destination: LibraryNav.Reader,
    readerActions: ReaderHostActions,
    windowSizeClass: WindowSizeClass,
) {
    val item = destination.item
    val context = LocalContext.current
    val exportErrorMessage = stringResource(R.string.export_pdf_error)

    val koin = KoinPlatform.getKoin()
    val host = remember { ScreenScopedViewModelHost() }
    val viewModel: EpubReaderViewModel = remember {
        host.adopt(
            koin.get {
                parametersOf(
                    item.id,
                    ReaderSource.FullBook,
                    destination.startReadaloudAtSec,
                    destination.openAtRawCfi ?: destination.initialLocatorJson,
                    destination.openAtAnnotationId,
                    destination.startHref,
                    item.sourceId.ifEmpty { null },
                )
            },
        )
    }
    DisposableEffect(Unit) { onDispose { host.clear() } }

    LaunchedEffect(viewModel) {
        viewModel.readerNavEvents.collect { event ->
            when (event) {
                is ReaderNavEvent.OpenInSourceBook -> {
                    readerActions.onOpenInBook?.invoke(
                        buildLocatorJsonFromCfi(event.cfi),
                    )
                }
                ReaderNavEvent.CloseEmptyHighlights -> readerActions.onBack()
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
                    Toast.makeText(context, exportErrorMessage, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    EpubReaderScreen(
        windowSizeClass = windowSizeClass,
        onNavigateBack = readerActions.onBack,
        viewModel = viewModel,
    )
}

private fun buildLocatorJsonFromCfi(cfi: String): String {
    val escaped = cfi.replace("\\", "\\\\").replace("\"", "\\\"")
    return """{"href":"","type":"application/xhtml+xml","locations":{"cfi":"$escaped"}}"""
}
