package com.riffle.core.domain

import kotlinx.serialization.Serializable

@Serializable
data class TocEntry(
    val title: String,
    val href: String,
    val children: List<TocEntry> = emptyList(),
)
