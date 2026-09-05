package com.riffle.core.catalog

data class CatalogStats(
    val totalSecondsListened: Double = 0.0,
    val totalItemsInProgress: Int = 0,
    val totalItemsFinished: Int = 0,
)
