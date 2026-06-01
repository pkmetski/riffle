package com.riffle.app.feature.library

internal enum class LibraryBackAction { ClearSearch, ResetToHomeTab, Exit }

internal fun libraryBackAction(searchQuery: String, selectedTab: Int): LibraryBackAction =
    when {
        searchQuery.isNotEmpty() -> LibraryBackAction.ClearSearch
        selectedTab != 0 -> LibraryBackAction.ResetToHomeTab
        else -> LibraryBackAction.Exit
    }
