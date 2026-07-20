package com.riffle.core.domain

import java.io.File

/** Read-only view of the sidecar cache: is a book's sidecar on disk? */
interface ReadaloudSidecarCache {
    fun cachedFile(storytellerSourceId: String, storytellerBookId: String): File?

    /**
     * Deletes every cached sidecar owned by [storytellerSourceId]. Called from the source-removal
     * path (`SourceRepositoryImpl.remove`) so cached files don't outlive their owning source row.
     */
    fun purgeSource(storytellerSourceId: String)
}
