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
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.LazyPublicationCapability
import com.riffle.core.catalog.LazyPublicationShape
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.models.LibraryItem
import org.koin.compose.koinInject
import platform.Foundation.NSUserDefaults

/**
 * iOS EPUB reader composable. For O'Reilly lazy publications, opens directly via
 * [IosLazyChapterFetcherImpl] without downloading the full EPUB. For all other sources,
 * downloads the EPUB and uses the file-based open path. Persists the reading position across
 * sessions via [NSUserDefaults].
 */
@Suppress("ktlint:standard:function-naming")
@Composable
actual fun EpubReaderScreen(item: LibraryItem, onBack: () -> Unit) {
    val bridgeFactory = koinInject<IosEpubNavigatorBridgeFactory>()
    val downloader = koinInject<IosEpubDownloader>()
    val annotationStore = koinInject<AnnotationStore>()
    val catalogRegistry = koinInject<CatalogRegistry>()

    var localPath by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var isLazyPublication by remember { mutableStateOf(false) }
    val bridge = remember { bridgeFactory.create() }
    val navigator = remember(bridge) { ReadiumSwiftNavigator(bridge) }
    val coordinator = remember(navigator) {
        AnnotationDecorationCoordinator(
            sourceId = item.sourceId,
            itemId = item.id,
            annotationStore = annotationStore,
            navigator = navigator,
        )
    }
    // Retained so DisposableEffect can cancel in-flight fetches on close.
    var lazyFetcher by remember { mutableStateOf<IosLazyChapterFetcher?>(null) }
    // Cached publication shape for prefetch index lookups — avoids re-fetching on every position.
    var lazyShape by remember { mutableStateOf<LazyPublicationShape?>(null) }

    LaunchedEffect(item.id) {
        val savedLocator = NSUserDefaults.standardUserDefaults.stringForKey(locatorKey(item))

        val cap = catalogRegistry.forSourceId(item.sourceId) as? LazyPublicationCapability
        if (cap != null) {
            val shape = cap.lazyPublication(item.id)
            if (shape != null) {
                isLazyPublication = true
                lazyShape = shape
                val fetcher = IosLazyChapterFetcherImpl(cap, shape, item.id)
                lazyFetcher = fetcher
                val shapeJson = IosLazyChapterFetcherImpl.serializeShape(shape)
                navigator.openLazy(shapeJson, savedLocator, fetcher)
                coordinator.start()
                localPath = "lazy" // sentinel: triggers the reader UI without a real file path
                return@LaunchedEffect
            }
        }

        // Normal file-based path.
        val path = downloader.localPath(item)
        if (path == null) {
            loadError = "Could not download book"
            return@LaunchedEffect
        }
        localPath = path
        navigator.open(path, savedLocator)
        coordinator.start()
    }

    // Prefetch the next chapter whenever position changes in a lazy publication.
    LaunchedEffect(item.id) {
        navigator.positionFlow.collect { position ->
            val fetcher = lazyFetcher ?: return@collect
            val shape = lazyShape ?: return@collect
            val currentIndex = shape.spine.indexOfFirst { it.fullPath == position.href }
            if (currentIndex >= 0) {
                fetcher.prefetchNext(currentIndex)
            }
        }
    }

    DisposableEffect(item.id) {
        onDispose {
            coordinator.stop()
            navigator.snapshotPosition()?.locatorJson?.let { json ->
                NSUserDefaults.standardUserDefaults.setObject(json, forKey = locatorKey(item))
            }
            navigator.close()
            lazyFetcher?.dispose()
            lazyFetcher = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        when {
            loadError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText(loadError ?: "Error")
            }
            localPath == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText("Opening book…")
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

        if (isLazyPublication && localPath != null) {
            Box(
                modifier = Modifier
                    .systemBarsPadding()
                    .padding(12.dp)
                    .align(Alignment.TopEnd),
            ) {
                BasicText("Download to search")
            }
        }
    }
}

private fun locatorKey(item: LibraryItem) = "epub_locator_${item.sourceId}_${item.id}"
