package com.riffle.feature.reader

/**
 * Platform-neutral snapshot of the reader's current Locator — the readaloud session boundary
 * type for position hand-offs. Android populates it from `Locator.toJSON()` before passing it
 * across the seam; iOS populates it from its own Swift navigator snapshot.
 */
data class ReadaloudPosition(
    val href: String,
    val progression: Double?,
    /** Readium Locator JSON string, used for position saves and Storyteller sync round-trips. */
    val locatorJson: String,
)
