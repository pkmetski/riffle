package com.riffle.feature.library

/**
 * Sort mode for the All Books tab. Default is [ADDED_DESC] — newest additions first — so the tab
 * behaves as a "what's new" surface rather than a static alphabetical dump. For every mode, rows
 * whose keying field is null or the sentinel 0 (browse-cached rows, per `LibraryItemEntity`) sort
 * to the tail with a title tie-break so sources that can't supply a value never pollute the top.
 *
 * Platform-neutral: display labels are mapped per platform (Android maps each entry to a string
 * resource; see `LibrarySortMode.labelResId` in :app).
 */
enum class LibrarySortMode {
    ADDED_DESC,
    ADDED_ASC,
    TITLE_ASC,
    TITLE_DESC,
    AUTHOR_ASC,
    RECENTLY_OPENED,
}
