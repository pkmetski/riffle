package com.riffle.core.domain

/**
 * Persists which highlight the elided Highlights-mode reader (ADR 0041) last showed, so reopening
 * the same book resumes near where the user left off. Keyed by (serverId, itemId) like the other
 * per-book stores. Device-local only: unlike annotations themselves, this is never synced.
 */
interface HighlightsResumeStore {
    suspend fun lastHighlightId(serverId: String, itemId: String): String?
    suspend fun setLastHighlightId(serverId: String, itemId: String, annotationId: String)
}
