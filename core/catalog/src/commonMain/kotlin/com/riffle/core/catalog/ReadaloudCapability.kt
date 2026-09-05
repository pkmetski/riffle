package com.riffle.core.catalog

/**
 * Marker for Sources that carry paired Storyteller synced audio + text bundles. Gates the readaloud
 * bundle affordance on the detail sheet and the "Readaloud (streaming)" section of the Downloads
 * screen. Only meaningful for Sources that also implement [DownloadsCapability]. ABS is the only
 * declarer today.
 */
interface ReadaloudCapability : CatalogCapability
