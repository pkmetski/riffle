package com.riffle.app.feature.library

import com.riffle.core.models.LibraryItem

internal fun homeLeadingSectionKey(
    inProgress: List<LibraryItem>,
    continueSeries: List<LibraryItem>,
    recentlyAdded: List<LibraryItem>,
    finished: List<LibraryItem>,
): String? = when {
    inProgress.isNotEmpty() -> "in_progress"
    continueSeries.isNotEmpty() -> "continue_series"
    recentlyAdded.isNotEmpty() -> "recently_added"
    finished.isNotEmpty() -> "finished"
    else -> null
}
