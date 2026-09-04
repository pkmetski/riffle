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
 * iOS EPUB reader composable. Downloads the EPUB if not cached, then embeds the Readium Swift
 * navigator via UIKitViewController and persists the reading position across sessions.
 */
@Composable
actual fun EpubReaderScreen(item: LibraryItem, onBack: () -> Unit) {
    val bridgeFactory = koinInject<IosEpubNavigatorBridgeFactory>()
    val downloader = koinInject<IosEpubDownloader>()

    var localPath by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val bridge = remember { bridgeFactory.create() }
    val navigator = remember(bridge) { ReadiumSwiftNavigator(bridge) }

    LaunchedEffect(item.id) {
        val path = downloader.localPath(item)
        if (path == null) {
            loadError = "Could not download book"
            return@LaunchedEffect
        }
        localPath = path
        val savedLocator = NSUserDefaults.standardUserDefaults
            .stringForKey(locatorKey(item))
        navigator.open(path, savedLocator)
    }

    DisposableEffect(item.id) {
        onDispose {
            navigator.snapshotPosition()?.locatorJson?.let { json ->
                NSUserDefaults.standardUserDefaults.setObject(json, forKey = locatorKey(item))
            }
            navigator.close()
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
    }
}

private fun locatorKey(item: LibraryItem) = "epub_locator_${item.sourceId}_${item.id}"
