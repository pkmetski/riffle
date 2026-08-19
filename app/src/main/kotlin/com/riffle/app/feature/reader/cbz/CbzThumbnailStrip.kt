package com.riffle.app.feature.reader.cbz

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size as CoilSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val MAX_THUMB_DIMENSION = 256

@Composable
internal fun CbzThumbnailStrip(
    currentPage: Int,
    pageCount: Int,
    imageSource: CbzImageSource,
    onSeek: (Int) -> Unit,
    thumbnailCache: LruCache<Int, Bitmap>? = null,
) {
    val cache = thumbnailCache ?: remember(imageSource) { LruCache(50) }
    val listState = rememberLazyListState()

    LaunchedEffect(currentPage) {
        val viewport = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
        val leading = if (viewport > 0) -(viewport / 2) else 0
        listState.animateScrollToItem(currentPage, scrollOffset = leading)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "${currentPage + 1} / $pageCount",
            color = MaterialTheme.colorScheme.onSurface,
        )
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .testTag("cbz_thumbnail_strip"),
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(pageCount) { index ->
                CbzThumbnail(
                    imageSource = imageSource,
                    pageIndex = index,
                    isCurrent = index == currentPage,
                    onClick = { onSeek(index) },
                    thumbnailCache = cache,
                )
            }
        }
    }
}

@Composable
private fun CbzThumbnail(
    imageSource: CbzImageSource,
    pageIndex: Int,
    isCurrent: Boolean,
    onClick: () -> Unit,
    thumbnailCache: LruCache<Int, Bitmap>,
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(
        initialValue = thumbnailCache.get(pageIndex),
        key1 = pageIndex,
        key2 = imageSource,
    ) {
        if (value == null) {
            val decoded = withContext(Dispatchers.IO) {
                runCatching { decodeSampledBitmap(imageSource, pageIndex, MAX_THUMB_DIMENSION) }.getOrNull()
            }
            if (decoded != null) {
                thumbnailCache.put(pageIndex, decoded)
                value = decoded
            }
        }
    }
    val borderColor = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Transparent
    Box(
        modifier = Modifier
            .size(width = 88.dp, height = 120.dp)
            .background(Color(0xFF1A1A1A), RoundedCornerShape(2.dp))
            .border(width = 2.dp, color = borderColor, shape = RoundedCornerShape(2.dp))
            .clickable { onClick() }
            .testTag("cbz_thumb_$pageIndex"),
    ) {
        val currentBitmap = bitmap
        if (currentBitmap != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(currentBitmap)
                    .size(CoilSize(264, 360))
                    .crossfade(false)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
