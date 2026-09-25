package com.riffle.feature.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.riffle.core.common.TimeRemaining
import com.riffle.core.domain.ReaderTheme
import com.riffle.feature.designsystem.RiffleTheme
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.reader.RailSegment

/**
 * The reader's on-screen-info overlay: the reading-progress labels and the chapter rail.
 *
 * One implementation for both hosts. Android mounts it from `EpubReaderScreen` /
 * `PdfReaderScreen` / `CbzReaderScreen`; iOS mounts it from `IosEpubReaderScreen`. The five
 * `FormattingPreferences` flags that gate it — `showChapterMap`, `coloredChapterMap`,
 * `showCurrentChapterLabel`, `showReadingProgressLabels`, `showReadingTimeEstimate` — are already
 * shared, so both platforms render the same thing from the same switch.
 */
@Composable
fun ChapterMapOverlay(
    segments: List<RailSegment>,
    activeIndex: Int,
    cursorPosition: Float,
    totalProgress: Float,
    readerTheme: ReaderTheme,
    showRail: Boolean,
    coloredChapterMap: Boolean,
    showCurrentChapterLabel: Boolean,
    showProgressLabels: Boolean,
    showReadingTimeEstimate: Boolean,
    templates: ChapterMapProgressLabelTemplates,
    chapterTimeRemaining: TimeRemaining? = null,
    bookTimeRemaining: TimeRemaining? = null,
    bookmarkPositions: List<Float> = emptyList(),
    onSegmentClick: (RailSegment) -> Unit,
    modifier: Modifier = Modifier,
) {
    val darkTheme = readerTheme == ReaderTheme.Dark || readerTheme == ReaderTheme.DarkDim
    RiffleTheme(darkTheme = darkTheme) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(readerTheme.readerPalette.background),
        ) {
            if (showProgressLabels || showCurrentChapterLabel || showReadingTimeEstimate) {
                ReadingProgressLabels(
                    activeChapterIndex = activeIndex,
                    chapterCount = segments.size,
                    activeChapterTitle = segments.getOrNull(activeIndex)?.title.orEmpty(),
                    totalProgress = totalProgress,
                    readerTheme = readerTheme,
                    showCountAndPercent = showProgressLabels,
                    showChapterName = showCurrentChapterLabel,
                    showReadingTimeEstimate = showReadingTimeEstimate,
                    templates = templates,
                    chapterTimeRemaining = chapterTimeRemaining,
                    bookTimeRemaining = bookTimeRemaining,
                )
            }
            if (showRail) {
                ChapterNavigationRail(
                    segments = segments,
                    activeIndex = activeIndex,
                    cursorPosition = cursorPosition,
                    readerTheme = readerTheme,
                    railContentDescription = formatTemplate(
                        templates.activeRailSegmentProgress,
                        segments.getOrNull(activeIndex)?.title.orEmpty(),
                        chapterRailProgressPercent(cursorPosition),
                    ),
                    onSegmentClick = onSegmentClick,
                    coloredChapterMap = coloredChapterMap,
                    bookmarkPositions = bookmarkPositions,
                )
            }
        }
    }
}

@Composable
fun ReadingProgressLabels(
    activeChapterIndex: Int,
    chapterCount: Int,
    activeChapterTitle: String,
    totalProgress: Float,
    readerTheme: ReaderTheme,
    showCountAndPercent: Boolean,
    showChapterName: Boolean,
    templates: ChapterMapProgressLabelTemplates,
    showReadingTimeEstimate: Boolean = false,
    chapterTimeRemaining: TimeRemaining? = null,
    bookTimeRemaining: TimeRemaining? = null,
) {
    val chapterCountText = if (chapterCount > 0) {
        formatChapterCount(activeChapterIndex, chapterCount, templates)
    } else {
        ""
    }
    val pctText = formatTotalProgressPercent(totalProgress)
    val textColor = readerThemeLabelColor(readerTheme)
    val isExact = chapterTimeRemaining is TimeRemaining.Exact &&
        bookTimeRemaining is TimeRemaining.Exact
    val timeColor = if (isExact) MaterialTheme.colorScheme.tertiary else textColor
    val chapterTimeText = chapterTimeRemaining?.let { formatChapterRemaining(it, templates) }
    val bookTimeText = bookTimeRemaining?.let { formatBookRemaining(it, templates) }
    val showLeftColumn = showCountAndPercent || (showReadingTimeEstimate && chapterTimeText != null)
    val showRightColumn = showCountAndPercent || (showReadingTimeEstimate && bookTimeText != null)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp)
            .testTag(TestTags.READING_PROGRESS_LABELS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showLeftColumn) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .testTag(TestTags.READING_PROGRESS_CHAPTER),
            ) {
                if (showCountAndPercent) {
                    val readingProgressContentDescription =
                        formatTemplate(templates.readingProgressValue, chapterCountText)
                    Text(
                        text = chapterCountText,
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor,
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        modifier = Modifier.semantics {
                            contentDescription = readingProgressContentDescription
                        },
                    )
                }
                if (showReadingTimeEstimate && chapterTimeText != null) {
                    Text(
                        text = chapterTimeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = timeColor,
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        modifier = Modifier.testTag(TestTags.READING_PROGRESS_CHAPTER_TIME),
                    )
                }
            }
        }
        if (showChapterName) {
            val currentChapterContentDescription =
                formatTemplate(templates.currentChapterValue, activeChapterTitle)
            Text(
                text = activeChapterTitle,
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                textAlign = TextAlign.Center,
                fontStyle = FontStyle.Italic,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(2f)
                    .testTag(TestTags.READING_PROGRESS_CHAPTER_NAME)
                    .semantics { contentDescription = currentChapterContentDescription },
            )
        }
        if (showRightColumn) {
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier
                    .weight(1f)
                    .testTag(TestTags.READING_PROGRESS_PERCENT),
            ) {
                if (showCountAndPercent) {
                    val totalProgressContentDescription =
                        formatTemplate(templates.totalProgressValue, pctText)
                    Text(
                        text = pctText,
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        modifier = Modifier.semantics {
                            contentDescription = totalProgressContentDescription
                        },
                    )
                }
                if (showReadingTimeEstimate && bookTimeText != null) {
                    Text(
                        text = bookTimeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = timeColor,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        modifier = Modifier.testTag(TestTags.READING_PROGRESS_BOOK_TIME),
                    )
                }
            }
        }
    }
}
