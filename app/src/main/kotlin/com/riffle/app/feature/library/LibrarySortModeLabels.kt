package com.riffle.app.feature.library

import androidx.annotation.StringRes
import com.riffle.app.R
import com.riffle.feature.library.LibrarySortMode

/**
 * Android display label for each [LibrarySortMode]. The enum itself is platform-neutral (lives in
 * :feature:library / commonMain), so the string-resource mapping for the All Books sort menu lives
 * here in :app where the resources are.
 */
@get:StringRes
val LibrarySortMode.labelResId: Int get() = when (this) {
    LibrarySortMode.ADDED_DESC -> R.string.ui_sort_recently_added
    LibrarySortMode.ADDED_ASC -> R.string.ui_sort_oldest_first
    LibrarySortMode.TITLE_ASC -> R.string.ui_sort_title_az
    LibrarySortMode.TITLE_DESC -> R.string.ui_sort_title_za
    LibrarySortMode.AUTHOR_ASC -> R.string.ui_sort_author_az
    LibrarySortMode.RECENTLY_OPENED -> R.string.ui_sort_recently_opened
}
