package com.riffle.core.models

/**
 * The key that owns a book's audio playback settings. It is resolved (see [AudioIdentityResolver])
 * to the linked audiobook's `(absSourceId, absLibraryItemId)` when one exists, otherwise the
 * Storyteller readaloud's `(storytellerSourceId, storytellerBookId)`. `sourceId` is always a row in
 * the `servers` table, so Storyteller-rooted and ABS-rooted keys never collide (ADR 0025 / 0028).
 */
data class AudioIdentity(
    val sourceId: String,
    val bookId: String,
)
