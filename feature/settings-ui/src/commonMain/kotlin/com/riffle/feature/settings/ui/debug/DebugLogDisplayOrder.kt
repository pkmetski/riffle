package com.riffle.feature.settings.ui.debug

import com.riffle.core.logging.InMemoryLogBuffer

/**
 * Reverses the log entry list so the newest entry appears first — a display concern extracted to
 * commonMain so it can be covered by a commonTest regression. Reverting the flip would silently
 * regress to an oldest-at-top view.
 */
internal fun debugLogDisplayOrder(
    entries: List<InMemoryLogBuffer.Entry>,
): List<InMemoryLogBuffer.Entry> = entries.asReversed()
