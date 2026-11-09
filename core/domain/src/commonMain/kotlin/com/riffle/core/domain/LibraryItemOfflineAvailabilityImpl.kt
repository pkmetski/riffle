package com.riffle.core.domain

import com.riffle.core.common.Clock
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Decides whether a [LibraryItem] can be opened with no network — the single source of truth behind
 * the library's offline filtering. An item is available offline when its ebook is downloaded or
 * cached (EPUB/PDF/CBZ), OR when its audiobook is downloaded, OR when a downloaded readaloud bundle
 * can supply its audio ([BundleAudiobookSource]).
 *
 * Results are memoised per item: each uncached check costs multiple filesystem syscalls, and the
 * library's offline projections re-run this over every item whenever any database flow re-emits —
 * which happens on every reading-progress write (i.e. every page turn). Uncached, a large library
 * turns each page turn into a multi-second filesystem sweep.
 *
 * Invalidation is two-layered:
 *  - Event-driven: [LocalAvailabilityEvents.changes] evicts the exact item on every
 *    download/cache/delete path that notifies.
 *  - TTL backstop ([ttlMillis]): paths that do NOT notify (readaloud bundle download/remove) and
 *    the check-then-act window both self-heal within one TTL. 30 s keeps ~all syscall savings.
 *
 * Shared `commonMain` implementation — replaces the former `jvmMain`-only
 * `ConcurrentHashMap` version. Thread-safety comes from a [Mutex]-protected map; the calling
 * convention is synchronous (`isAvailableOffline` is not a suspend fun) so the mutex is locked
 * on the caller's thread rather than suspending.
 */
class LibraryItemOfflineAvailabilityImpl(
    private val epubRepository: EpubRepository,
    private val pdfRepository: PdfRepository,
    private val cbzRepository: CbzRepository,
    private val audiobookDownloadRepository: AudiobookDownloadRepository,
    private val bundleAudiobookSource: BundleAudiobookSource,
    availabilityChanges: SharedFlow<StoredItemRef>? = null,
    invalidationScope: CoroutineScope? = null,
    private val ttlMillis: Long = 30_000,
    private val clock: Clock,
) : LibraryItemOfflineAvailability {

    private data class Entry(val available: Boolean, val computedAtMs: Long)

    private val mutex = Mutex()
    private val cache = mutableMapOf<String, Entry>()

    init {
        if (availabilityChanges != null && invalidationScope != null) {
            invalidationScope.launch {
                availabilityChanges.collect { ref ->
                    mutex.withLock { cache.remove(key(ref.sourceId, ref.itemId)) }
                }
            }
        }
    }

    override fun isAvailableOffline(item: LibraryItem): Boolean {
        val k = key(item.sourceId, item.id)
        val now = clock.nowMs()
        // Fast read: check without lock first (tolerate a stale hit; correctness is bounded by TTL)
        cache[k]?.let { entry ->
            if (now - entry.computedAtMs < ttlMillis) return entry.available
        }
        val computed = computeAvailability(item)
        // Write under lock to prevent concurrent updates corrupting the map
        mutex.tryLock().let { locked ->
            if (locked) try { cache[k] = Entry(computed, now) } finally { mutex.unlock() }
        }
        return computed
    }

    private fun computeAvailability(item: LibraryItem): Boolean {
        val ebookAvailable = when (item.ebookFormat) {
            EbookFormat.Epub ->
                epubRepository.isDownloaded(item.sourceId, item.id) ||
                    epubRepository.isCached(item.sourceId, item.id)
            EbookFormat.Pdf ->
                pdfRepository.isDownloaded(item.sourceId, item.id) ||
                    pdfRepository.isCached(item.sourceId, item.id)
            EbookFormat.Cbz ->
                cbzRepository.isDownloaded(item.sourceId, item.id) ||
                    cbzRepository.isCached(item.sourceId, item.id)
            EbookFormat.Unsupported -> false
        }
        return ebookAvailable ||
            audiobookDownloadRepository.isDownloaded(item.sourceId, item.id) ||
            bundleAudiobookSource.isAvailableOffline(item.sourceId, item.id)
    }

    private fun key(sourceId: String, itemId: String) = "$sourceId::$itemId"
}
