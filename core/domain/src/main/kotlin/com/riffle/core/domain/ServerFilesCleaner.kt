package com.riffle.core.domain

/**
 * Deletes every on-disk file a Server owns — downloaded and cached EPUBs/PDFs and downloaded
 * audiobooks. The DB cascade ([ServerRepository.remove]) clears a removed Server's metadata, but the
 * file stores live outside Room and would otherwise leak their bytes until OS cache reclaim or an
 * app-data clear. All stores key files under `<dir>/<serverId>/…` (ADR 0025), so removal is a
 * per-Server subtree delete.
 */
interface ServerFilesCleaner {
    suspend fun deleteAllForServer(serverId: String)
}
