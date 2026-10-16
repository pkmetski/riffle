package com.riffle.feature.library.ui

import com.riffle.core.models.LibraryItem

data class DownloadsActions(
    val onNavigateBack: () -> Unit,
    val onItemSelected: (LibraryItem) -> Unit,
)

fun DownloadsActions.probeFields(): List<Pair<String, Any>> = listOf(
    "onNavigateBack" to onNavigateBack,
    "onItemSelected" to onItemSelected,
)
