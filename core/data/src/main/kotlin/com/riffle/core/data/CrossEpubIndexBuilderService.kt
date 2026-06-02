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
 * a prerequisite EPUB can't be fetched. Both EPUBs are read cache-first and only downloaded
 * (the few-MB EPUB bundle, never the audio bundle) on the first build, then cached so later
 * refreshes just re-hash from disk.
 */
@Singleton
class CrossEpubIndexBuilderService(
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
    private val absApi: AbsLibraryApi,
    private val bundleFetcher: EpubBundleFetcher,
    @EpubCacheStore private val cacheStore: LocalStore,
    @EpubDownloadsStore private val downloadsStore: LocalStore,
    private val store: CrossEpubIndexStore,
    private val clock: () -> Long,
) {
    @Inject constructor(
        serverRepository: ServerRepository,
        tokenStorage: TokenStorage,
        absApi: AbsLibraryApi,
        bundleFetcher: EpubBundleFetcher,
        @EpubCacheStore cacheStore: LocalStore,
        @EpubDownloadsStore downloadsStore: LocalStore,
        store: CrossEpubIndexStore,
    ) : this(serverRepository, tokenStorage, absApi, bundleFetcher, cacheStore, downloadsStore, store, System::currentTimeMillis)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = Collections.synchronizedSet(mutableSetOf<Pair<String, String>>())

    private val service = CrossEpubIndexService(
        loadInputs = ::loadInputs,
        store = store,
        clock = clock,
    )

    /** Schedule an idempotent background build for [link]; returns immediately. */
    fun enqueueBuild(link: ReadaloudLink) {
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
        val absServer = serverRepository.getById(link.absServerId) ?: return null
        val absToken = tokenStorage.getToken(link.absServerId) ?: return null
        val storytellerServer = serverRepository.getById(link.storytellerServerId) ?: return null
        val storytellerToken = tokenStorage.getToken(link.storytellerServerId) ?: return null

        val absFile = absEpubFile(absServer, absToken, link.absLibraryItemId) ?: return null
        val storytellerFile = storytellerEpubFile(storytellerServer, storytellerToken, link.storytellerBookId) ?: return null

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

    private fun cachedFile(itemId: String): File? =
        downloadsStore.get(itemId) ?: cacheStore.get(itemId)

    private suspend fun absEpubFile(server: Server, token: String, itemId: String): File? {
        cachedFile(itemId)?.let { return it }
        val ino = when (val r = absApi.getItemEbookFileIno(server.url.value, itemId, token, server.insecureConnectionAllowed)) {
            is NetworkItemEbookInoResult.Success -> r.ino
            is NetworkItemEbookInoResult.NetworkError -> return null
        }
        return when (val r = absApi.downloadEpub(server.url.value, itemId, ino, token, server.insecureConnectionAllowed)) {
            is NetworkEpubDownloadResult.Success -> r.body.use { cacheStore.save(itemId, it.byteStream()) }
            is NetworkEpubDownloadResult.NetworkError -> null
        }
    }

    private suspend fun storytellerEpubFile(server: Server, token: String, bookId: String): File? {
        cachedFile(bookId)?.let { return it }
        return when (val r = bundleFetcher.fetch(server.url.value, bookId, token, server.insecureConnectionAllowed)) {
            is EpubBundleFetcher.Result.Success -> try {
                r.epubFile.inputStream().use { cacheStore.save(bookId, it) }
            } finally {
                r.epubFile.delete()
            }
            is EpubBundleFetcher.Result.NetworkError -> null
        }
    }
}
