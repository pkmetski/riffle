package com.riffle.feature.reader.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.riffle.feature.designsystem.TestTags

private val THUMB_WIDTH = 88.dp
private val THUMB_HEIGHT = 120.dp
private val THUMB_BORDER = 2.dp
private val STRIP_PADDING = 4.dp

/**
 * Horizontally scrolling page-thumbnail strip. Each cell calls [thumbnailContent] with a
 * thumbnail-sized modifier, so the platform provides its own image-rendering implementation
 * (Coil/Bitmap on Android, UIImage on iOS) while this composable handles layout and navigation.
 */
@Composable
fun CbzThumbnailStrip(
    currentPage: Int,
    pageCount: Int,
    onSeek: (Int) -> Unit,
    thumbnailContent: @Composable (modifier: Modifier, page: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(currentPage) {
        listState.animateScrollToItem(
            index = currentPage,
            scrollOffset = -THUMB_WIDTH.value.toInt() * 2,
        )
    }

    LazyRow(
        state = listState,
        modifier = modifier
            .testTag(TestTags.CBZ_THUMBNAIL_STRIP)
            .padding(vertical = STRIP_PADDING),
    ) {
        items(pageCount) { page ->
            val isSelected = page == currentPage
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .width(THUMB_WIDTH)
                    .height(THUMB_HEIGHT)
                    .then(
                        if (isSelected) {
                            Modifier.border(THUMB_BORDER, MaterialTheme.colorScheme.primary)
                        } else {
                            Modifier
                        },
                    )
                    .testTag(TestTags.cbzThumb(page))
                    .clickable { onSeek(page) },
            ) {
                thumbnailContent(
                    Modifier.matchParentSize(),
                    page,
                )
            }
        }
    }
}
