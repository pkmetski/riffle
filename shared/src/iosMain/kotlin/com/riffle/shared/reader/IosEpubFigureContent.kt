package com.riffle.shared.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRect
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIColor
import platform.UIKit.UIImage
import platform.UIKit.UIImageView
import platform.UIKit.UIViewContentMode
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * iOS-specific image content for [com.riffle.feature.reader.ui.FigureZoomOverlay].
 *
 * Loads image bytes off the main thread — from a `data:` URI via Base64 decode, or from the
 * Readium navigator via [ReadiumSwiftNavigator.readResourceBytes] — then renders as a [UIImageView].
 * Shows a [CircularProgressIndicator] while bytes are loading.
 *
 * @param href The `data:` URI or EPUB-package-relative href from the tap event.
 * @param navigator The iOS navigator for fetching EPUB resources.
 * @param modifier The image modifier with the current pan/zoom graphicsLayer applied.
 */
@OptIn(ExperimentalEncodingApi::class)
@Composable
internal fun IosEpubFigureImage(
    href: String,
    navigator: ReadiumSwiftNavigator,
    modifier: Modifier,
) {
    var imageBytes by remember(href) { mutableStateOf<ByteArray?>(null) }

    LaunchedEffect(href) {
        imageBytes = withContext(Dispatchers.IO) {
            if (href.startsWith("data:")) {
                val commaIdx = href.indexOf(',')
                val meta = if (commaIdx >= 0) href.substring(0, commaIdx) else return@withContext null
                if (!meta.contains(";base64")) return@withContext null
                runCatching { Base64.decode(href.substring(commaIdx + 1)) }.getOrNull()
            } else {
                navigator.readResourceBytes(href)
            }
        }
    }
    DisposableEffect(href) {
        onDispose { imageBytes = null }
    }

    val bytes = imageBytes
    if (bytes != null) {
        ImageBytesView(bytes = bytes, modifier = modifier)
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

/**
 * iOS-specific SVG content for [com.riffle.feature.reader.ui.FigureZoomOverlay].
 * Renders inline SVG markup in a [WKWebView] via [UIKitView].
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
internal fun IosEpubSvgView(svgMarkup: String, modifier: Modifier) {
    val html = remember(svgMarkup) {
        """<!doctype html><html><head><meta name="viewport" content="width=device-width">
           <style>html,body{margin:0;padding:0;background:transparent}svg{width:100%;height:100%;display:block}</style>
           </head><body>$svgMarkup</body></html>""".trimIndent()
    }
    UIKitView(
        factory = {
            WKWebView(
                frame = kotlinx.cinterop.cValue<CGRect>(),
                configuration = WKWebViewConfiguration(),
            ).apply {
                opaque = false
                backgroundColor = UIColor.clearColor
                scrollView.backgroundColor = UIColor.clearColor
                scrollView.scrollEnabled = false
                loadHTMLString(html, baseURL = null)
            }
        },
        update = {},
        modifier = modifier,
    )
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@Composable
private fun ImageBytesView(bytes: ByteArray, modifier: Modifier) {
    UIKitView(
        factory = {
            UIImageView().apply {
                contentMode = UIViewContentMode.UIViewContentModeScaleAspectFit
                backgroundColor = UIColor.clearColor
            }
        },
        update = { view ->
            bytes.usePinned { pinned ->
                val nsData = NSData.create(
                    bytes = pinned.addressOf(0),
                    length = bytes.size.toULong(),
                )
                view.image = UIImage.imageWithData(nsData)
            }
        },
        modifier = modifier,
    )
}
