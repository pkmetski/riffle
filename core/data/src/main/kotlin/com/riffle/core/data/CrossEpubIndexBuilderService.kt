package com.riffle.core.data

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.data.di.EpubCacheStore
import com.riffle.core.data.di.EpubDownloadsStore
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.CrossEpubBuildInputs
import com.riffle.core.domain.CrossEpubIndexBuildOutcome
import com.riffle.core.domain.CrossEpubIndexService
import com.riffle.core.domain.CrossEpubIndexStore
import com.riffle.core.domain.EpubChecksum
import com.riffle.core.domain.EpubContentExtractor
import com.riffle.core.domain.LocalStore
import com.riffle.core.models.ReadaloudLink
import kotlinx.coroutines.launch
import java.io.File
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds and persists the cross-EPUB index for Confirmed matched books (ADR 0019/0021).
 *
 * [enqueueBuild] is fire-and-forget on a background scope so a library refresh never blocks
 * on EPUB downloads; the work is idempotent (it skips when the index for the current
 * checksums already exists) and degrades to *deferred* — never a partial/wrong index — when
 * a prerequisite EPUB isn't available yet.
 *
 * The Source-side publisher EPUB is small and is downloaded-then-cached on the first build. The
 * Storyteller side, however, is the *synced bundle* (ADR 0023) — the full readaloud audio, hundreds
 * of MB — so this service never downloads it proactively: it builds only once that bundle is already
 * present locally (i.e. after the user has downloaded readaloud for the book).
 */
@Singleton
class CrossEpubIndexBuilderService(
    private val catalogRegistry: CatalogRegistry,
    @EpubCacheStore private val cacheStore: LocalStore,
    @EpubDownloadsStore private val downloadsStore: LocalStore,
    private val store: CrossEpubIndexStore,
    private val sidecarStore: ReadaloudSidecarStore,
    private val clock: () -> Long,
    applicationScope: ApplicationScope,
) : CrossEpubIndexBuildTrigger {
    @Inject constructor(
        catalogRegistry: CatalogRegistry,
        @EpubCacheStore cacheStore: LocalStore,
        @EpubDownloadsStore downloadsStore: LocalStore,
        store: CrossEpubIndexStore,
        sidecarStore: ReadaloudSidecarStore,
        applicationScope: ApplicationScope,
    ) : this(catalogRegistry, cacheStore, downloadsStore, store, sidecarStore, System::currentTimeMillis, applicationScope)

    private val scope = applicationScope.coroutineScope
    private val inFlight = Collections.synchronizedSet(mutableSetOf<Pair<String, String>>())

    private val service = CrossEpubIndexService(
        loadInputs = ::loadInputs,
        store = store,
        clock = clock,
    )

    /** Schedule an idempotent background build for [link]; returns immediately. */
    override fun enqueueBuild(link: ReadaloudLink) {
        val key = link.absSourceId to link.absLibraryItemId
        if (!inFlight.add(key)) return
        scope.launch {
            try {
                ensureBuilt(link)
            } finally {
                inFlight.remove(key)
            }
        }
    }

    suspend fun ensureBuilt(link: ReadaloudLink): CrossEpubIndexBuildOutcome = service.buildOnConfirm(link)

    private suspend fun loadInputs(link: ReadaloudLink): CrossEpubBuildInputs? {
        // Storyteller text for the index: the downloaded bundle if present, otherwise the ~1 MB sidecar
        // (ADR 0028). Use ONLY the already-prepared sidecar (cachedFile, non-blocking) — never fetch here.
        val storytellerFile = cachedFile(link.storytellerSourceId, link.storytellerBookId)
            ?: sidecarStore.cachedFile(link.storytellerSourceId, link.storytellerBookId)
            ?: return null

        val absFile = absEpubFile(link.absSourceId, link.absLibraryItemId) ?: return null

        // extract() reads only the OPF + spine chapters + SMIL from the zip, and of() streams the
        // file — so the hundreds-of-MB synced bundle (ADR 0023) is never held in memory.
        val absExtract = EpubContentExtractor.extract(absFile) ?: return null
        val storytellerExtract = EpubContentExtractor.extract(storytellerFile) ?: return null

        return CrossEpubBuildInputs(
            absChecksum = EpubChecksum.of(absFile),
            storytellerChecksum = EpubChecksum.of(storytellerFile),
            absChaptersHtml = absExtract.chapters.map { it.html },
            storytellerChaptersHtml = storytellerExtract.chapters.map { it.html },
        )
    }

    private fun cachedFile(sourceId: String, itemId: String): File? =
        downloadsStore.get(sourceId, itemId) ?: cacheStore.get(sourceId, itemId)

    private suspend fun absEpubFile(sourceId: String, itemId: String): File? {
        cachedFile(sourceId, itemId)?.let { return it }
        val catalog = catalogRegistry.forSourceId(sourceId) ?: return null
        return runCatching {
            catalog.openFile(itemId, BookFormat.Epub).use { stream ->
                cacheStore.save(sourceId, itemId, stream.byteStream())
            }
        }.getOrNull()
    }
}
