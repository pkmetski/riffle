package com.riffle.app.feature.reader

data class TocEntry(
    val title: String,
    val href: String,
    val children: List<TocEntry> = emptyList(),
)
