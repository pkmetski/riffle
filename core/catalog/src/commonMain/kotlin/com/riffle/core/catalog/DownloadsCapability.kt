package com.riffle.core.catalog

/**
 * Marker for Sources that fetch item bytes into a local store. Presence gates three coordinated
 * surfaces together — they are one concept:
 *  - the detail sheet's Download button,
 *  - the navigation drawer's "Downloads" entry above Settings,
 *  - the Downloads screen (both the Downloaded and Cached tiers, since Cached is a tier of the
 *    same local store).
 *
 * LocalFiles omits this — the file is already on the device, no fetch step. Chitanka / ABS / WebDAV
 * declare it.
 */
interface DownloadsCapability : CatalogCapability
