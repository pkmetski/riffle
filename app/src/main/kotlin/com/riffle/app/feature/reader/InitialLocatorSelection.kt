package com.riffle.app.feature.reader

import org.readium.r2.shared.publication.Locator

/**
 * Picks the initial locator handed to Readium's `EpubNavigatorFragment` when the reader
 * boots (paginated + vertical modes).
 *
 * Normally the last-known reading position wins so reopens land where the user left off.
 * But when the reader was opened from an annotation tap (Annotations View → "Open in book"),
 * [focusAnnotationId] is populated from the openAtCfi resolver, and the last-locator snapshot
 * is unreliable — `runReaderSyncCycle` inside `openBook` writes the ABS server's last-read
 * position into it before first composition, which then shadows the annotation target and
 * lands the reader in the wrong chapter. The continuous renderer escapes this because its
 * `focusAnnotationId` path centres on the actual `<mark>` element; paginated/vertical have
 * no equivalent rescue, so we must honour [initial] directly for that flow.
 */
internal fun pickInitialLocator(
    focusAnnotationId: String?,
    latest: Locator?,
    initial: Locator?,
): Locator? = when {
    focusAnnotationId != null -> initial
    else -> latest ?: initial
}
