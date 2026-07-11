package com.riffle.core.domain

/**
 * Whether the Source's storage model includes an implicit, evictable **Cache** tier alongside the
 * explicit **Download** tier (ADR 0011 / ADR 0041).
 *
 * Sources with a remote catalog (ABS today, OPDS/Kavita/… later) keep the two-tier split — cache is
 * populated on open, downloads are populated by the user. LocalFiles is single-tier: bytes are
 * copied in permanently on scan, so there's no Cache concept to expose.
 *
 * Consumers use this to gate cache-on-open writes and to decide the Downloads Screen section count.
 */
fun SourceType.hasCacheTier(): Boolean = when (this) {
    SourceType.ABS -> true
    SourceType.LOCAL_FILES -> false
    // Chitanka has a remote catalog, but the on-open cache is a tacit page cache rather than
    // an explicit user-visible tier — no separate "Cached" section in the Downloads Screen.
    SourceType.CHITANKA -> false
}

/**
 * Whether the Downloads Screen should render a separate **Cached** section for the given active
 * Source. Null (no active source yet) preserves the current two-section layout — a UI without an
 * active Source is transient and shouldn't flicker.
 */
fun showCachedSectionFor(activeSource: Source?): Boolean =
    activeSource?.type?.hasCacheTier() ?: true

/**
 * Whether the Source can carry a paired Storyteller **readaloud** bundle (synced audio + text). ABS
 * is the only Source today with matched Storyteller items; every other Source has no readaloud
 * concept, so the Downloads Screen's "Readaloud (streaming)" section is meaningless there.
 *
 * Consumers use this to gate the Readaloud section header alongside [hasCacheTier].
 */
fun SourceType.hasReadaloud(): Boolean = when (this) {
    SourceType.ABS -> true
    SourceType.LOCAL_FILES -> false
    SourceType.CHITANKA -> false
}

/**
 * Whether the Downloads Screen should render the **Readaloud (streaming)** section for the given
 * active Source. Null preserves the existing layout for parity with [showCachedSectionFor].
 */
fun showReadaloudSectionFor(activeSource: Source?): Boolean =
    activeSource?.type?.hasReadaloud() ?: true
