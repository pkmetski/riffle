package com.riffle.app.feature.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.riffle.app.R
import com.riffle.feature.reader.ui.ChapterMapProgressLabelTemplates

/**
 * Android's string catalogue for the shared chapter-map overlay.
 *
 * The overlay itself lives in `:feature:reader-ui` so iOS renders the identical thing; only the
 * strings are host-owned, which is what keeps Android's `values-bg` / `values-es` translations
 * working without migrating them to `composeResources`. The templates are handed over unexpanded
 * (`stringResource` with no format args) because the shared `formatTemplate` does the expansion —
 * `String.format` is JVM-only and cannot follow the overlay into commonMain.
 */
@Composable
internal fun chapterMapProgressLabelTemplates() = ChapterMapProgressLabelTemplates(
    chapterCount = stringResource(R.string.ui_chapter_progress_count),
    durationMinutes = stringResource(R.string.ui_reader_duration_minutes),
    durationHoursMinutes = stringResource(R.string.ui_reader_duration_hours_minutes),
    chapterRemainingExact = stringResource(R.string.ui_chapter_time_remaining_exact),
    chapterRemainingEstimated = stringResource(R.string.ui_chapter_time_remaining_estimated),
    chapterRemainingLessThanMinute = stringResource(R.string.ui_chapter_time_remaining_less_than_minute),
    bookRemainingExact = stringResource(R.string.ui_book_time_remaining_exact),
    bookRemainingEstimated = stringResource(R.string.ui_book_time_remaining_estimated),
    bookRemainingLessThanMinute = stringResource(R.string.ui_book_time_remaining_less_than_minute),
    readingProgressValue = stringResource(R.string.ui_reading_progress_value),
    currentChapterValue = stringResource(R.string.ui_current_chapter_value),
    totalProgressValue = stringResource(R.string.ui_total_progress_value),
    activeRailSegmentProgress = stringResource(R.string.ui_active_rail_segment_progress),
)
