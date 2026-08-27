package com.riffle.app.feature.library

import androidx.annotation.StringRes
import com.riffle.app.R

enum class LibrarySectionType(@StringRes val titleResId: Int) {
    IN_PROGRESS(R.string.ui_section_in_progress),
    FINISHED(R.string.ui_section_completed),
    RECENTLY_ADDED(R.string.ui_section_recently_added),
    CONTINUE_SERIES(R.string.ui_section_continue_series),
}
