package com.riffle.core.data

import com.riffle.core.common.FileStore
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.CrossEpubBuildInputs
import com.riffle.core.domain.CrossEpubIndexBuildOutcome
import com.riffle.core.domain.CrossEpubIndexBuildTrigger
import com.riffle.core.domain.CrossEpubIndexService
import com.riffle.core.domain.CrossEpubIndexStore
import com.riffle.core.domain.IosEpubChecksum
import com.riffle.core.domain.IosEpubContentExtractor
import com.riffle.core.domain.IosEpubTextChars
import com.riffle.core.models.ReadaloudLink
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * iOS counterpart to `CrossEpubIndexBuilderService`: builds and persists the cross-EPUB index for
 * Confirmed matched books (ADR 0023/0025).
 *
 * [enqueueBuild] is fire-and-forget on the survivable scope so a library refresh never blocks on
 * EPUB reads; the work is idempotent (the service skips when an index for the current checksums
 * already exists) and degrades to *deferred* — never a partial or wrong index — when a
 * prerequisite EPUB is not present locally yet.
 *
 * Like Android, this never downloads the Storyteller side proactively: that side is the synced
 * bundle (ADR 0027), hundreds of MB, so the build only runs once the bundle — or the much smaller
 * prepared sidecar (ADR 0040) — is already on disk. Unlike Android it also does not fetch the ABS
 * EPUB on demand; the build simply defers until the reader or a download has cached it.
 */
class IosCrossEpubIndexBuilderService(
    private val fileStore: FileStore,
    private val store: CrossEpubIndexStore,
    private val sidecarStore: IosReadaloudSidecarStore,
    private val clock: () -> Long,
    private val applicationScope: ApplicationScope,
) : CrossEpubIndexBuildTrigger {

    private val inFlight = mutableSetOf<Pair<String, String>>()
    private val mutex = Mutex()

    private val service = CrossEpubIndexService(
        loadInputs = ::loadInputs,
        store = store,
        clock = clock,
        countReadableChars = IosEpubTextChars::countReadableChars,
    )

    override fun enqueueBuild(link: ReadaloudLink) {
        val key = link.absSourceId to link.absLibraryItemId
        applicationScope.launchSurvivable {
            if (!mutex.withLock { inFlight.add(key) }) return@launchSurvivable
            try {
                ensureBuilt(link)
            } finally {
                mutex.withLock { inFlight.remove(key) }
            }
        }
    }

    suspend fun ensureBuilt(link: ReadaloudLink): CrossEpubIndexBuildOutcome = service.buildOnConfirm(link)

    private suspend fun loadInputs(link: ReadaloudLink): CrossEpubBuildInputs? {
        // Storyteller text: the downloaded bundle if present, otherwise the already-prepared
        // sidecar. Never fetch here — a missing prerequisite must defer, not block the refresh.
        val storytellerBytes = localEpubBytes(link.storytellerSourceId, link.storytellerBookId)
            ?: sidecarStore.cachedSidecarPath(link.storytellerSourceId, link.storytellerBookId)
                ?.let { IosAudiobookFiles.readBytes(it) }
            ?: return null

        val absBytes = localEpubBytes(link.absSourceId, link.absLibraryItemId) ?: return null

        val absExtract = IosEpubContentExtractor.extract(absBytes) ?: return null
        val storytellerExtract = IosEpubContentExtractor.extract(storytellerBytes) ?: return null

        return CrossEpubBuildInputs(
            absChecksum = IosEpubChecksum.of(absBytes),
            storytellerChecksum = IosEpubChecksum.of(storytellerBytes),
            absChaptersHtml = absExtract.chapters.map { it.html },
            storytellerChaptersHtml = storytellerExtract.chapters.map { it.html },
        )
    }

    private fun localEpubBytes(sourceId: String, itemId: String): ByteArray? {
        val download = fileStore.resolve(NS_EPUB_DOWNLOADS, "$sourceId/$itemId.epub")
        if (IosAudiobookFiles.exists(download)) return IosAudiobookFiles.readBytes(download)
        val cache = fileStore.resolve(NS_EPUB_CACHE, "$sourceId/$itemId.epub")
        return if (IosAudiobookFiles.exists(cache)) IosAudiobookFiles.readBytes(cache) else null
    }
}
