package com.riffle.core.domain

/**
 * Deletes every on-disk file a Source owns — downloaded and cached EPUBs/PDFs and downloaded
 * audiobooks. The DB cascade ([SourceRepository.remove]) clears a removed Source's metadata, but the
 * file stores live outside Room and would otherwise leak their bytes until OS cache reclaim or an
 * app-data clear. All stores key files under `<dir>/<sourceId>/…` (ADR 0025), so removal is a
 * per-Source subtree delete.
 */
interface SourceFilesCleaner {
    suspend fun deleteAllForSource(sourceId: String)
}
