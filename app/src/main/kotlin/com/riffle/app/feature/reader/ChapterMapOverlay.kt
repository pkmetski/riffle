package com.riffle.app.feature.reader

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
import androidx.compose.ui.graphics.Color
import com.riffle.app.feature.readersettings.palette
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.riffle.app.R
import com.riffle.app.ui.theme.RiffleTheme
import com.riffle.core.common.TimeRemaining
import com.riffle.core.domain.ReaderTheme
import java.util.Locale

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
                .background(readerTheme.palette.background),
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
                    onSegmentClick = onSegmentClick,
                    coloredChapterMap = coloredChapterMap,
                    bookmarkPositions = bookmarkPositions,
                )
            }
        }
    }
}

// Reader-theme-paired label colour: page foreground at reduced alpha so the labels read
// as continuation of the page, not chrome — and don't compete with actual body text.
// Per-theme alpha because the same alpha across themes reads as different "loudness" depending
// on the foreground/background contrast.
internal fun readerThemeLabelColor(theme: ReaderTheme): Color {
    val alpha = when (theme) {
        ReaderTheme.Light -> 0.65f
        ReaderTheme.Dark -> 0.65f
        ReaderTheme.DarkDim -> 0.85f
        ReaderTheme.Sepia -> 0.70f
        // Auto resolves upstream; treat as Light if it slips through.
        ReaderTheme.Auto -> 0.65f
    }
    return theme.palette.foreground.copy(alpha = alpha)
}

internal data class ChapterMapProgressLabelTemplates(
    val chapterCount: String,
    val durationMinutes: String,
    val durationHoursMinutes: String,
    val chapterRemainingExact: String,
    val chapterRemainingEstimated: String,
    val chapterRemainingLessThanMinute: String,
    val bookRemainingExact: String,
    val bookRemainingEstimated: String,
    val bookRemainingLessThanMinute: String,
)

@Composable
private fun chapterMapProgressLabelTemplates() = ChapterMapProgressLabelTemplates(
    chapterCount = stringResource(R.string.ui_chapter_progress_count),
    durationMinutes = stringResource(R.string.ui_reader_duration_minutes),
    durationHoursMinutes = stringResource(R.string.ui_reader_duration_hours_minutes),
    chapterRemainingExact = stringResource(R.string.ui_chapter_time_remaining_exact),
    chapterRemainingEstimated = stringResource(R.string.ui_chapter_time_remaining_estimated),
    chapterRemainingLessThanMinute = stringResource(R.string.ui_chapter_time_remaining_less_than_minute),
    bookRemainingExact = stringResource(R.string.ui_book_time_remaining_exact),
    bookRemainingEstimated = stringResource(R.string.ui_book_time_remaining_estimated),
    bookRemainingLessThanMinute = stringResource(R.string.ui_book_time_remaining_less_than_minute),
)

private fun String.withArgs(vararg args: Any): String =
    String.format(Locale.getDefault(), this, *args)

internal fun formatChapterCount(
    activeChapterIndex: Int,
    chapterCount: Int,
    templates: ChapterMapProgressLabelTemplates,
): String =
    templates.chapterCount.withArgs((activeChapterIndex + 1).coerceAtMost(chapterCount), chapterCount)

internal fun formatDuration(sec: Long, templates: ChapterMapProgressLabelTemplates): String {
    val hours = sec / 3600
    val minutes = (sec % 3600) / 60
    return when {
        hours > 0 -> templates.durationHoursMinutes.withArgs(hours, minutes)
        else -> templates.durationMinutes.withArgs(minutes)
    }
}

internal fun formatChapterRemaining(
    remaining: TimeRemaining,
    templates: ChapterMapProgressLabelTemplates,
): String = when (remaining) {
    is TimeRemaining.Exact -> {
        val sec = remaining.sec
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        val duration = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        templates.chapterRemainingExact.withArgs(duration)
    }
    is TimeRemaining.Estimated -> when {
        remaining.sec < 60 -> templates.chapterRemainingLessThanMinute
        else -> templates.chapterRemainingEstimated.withArgs(formatDuration(remaining.sec, templates))
    }
}

internal fun formatBookRemaining(
    remaining: TimeRemaining,
    templates: ChapterMapProgressLabelTemplates,
): String = when (remaining) {
    is TimeRemaining.Exact -> {
        val sec = remaining.sec
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        val duration = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        templates.bookRemainingExact.withArgs(duration)
    }
    is TimeRemaining.Estimated -> when {
        remaining.sec < 60 -> templates.bookRemainingLessThanMinute
        else -> templates.bookRemainingEstimated.withArgs(formatDuration(remaining.sec, templates))
    }
}

@Composable
internal fun ReadingProgressLabels(
    activeChapterIndex: Int,
    chapterCount: Int,
    activeChapterTitle: String,
    totalProgress: Float,
    readerTheme: ReaderTheme,
    showCountAndPercent: Boolean,
    showChapterName: Boolean,
    showReadingTimeEstimate: Boolean = false,
    chapterTimeRemaining: TimeRemaining? = null,
    bookTimeRemaining: TimeRemaining? = null,
) {
    val labelTemplates = chapterMapProgressLabelTemplates()
    val chapterCountText = if (chapterCount > 0) {
        formatChapterCount(activeChapterIndex, chapterCount, labelTemplates)
    } else {
        ""
    }
    val pctText = "%.1f%%".format(totalProgress.coerceIn(0f, 1f) * 100f)
    val textColor = readerThemeLabelColor(readerTheme)
    val isExact = chapterTimeRemaining is TimeRemaining.Exact &&
        bookTimeRemaining is TimeRemaining.Exact
    val timeColor = if (isExact) MaterialTheme.colorScheme.tertiary else textColor
    val chapterTimeText = chapterTimeRemaining?.let { formatChapterRemaining(it, labelTemplates) }
    val bookTimeText = bookTimeRemaining?.let { formatBookRemaining(it, labelTemplates) }
    val showLeftColumn = showCountAndPercent || (showReadingTimeEstimate && chapterTimeText != null)
    val showRightColumn = showCountAndPercent || (showReadingTimeEstimate && bookTimeText != null)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp)
            .testTag("reading_progress_labels"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showLeftColumn) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .testTag("reading_progress_chapter"),
            ) {
                if (showCountAndPercent) {
                    val readingProgressContentDescription =
                        stringResource(R.string.ui_reading_progress_value, chapterCountText)
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
                        modifier = Modifier.testTag("reading_progress_chapter_time"),
                    )
                }
            }
        }
        if (showChapterName) {
            val currentChapterContentDescription =
                stringResource(R.string.ui_current_chapter_value, activeChapterTitle)
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
                    .testTag("reading_progress_chapter_name")
                    .semantics { contentDescription = currentChapterContentDescription },
            )
        }
        if (showRightColumn) {
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier
                    .weight(1f)
                    .testTag("reading_progress_percent"),
            ) {
                if (showCountAndPercent) {
                    val totalProgressContentDescription =
                        stringResource(R.string.ui_total_progress_value, pctText)
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
                        modifier = Modifier.testTag("reading_progress_book_time"),
                    )
                }
            }
        }
    }
}
