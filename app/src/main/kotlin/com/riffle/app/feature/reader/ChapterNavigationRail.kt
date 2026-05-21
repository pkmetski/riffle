package com.riffle.app.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun ChapterNavigationRail(
    segments: List<RailSegment>,
    activeIndex: Int,
    cursorPosition: Float,
    onSegmentClick: (RailSegment) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (segments.isEmpty()) return

    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
    val cursorColor = MaterialTheme.colorScheme.onBackground

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .testTag("chapter_navigation_rail")
            .drawWithContent {
                drawContent()
                val x = cursorPosition.coerceIn(0f, 1f) * size.width
                drawLine(
                    color = cursorColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 2.dp.toPx(),
                )
            },
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            segments.forEachIndexed { index, segment ->
                val isActive = index == activeIndex
                val segmentModifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 0.5.dp)
                    .background(if (isActive) activeColor else inactiveColor)
                    .clickable { onSegmentClick(segment) }
                    .then(
                        if (isActive) Modifier.semantics {
                            contentDescription = "Active rail segment: ${segment.title}"
                        } else Modifier
                    )
                Box(modifier = segmentModifier)
            }
        }
    }
}
