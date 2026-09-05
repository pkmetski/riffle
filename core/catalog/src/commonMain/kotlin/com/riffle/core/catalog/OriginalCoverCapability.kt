package com.riffle.core.catalog

/**
 * Exposes the cover discovered from an item's source metadata before any user-selected override
 * is applied. Sources that support editable metadata use this to implement "restore from
 * metadata" without treating an override-applied catalog item as the original.
 */
interface OriginalCoverCapability : CatalogCapability {
    suspend fun originalCoverUrl(itemId: String): String?
}
