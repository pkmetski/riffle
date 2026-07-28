package com.riffle.core.domain
import com.riffle.core.models.ReadaloudLink

/**
 * The locally-cached materials needed to build a cross-EPUB index for one matched book. The EPUBs
 * are referenced by their precomputed [EpubChecksum] (streamed from disk by the caller) rather than
 * their raw bytes, so a hundreds-of-MB synced bundle (ADR 0023) is never held in memory here.
 */
data class CrossEpubBuildInputs(
    val absChecksum: String,
    val storytellerChecksum: String,
    val absChaptersHtml: List<String>,
    val storytellerChaptersHtml: List<String>,
)

/** Persistence port for the cross-EPUB index cache (backed by the `cross_epub_index` table). */
interface CrossEpubIndexStore {
    suspend fun exists(absChecksum: String, storytellerChecksum: String): Boolean
    suspend fun put(absChecksum: String, storytellerChecksum: String, blob: String, builtAt: Long)
    /** Load the cached index for the current checksums, or `null` on a miss (rebuild needed). */
    suspend fun load(absChecksum: String, storytellerChecksum: String): CrossEpubIndex? = null
}

sealed interface CrossEpubIndexBuildOutcome {
    /** Both EPUBs were present and the index was built and persisted under their checksums. */
    data class Built(val absChecksum: String, val storytellerChecksum: String) : CrossEpubIndexBuildOutcome
    /** A row for the current checksums already existed — nothing rebuilt. */
    data object AlreadyBuilt : CrossEpubIndexBuildOutcome
    /** A prerequisite EPUB couldn't be obtained; the book degrades to single-peer until it lands. */
    data object Deferred : CrossEpubIndexBuildOutcome
}

/**
 * Builds and persists the cross-EPUB index for a Confirmed [ReadaloudLink] (ADR 0019/0021):
 * eagerly on Confirm, and opportunistically on the first sync cycle that needs it if the
 * eager build was deferred. [loadInputs] ensures both EPUBs are cached (fetching the
 * Storyteller bundle and/or ABS EPUB as needed — never the audiobook bundle) and returns
 * the build materials, or `null` when a prerequisite can't be obtained. A missing
 * prerequisite defers rather than persisting a partial index, so a sync cycle never gets a
 * wrong cross-domain mapping — only a deferred one.
 */
class CrossEpubIndexService(
    private val loadInputs: suspend (ReadaloudLink) -> CrossEpubBuildInputs?,
    private val store: CrossEpubIndexStore,
    private val clock: () -> Long,
) {
    suspend fun buildOnConfirm(link: ReadaloudLink): CrossEpubIndexBuildOutcome {
        val inputs = loadInputs(link) ?: return CrossEpubIndexBuildOutcome.Deferred

        val absChecksum = inputs.absChecksum
        val storytellerChecksum = inputs.storytellerChecksum
        if (store.exists(absChecksum, storytellerChecksum)) return CrossEpubIndexBuildOutcome.AlreadyBuilt

        val index = CrossEpubIndexBuilder.build(inputs.absChaptersHtml, inputs.storytellerChaptersHtml)
        store.put(absChecksum, storytellerChecksum, CrossEpubIndexSerializer.encode(index), clock())
        return CrossEpubIndexBuildOutcome.Built(absChecksum, storytellerChecksum)
    }
}
