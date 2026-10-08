@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.feature.reader

import android.annotation.SuppressLint
import android.util.Base64
import android.webkit.WebView
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url

/**
 * Android-specific image content for [com.riffle.feature.reader.ui.FigureZoomOverlay].
 *
 * Loads image bytes off the main thread — from a `data:` URI via Base64 decode, or from the
 * publication resource via [Publication.get] — then renders the decoded [android.graphics.Bitmap].
 * Shows a [CircularProgressIndicator] while bytes are loading.
 *
 * @param href The `data:` URI or EPUB-package-relative href from the tap event.
 * @param publication The open Readium publication; null for data URIs (they decode inline).
 * @param modifier The image modifier from [com.riffle.feature.reader.ui.FigureZoomOverlay] with
 *   the current pan/zoom graphicsLayer applied. Apply this to the rendered image, not the spinner.
 */
@Composable
internal fun AndroidFigureImage(
    href: String,
    publication: Publication?,
    modifier: Modifier,
) {
    // Cap the decode at 2048 px. That's the largest size a user can meaningfully resolve on a
    // phone/tablet at 5× pinch, and it prevents a 12-megapixel figure from allocating ~48 MB.
    val decodeCapPx = 2048
    var bitmap by remember(href) { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(href) {
        val decoded = withContext(Dispatchers.IO) {
            val bytes = loadImageBytes(href, publication) ?: return@withContext null
            decodeSampledBitmap(bytes, decodeCapPx, decodeCapPx)
        }
        bitmap = decoded
    }
    DisposableEffect(href) {
        onDispose {
            val b = bitmap
            bitmap = null
            if (b != null && !b.isRecycled) b.recycle()
        }
    }

    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
        )
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

/**
 * Android-specific SVG content for [com.riffle.feature.reader.ui.FigureZoomOverlay].
 * Renders inline SVG markup in a barebones [WebView] via [AndroidView].
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun AndroidSvgWebView(svgMarkup: String, modifier: Modifier) {
    val html = remember(svgMarkup) {
        """<!doctype html><html><head><meta name="viewport" content="width=device-width">
           <style>html,body{margin:0;padding:0;background:transparent}svg{width:100%;height:100%;display:block}</style>
           </head><body>$svgMarkup</body></html>""".trimIndent()
    }
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(0)
                settings.javaScriptEnabled = false
                settings.useWideViewPort = false
                settings.loadWithOverviewMode = true
                loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        update = {},
        modifier = modifier,
    )
}

/**
 * Load image bytes for the given [href].
 *  - `data:` URI → Base64-decoded payload.
 *  - Readium `readium_package` virtual-host URL → strip prefix and query the [Publication].
 *  - Other hrefs → query [Publication] directly.
 */
private suspend fun loadImageBytes(href: String, publication: Publication?): ByteArray? {
    if (href.startsWith("data:")) {
        val comma = href.indexOf(',')
        if (comma < 0) return null
        val meta = href.substring(0, comma)
        val payload = href.substring(comma + 1)
        // Only base64 payloads decode cleanly to bitmap bytes. URL-encoded data URIs are text
        // (typically inline SVG) and would produce corrupt bytes BitmapFactory can't decode.
        if (!meta.contains(";base64")) return null
        return runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull()
    }
    val pub = publication ?: return null
    val stripped = href
        .removePrefix("http://readium_package/")
        .removePrefix("https://readium_package/")
        .substringBefore('#')
    val url = Url(stripped) ?: return null
    return pub.get(url)?.read()?.getOrNull()
}
