package com.riffle.app.feature.library

import com.riffle.app.R
import com.riffle.feature.library.LibrarySectionType
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
        // LibrarySectionType.titleResId (@StringRes) was removed when the type moved to
        // feature:library commonMain — Android @StringRes cannot be used in KMP code.
        // Titles are now plain English strings; composeResources localization is a follow-up.
        assertEquals("In Progress", LibrarySectionType.IN_PROGRESS.title)
        assertEquals("Completed", LibrarySectionType.FINISHED.title)
        assertEquals("Recently Added", LibrarySectionType.RECENTLY_ADDED.title)
        assertEquals("Continue Series", LibrarySectionType.CONTINUE_SERIES.title)
    }
}
