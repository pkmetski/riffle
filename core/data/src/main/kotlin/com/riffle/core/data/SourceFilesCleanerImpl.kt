package com.riffle.core.data

import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.SourceFilesCleaner
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Purges a Source's files across all on-disk stores (ADR 0025). [stores] are the EPUB/PDF
 * download+cache [LocalStore]s; [audiobookDownloadsDir] is the audiobook download root, which is a
 * directory-per-item tree rather than a [LocalStore] (ADR 0029) but follows the same
 * `<root>/<sourceId>/…` layout, so it too is removed as a per-Source subtree.
 */
class SourceFilesCleanerImpl(
    private val stores: List<LocalStore>,
    private val audiobookDownloadsDir: File,
    private val dispatchers: DispatcherProvider,
) : SourceFilesCleaner {

    override suspend fun deleteAllForSource(sourceId: String) = withContext(dispatchers.io) {
        stores.forEach { it.deleteServer(sourceId) }
        File(audiobookDownloadsDir, sourceId).deleteRecursively()
        Unit
    }
}
