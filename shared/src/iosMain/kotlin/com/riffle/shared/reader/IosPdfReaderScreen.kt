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
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.SessionPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * iOS PDF reader composable. Downloads the PDF if not cached, then embeds PDFKit's
 * PDFView via UIKitViewController. Persists the last-read page via [ReadingPositionStore]
 * and syncs progress to the server via [ReadingSessionRepository].
 */
@Suppress("ktlint:standard:function-naming")
@Composable
actual fun PdfReaderScreen(item: LibraryItem, onBack: () -> Unit) {
    KeepReaderScreenOn()
    val bridgeFactory = koinInject<IosPdfNavigatorBridgeFactory>()
    val downloader = koinInject<IosPdfDownloader>()
    val positionStore = koinInject<ReadingPositionStore>()
    val sessionRepository = koinInject<ReadingSessionRepository>()
    var localPath by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val bridge = remember { bridgeFactory.create() }
    var lastTrackedPage by remember { mutableStateOf(0) }

    LaunchedEffect(item.id) {
        val path = downloader.localPath(item)
        if (path == null) {
            loadError = "Could not download PDF"
            return@LaunchedEffect
        }
        localPath = path
        val savedLocator = positionStore.load(item.sourceId, item.id)
        val savedPage = savedLocator?.let { decodePdfPage(it) } ?: 0
        lastTrackedPage = savedPage
        bridge.setPageChangeCallback(object : IosPdfPageChangeCallback {
            override fun onPageChanged(page: Int) {
                lastTrackedPage = page
            }
        })
        bridge.openPdf(path, savedPage)
    }

    DisposableEffect(item.id) {
        onDispose {
            bridge.setPageChangeCallback(null)
            val page = lastTrackedPage.takeIf { it > 0 } ?: bridge.currentPage()
            val pageCount = bridge.pageCount()
            if (page > 0 || pageCount > 0) {
                CoroutineScope(SupervisorJob()).launch {
                    runCatching {
                        val locatorJson = encodePdfLocator(page, pageCount)
                        val progress = if (pageCount > 0) page.toFloat() / pageCount else 0f
                        positionStore.save(item.sourceId, item.id, locatorJson)
                        val payload = SessionPayload(
                            ebookLocation = locatorJson,
                            ebookProgress = progress,
                        )
                        sessionRepository.runSyncCycle(item.id, payload)
                    }
                }
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

/** JSON locator compatible with the position store: {"href":"/page-N","locations":{"position":N,"progression":P}} */
private fun encodePdfLocator(page: Int, pageCount: Int): String {
    val progression = if (pageCount > 0) page.toDouble() / pageCount else 0.0
    return """{"href":"/page-$page","type":"application/pdf","locations":{"position":$page,"progression":$progression}}"""
}

private fun decodePdfPage(locatorJson: String): Int? {
    val positionMatch = Regex(""""position"\s*:\s*(\d+)""").find(locatorJson)
    return positionMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
}
