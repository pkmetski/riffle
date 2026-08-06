package com.riffle.app.feature.library

import android.app.Activity

internal enum class LibraryBackAction { ClearSearch, ResetToHomeTab, Exit }

internal fun libraryBackAction(searchQuery: String, selectedTab: Int): LibraryBackAction =
    when {
        searchQuery.isNotEmpty() -> LibraryBackAction.ClearSearch
        selectedTab != 0 -> LibraryBackAction.ResetToHomeTab
        else -> LibraryBackAction.Exit
    }

// Moves the task to the background rather than finishing the activity. Using finish() on a
// singleTop activity on Android 12+ causes the activity to be re-created in the same process,
// which re-delivers the Back event and creates an infinite finish→recreate loop.
internal fun handleLibraryExit(activity: Activity?) {
    activity?.moveTaskToBack(true)
}
