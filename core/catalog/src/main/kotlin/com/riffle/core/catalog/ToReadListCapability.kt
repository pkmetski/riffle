package com.riffle.core.catalog

/**
 * Marker for Sources that expose a user-visible to-read (wishlist) surface. Gates the Add-to-read
 * / Remove-from-read affordance on the detail sheet.
 *
 * Universal today — every Source implements it, either via a remote wishlist (ABS PlaylistsCapability)
 * or via the LocalToReadStore fallback in ToReadRepositoryImpl. Kept explicit so a future Source can
 * opt out (e.g. read-only sample sources) without touching UI shell code.
 */
interface ToReadListCapability : CatalogCapability
