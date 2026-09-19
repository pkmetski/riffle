package com.riffle.feature.reader

// The legacy substring-containment overlap detector. Superseded for the draft-commit path by the
// true-range detector in HighlightRangeOverlap.kt (2026-07-19), but still the check used where
// only the Readium text quotes are available and no chapter DOM has been parsed.

/** How much text either side of a snippet forms its position-matching context window. */
const val OVERLAP_CONTEXT_LEN = 60

/**
 * Returns true when [newSnippet]/[newAfter] and [existSnippet]/[existAfter] refer to the same
 * region of text — i.e. one highlight should be replaced by the other.
 *
 * Two conditions must BOTH hold:
 *  1. **Text overlap** — one snippet contains the other (substring test, case-insensitive).
 *  2. **Position match** — the "snippet + after-text" context window of each highlight must
 *     contain the other's window. After-text is used (not before-text) so that the check still
 *     passes when the new selection starts earlier than the existing one (a larger selection
 *     covering a smaller word). If [existAfter] is empty (pre-context annotation), position
 *     matching is skipped and text overlap alone is sufficient.
 *
 * Shared (issue #1066) so the same detector — and the same tests — run on Android and iOS.
 */
fun highlightOverlapsAtSamePosition(
    newSnippet: String,
    newAfter: String,
    existSnippet: String,
    existAfter: String,
    contextLen: Int = OVERLAP_CONTEXT_LEN,
): Boolean {
    val newTrimmed = newSnippet.trim().takeIf { it.isNotBlank() } ?: return false
    val existTrimmed = existSnippet.trim().takeIf { it.isNotBlank() } ?: return false
    val textOverlap = newTrimmed.contains(existTrimmed, ignoreCase = true) ||
        existTrimmed.contains(newTrimmed, ignoreCase = true)
    if (!textOverlap) return false
    val existAfterStart = existAfter.take(contextLen)
    if (existAfterStart.isEmpty()) return true
    val newContext = newTrimmed + newAfter.take(contextLen)
    val existContext = existTrimmed + existAfterStart
    return newContext.contains(existContext, ignoreCase = true) ||
        existContext.contains(newContext, ignoreCase = true)
}
