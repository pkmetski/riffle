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
    fun `home section types have expected title strings`() {
        // LibrarySectionType.titleResId was removed when the type moved to feature:library commonMain
        // (Android-specific @StringRes cannot be used in KMP code). Title strings are now plain
        // English; localization support will be added via composeResources in a follow-up.
        assertEquals("In Progress", LibrarySectionType.IN_PROGRESS.title)
        assertEquals("Completed", LibrarySectionType.FINISHED.title)
        assertEquals("Recently Added", LibrarySectionType.RECENTLY_ADDED.title)
        assertEquals("Continue Series", LibrarySectionType.CONTINUE_SERIES.title)
    }
}
