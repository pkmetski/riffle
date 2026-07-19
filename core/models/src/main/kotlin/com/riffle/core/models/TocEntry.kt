package com.riffle.core.models

import kotlinx.serialization.Serializable

@Serializable
data class TocEntry(
    val title: String,
    val href: String,
    val children: List<TocEntry> = emptyList(),
)
