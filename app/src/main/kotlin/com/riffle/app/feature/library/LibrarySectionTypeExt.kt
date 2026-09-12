package com.riffle.app.feature.library

import androidx.annotation.StringRes
import com.riffle.app.R
import com.riffle.feature.library.LibrarySectionType

@StringRes
fun LibrarySectionType.titleResId(): Int = when (this) {
    LibrarySectionType.IN_PROGRESS -> R.string.ui_section_in_progress
    LibrarySectionType.FINISHED -> R.string.ui_section_completed
    LibrarySectionType.RECENTLY_ADDED -> R.string.ui_section_recently_added
    LibrarySectionType.CONTINUE_SERIES -> R.string.ui_section_continue_series
}
