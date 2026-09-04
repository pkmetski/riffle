package com.riffle.shared.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitViewController
import com.riffle.core.models.LibraryItem
import org.koin.compose.koinInject
import platform.Foundation.NSUserDefaults

/**
 * iOS PDF reader composable. Downloads the PDF if not cached, then embeds PDFKit's
 * PDFView via UIKitViewController and persists the last-read page across sessions.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
actual fun PdfReaderScreen(item: LibraryItem, onBack: () -> Unit) {
    val bridgeFactory = koinInject<IosPdfNavigatorBridgeFactory>()
    val downloader = koinInject<IosPdfDownloader>()

    var localPath by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val bridge = remember { bridgeFactory.create() }

    LaunchedEffect(item.id) {
        val path = downloader.localPath(item)
        if (path == null) {
            loadError = "Could not download PDF"
            return@LaunchedEffect
        }
        localPath = path
        val savedPage = NSUserDefaults.standardUserDefaults
            .integerForKey(pageKey(item))
            .toInt()
        bridge.openPdf(path, savedPage)
    }

    DisposableEffect(item.id) {
        onDispose {
            val page = bridge.currentPage()
            if (page > 0 || bridge.pageCount() > 0) {
                NSUserDefaults.standardUserDefaults.setInteger(
                    page.toLong(),
                    forKey = pageKey(item),
                )
            }
            bridge.disposePdf()
        }
    }

    Box(Modifier.fillMaxSize()) {
        when {
            loadError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText(loadError ?: "Error")
            }
            localPath == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText("Opening PDF…")
            }
            else -> UIKitViewController(
                factory = { bridge.viewController() },
                modifier = Modifier.fillMaxSize(),
                update = {},
            )
        }

        Box(
            modifier = Modifier
                .systemBarsPadding()
                .padding(12.dp)
                .align(Alignment.TopStart),
        ) {
            BasicText(
                text = "← Back",
                modifier = Modifier.clickable(onClick = onBack),
            )
        }
    }
}

private fun pageKey(item: LibraryItem) = "pdf_page_${item.sourceId}_${item.id}"
