package com.riffle.core.catalog

interface StatsCapability : CatalogCapability {
    suspend fun getStats(): CatalogStats
}
