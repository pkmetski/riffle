package com.riffle.core.data

import com.riffle.core.domain.EpubAnalyzer
import com.riffle.core.domain.EpubChecksum
import com.riffle.core.domain.EpubContentExtractor
import com.riffle.core.domain.ExtractedEpub
import com.riffle.core.domain.LocalEpubLocator
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.ReadaloudSidecarCache
import java.io.File

/** [LocalEpubLocator] over the existing [LocalStore] pair plus the sidecar cache. */
class JvmLocalEpubLocator(
    private val cacheStore: LocalStore,
    private val downloadsStore: LocalStore,
    private val sidecarCache: ReadaloudSidecarCache,
) : LocalEpubLocator {

    override fun localEpubPath(sourceId: String, itemId: String): String? =
        (downloadsStore.get(sourceId, itemId) ?: cacheStore.get(sourceId, itemId))?.absolutePath

    override fun sidecarEpubPath(storytellerSourceId: String, storytellerBookId: String): String? =
        sidecarCache.cachedFile(storytellerSourceId, storytellerBookId)?.absolutePath
}

/**
 * [EpubAnalyzer] backed by the JVM's streaming primitives, so a synced bundle (ADR 0027) is
 * hashed and walked without ever being held in memory.
 */
object JvmEpubAnalyzer : EpubAnalyzer {
    override fun checksum(path: String): String? =
        File(path).takeIf { it.exists() }?.let { EpubChecksum.of(it) }

    override fun extract(path: String): ExtractedEpub? =
        File(path).takeIf { it.exists() }?.let { EpubContentExtractor.extract(it) }
}
