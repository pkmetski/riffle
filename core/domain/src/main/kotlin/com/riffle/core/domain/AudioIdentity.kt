package com.riffle.core.domain

/**
 * The key that owns a book's audio playback settings. It is resolved (see [AudioIdentityResolver])
 * to the linked audiobook's `(absServerId, absLibraryItemId)` when one exists, otherwise the
 * Storyteller readaloud's `(storytellerServerId, storytellerBookId)`. `serverId` is always a row in
 * the `servers` table, so Storyteller-rooted and ABS-rooted keys never collide (ADR 0025 / 0028).
 */
data class AudioIdentity(
    val serverId: String,
    val bookId: String,
)
