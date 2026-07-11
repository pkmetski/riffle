package com.riffle.core.catalog

/**
 * Marker for Sources whose items can be opened in the ebook reader. Gates the Read button on the
 * detail sheet. Symmetric with [AudiobookMediaCapability] on the audio side.
 *
 * Universal today — every Source ships ebook items. Kept explicit so a future audio-only Source
 * can opt out without special-casing UI.
 */
interface ReadCapability : CatalogCapability
