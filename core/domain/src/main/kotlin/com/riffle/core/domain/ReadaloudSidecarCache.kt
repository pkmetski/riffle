package com.riffle.core.domain

import java.io.File

/** Read-only view of the sidecar cache: is a book's sidecar on disk? */
fun interface ReadaloudSidecarCache {
    fun cachedFile(storytellerSourceId: String, storytellerBookId: String): File?
}
