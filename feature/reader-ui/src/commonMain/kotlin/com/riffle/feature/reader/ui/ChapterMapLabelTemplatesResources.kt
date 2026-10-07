package com.riffle.feature.reader.ui

import androidx.compose.runtime.Composable
import com.riffle.feature.reader.ui.generated.resources.Res
import com.riffle.feature.reader.ui.generated.resources.ui_active_rail_segment_progress
import com.riffle.feature.reader.ui.generated.resources.ui_book_time_remaining_estimated
import com.riffle.feature.reader.ui.generated.resources.ui_book_time_remaining_exact
import com.riffle.feature.reader.ui.generated.resources.ui_book_time_remaining_less_than_minute
import com.riffle.feature.reader.ui.generated.resources.ui_chapter_progress_count
import com.riffle.feature.reader.ui.generated.resources.ui_chapter_time_remaining_estimated
import com.riffle.feature.reader.ui.generated.resources.ui_chapter_time_remaining_exact
import com.riffle.feature.reader.ui.generated.resources.ui_chapter_time_remaining_less_than_minute
import com.riffle.feature.reader.ui.generated.resources.ui_current_chapter_value
import com.riffle.feature.reader.ui.generated.resources.ui_reader_duration_hours_minutes
import com.riffle.feature.reader.ui.generated.resources.ui_reader_duration_minutes
import com.riffle.feature.reader.ui.generated.resources.ui_reading_progress_value
import com.riffle.feature.reader.ui.generated.resources.ui_total_progress_value
import org.jetbrains.compose.resources.stringResource

/** Builds [ChapterMapProgressLabelTemplates] from composeResources, picking up the active locale. */
@Composable
fun chapterMapProgressLabelTemplates(): ChapterMapProgressLabelTemplates =
    ChapterMapProgressLabelTemplates(
        chapterCount = stringResource(Res.string.ui_chapter_progress_count),
        durationMinutes = stringResource(Res.string.ui_reader_duration_minutes),
        durationHoursMinutes = stringResource(Res.string.ui_reader_duration_hours_minutes),
        chapterRemainingExact = stringResource(Res.string.ui_chapter_time_remaining_exact),
        chapterRemainingEstimated = stringResource(Res.string.ui_chapter_time_remaining_estimated),
        chapterRemainingLessThanMinute = stringResource(Res.string.ui_chapter_time_remaining_less_than_minute),
        bookRemainingExact = stringResource(Res.string.ui_book_time_remaining_exact),
        bookRemainingEstimated = stringResource(Res.string.ui_book_time_remaining_estimated),
        bookRemainingLessThanMinute = stringResource(Res.string.ui_book_time_remaining_less_than_minute),
        readingProgressValue = stringResource(Res.string.ui_reading_progress_value),
        currentChapterValue = stringResource(Res.string.ui_current_chapter_value),
        totalProgressValue = stringResource(Res.string.ui_total_progress_value),
        activeRailSegmentProgress = stringResource(Res.string.ui_active_rail_segment_progress),
    )
