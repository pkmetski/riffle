package com.riffle.feature.reader

data class RailSegment(
    val title: String,
    val href: String,
    val weight: Float = 1f,
    val groupIndex: Int? = null,
)
