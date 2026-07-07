package com.riffle.core.domain

/**
 * Resolves the canonical [AudioIdentity] for a readaloud (ADR 0028): the linked audiobook's ABS id
 * when an audiobook is linked, otherwise the Storyteller readaloud id.
 */
interface AudioIdentityResolver {
    suspend fun resolveForStorytellerBook(
        storytellerSourceId: String,
        storytellerBookId: String,
    ): AudioIdentity
}
