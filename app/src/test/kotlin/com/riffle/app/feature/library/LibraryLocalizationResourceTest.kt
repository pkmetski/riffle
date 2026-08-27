package com.riffle.app.feature.library

import com.riffle.app.R
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryLocalizationResourceTest {

    @Test
    fun `sort modes use localized string resources`() {
        assertEquals(
            mapOf(
                LibrarySortMode.ADDED_DESC to R.string.ui_sort_recently_added,
                LibrarySortMode.ADDED_ASC to R.string.ui_sort_oldest_first,
                LibrarySortMode.TITLE_ASC to R.string.ui_sort_title_az,
                LibrarySortMode.TITLE_DESC to R.string.ui_sort_title_za,
                LibrarySortMode.AUTHOR_ASC to R.string.ui_sort_author_az,
                LibrarySortMode.RECENTLY_OPENED to R.string.ui_sort_recently_opened,
            ),
            LibrarySortMode.entries.associateWith { it.labelResId },
        )
    }

    @Test
    fun `home section types use localized string resources`() {
        assertEquals(
            mapOf(
                LibrarySectionType.IN_PROGRESS to R.string.ui_section_in_progress,
                LibrarySectionType.FINISHED to R.string.ui_section_completed,
                LibrarySectionType.RECENTLY_ADDED to R.string.ui_section_recently_added,
                LibrarySectionType.CONTINUE_SERIES to R.string.ui_section_continue_series,
            ),
            LibrarySectionType.entries.associateWith { it.titleResId },
        )
    }
}
