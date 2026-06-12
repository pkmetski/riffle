package com.riffle.core.data

import com.riffle.core.data.di.EpubCacheStore
import com.riffle.core.data.di.EpubDownloadsStore
import com.riffle.core.domain.CrossEpubBuildInputs
import com.riffle.core.domain.CrossEpubIndexBuildOutcome
import com.riffle.core.domain.CrossEpubIndexService
import com.riffle.core.domain.CrossEpubIndexStore
import com.riffle.core.domain.EpubChecksum
import com.riffle.core.domain.EpubContentExtractor
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.ReadaloudLink
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkEpubDownloadResult
import com.riffle.core.network.NetworkItemEbookInoResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * The ABS publisher EPUB is small and is downloaded-then-cached on the first build. The Storyteller
 * side, however, is the *synced bundle* (ADR 0023) — the full readaloud audio, hundreds of MB — so
 * this service never downloads it proactively: it builds only once that bundle is already present
 * locally (i.e. after the user has downloaded readaloud for the book). Pulling and caching one synced
 * bundle per Confirmed match in the background would fill the device's storage, starving real user
 * downloads and truncating in-flight ones. This isn't a functional regression: the canonical reconciliation cycle
 * ([ReaderSyncFactory]) already gates on that same locally-present bundle, so an index built
 * before the bundle exists could never be used anyway.
 */
@Singleton
class CrossEpubIndexBuilderService(
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
    private val absApi: AbsLibraryApi,
    @EpubCacheStore private val cacheStore: LocalStore,
    @EpubDownloadsStore private val downloadsStore: LocalStore,
    private val store: CrossEpubIndexStore,
    private val clock: () -> Long,
) : CrossEpubIndexBuildTrigger {
    @Inject constructor(
        serverRepository: ServerRepository,
        tokenStorage: TokenStorage,
        absApi: AbsLibraryApi,
        @EpubCacheStore cacheStore: LocalStore,
        @EpubDownloadsStore downloadsStore: LocalStore,
        store: CrossEpubIndexStore,
    ) : this(serverRepository, tokenStorage, absApi, cacheStore, downloadsStore, store, System::currentTimeMillis)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = Collections.synchronizedSet(mutableSetOf<Pair<String, String>>())

    private val service = CrossEpubIndexService(
        loadInputs = ::loadInputs,
        store = store,
        clock = clock,
    )

    /** Schedule an idempotent background build for [link]; returns immediately. */
    override fun enqueueBuild(link: ReadaloudLink) {
        val key = link.absServerId to link.absLibraryItemId
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
        // Storyteller synced bundle: locally-present only, never proactively downloaded (see the class
        // doc). Absent → defer the build until readaloud has been downloaded for this book. Checked
        // first (it's a cheap local lookup) so a not-yet-downloaded match doesn't even pull the ABS
        // publisher EPUB — nothing it produces could be used until the bundle arrives anyway.
        val storytellerFile = cachedFile(link.storytellerServerId, link.storytellerBookId) ?: return null

        val absServer = serverRepository.getById(link.absServerId) ?: return null
        val absToken = tokenStorage.getToken(link.absServerId) ?: return null
        val absFile = absEpubFile(absServer, absToken, link.absLibraryItemId) ?: return null

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

    private fun cachedFile(serverId: String, itemId: String): File? =
        downloadsStore.get(serverId, itemId) ?: cacheStore.get(serverId, itemId)

    private suspend fun absEpubFile(server: Server, token: String, itemId: String): File? {
        cachedFile(server.id, itemId)?.let { return it }
        val ino = when (val r = absApi.getItemEbookFileIno(server.url.value, itemId, token, server.insecureConnectionAllowed)) {
            is NetworkItemEbookInoResult.Success -> r.ino
            is NetworkItemEbookInoResult.NetworkError -> return null
        }
        return when (val r = absApi.downloadEpub(server.url.value, itemId, ino, token, server.insecureConnectionAllowed)) {
            is NetworkEpubDownloadResult.Success -> r.body.use { cacheStore.save(server.id, itemId, it.byteStream()) }
            is NetworkEpubDownloadResult.NetworkError -> null
        }
    }
}
