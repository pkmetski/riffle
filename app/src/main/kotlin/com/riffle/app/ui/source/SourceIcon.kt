package com.riffle.app.ui.source

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceType

/**
 * Renders the icon for a configured [Source] in the source switcher: fetches the server's
 * favicon via Coil (using the app-scope [coil.ImageLoader] with its disk cache) and falls back
 * to the bundled monogram drawable while loading or on any error / decode failure.
 *
 * Do not call this for [SourceType.LOCAL_FILES]; the caller keeps its existing Material Folder
 * treatment. See [SourceIconResolver.fallbackDrawableFor].
 */
@Composable
fun SourceIcon(
    source: Source,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val fallbackRes = SourceIconResolver.fallbackDrawableFor(source)
    val faviconUrl = SourceIconResolver.faviconUrlFor(source)
    if (faviconUrl == null) {
        SourceIconMonogram(fallbackRes = fallbackRes, modifier = modifier, size = size)
    } else {
        val shape = RoundedCornerShape(8.dp)
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(faviconUrl)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier.size(size).clip(shape),
            loading = { SourceIconMonogram(fallbackRes = fallbackRes, size = size) },
            error = { SourceIconMonogram(fallbackRes = fallbackRes, size = size) },
        )
    }
}

/**
 * Renders the icon for a source picked by type in the "add source" flow, where no [Source] with
 * a base URL exists yet — always the bundled monogram. Not defined for [SourceType.LOCAL_FILES];
 * the caller keeps its existing Material Folder treatment there.
 */
@Composable
fun SourceTypeIcon(
    type: SourceType,
    serverType: ServerType = ServerType.AUDIOBOOKSHELF,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val fallbackRes = SourceIconResolver.fallbackDrawableFor(type, serverType)
    SourceIconMonogram(fallbackRes = fallbackRes, modifier = modifier, size = size)
}

@Composable
private fun SourceIconMonogram(
    fallbackRes: Int,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    // Clip to a slightly-rounded rect so the fallback monogram and any fetched favicon share the
    // same silhouette in the switcher (favicons in the wild are rectangular of varying aspect).
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
    ) {
        Image(
            painter = painterResource(id = fallbackRes),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(size),
        )
    }
}
