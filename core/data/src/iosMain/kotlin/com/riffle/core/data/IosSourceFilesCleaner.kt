package com.riffle.core.data

import com.riffle.core.common.FileStore
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.SourceFilesCleaner
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager

/**
 * iOS [SourceFilesCleaner] (#1101): purges every per-source directory the iOS downloaders and
 * caches write — `<namespace>/<sourceId>/` under the EPUB, PDF and CBZ download/cache stores and
 * the audiobook downloads root — so removing a source does not leave its archives on disk for
 * the Downloads screen and cache accounting to keep listing under a dead source id. Android's
 * `SourceFilesCleanerImpl` does the same over its `LocalStore`s.
 */
@OptIn(ExperimentalForeignApi::class)
class IosSourceFilesCleaner(
    private val fileStore: FileStore,
    private val dispatchers: DispatcherProvider,
) : SourceFilesCleaner {

    override suspend fun deleteAllForSource(sourceId: String) = withContext(dispatchers.io) {
        NAMESPACES.forEach { namespace ->
            NSFileManager.defaultManager.removeItemAtPath(fileStore.resolve(namespace, sourceId), error = null)
        }
    }

    companion object {
        val NAMESPACES = listOf(
            NS_EPUB_DOWNLOADS,
            NS_EPUB_CACHE,
            NS_PDF_DOWNLOADS,
            NS_PDF_CACHE,
            NS_CBZ_DOWNLOADS,
            NS_CBZ_CACHE,
            NS_AUDIOBOOK_DOWNLOADS,
        )
    }
}
