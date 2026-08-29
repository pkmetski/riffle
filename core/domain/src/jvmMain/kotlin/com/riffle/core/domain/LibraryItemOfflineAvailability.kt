package com.riffle.core.domain
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Decides whether a [LibraryItem] can be opened with no network — the single source of truth behind
 * the library's offline filtering. An item is available offline when its ebook is downloaded or
 * cached (EPUB/PDF), OR when its audiobook is downloaded. Audiobooks have a download-only tier (no
 * auto-cache), so the audio side is a plain `isDownloaded` check (ADR 0035). An item is ALSO
 * offline-available when a downloaded readaloud bundle can supply its audio ([BundleAudiobookSource]).
 *
 * Results are memoized per item: each uncached check costs up to ~8 `File.exists()` syscalls (a
 * miss internally constructs an ErrnoException with a full stack fill), and the library's offline
 * projections re-run this over EVERY item whenever any Room flow re-emits — which happens on every
 * reading-progress write, i.e. every reader page turn. Uncached, a large library turns each page
 * turn into a multi-second filesystem sweep. The cache is invalidated per item by
 * [LocalAvailabilityEvents.changes], which every download/cache/delete path already notifies.
 */
class LibraryItemOfflineAvailability(
    private val epubRepository: EpubRepository,
    private val pdfRepository: PdfRepository,
    private val cbzRepository: CbzRepository,
    private val audiobookDownloadRepository: AudiobookDownloadRepository,
    private val bundleAudiobookSource: BundleAudiobookSource,
    availabilityChanges: SharedFlow<StoredItemRef>? = null,
    invalidationScope: CoroutineScope? = null,
) {
    private val cache = ConcurrentHashMap<String, Boolean>()

    init {
        if (availabilityChanges != null && invalidationScope != null) {
            invalidationScope.launch {
                availabilityChanges.collect { ref -> cache.remove(key(ref.sourceId, ref.itemId)) }
            }
        }
    }

    fun isAvailableOffline(item: LibraryItem): Boolean =
        cache.getOrPut(key(item.sourceId, item.id)) { computeAvailability(item) }

    private fun computeAvailability(item: LibraryItem): Boolean {
        val ebookAvailable = when (item.ebookFormat) {
            EbookFormat.Epub ->
                epubRepository.isDownloaded(item.sourceId, item.id) || epubRepository.isCached(item.sourceId, item.id)
            EbookFormat.Pdf ->
                pdfRepository.isDownloaded(item.sourceId, item.id) || pdfRepository.isCached(item.sourceId, item.id)
            EbookFormat.Cbz ->
                cbzRepository.isDownloaded(item.sourceId, item.id) || cbzRepository.isCached(item.sourceId, item.id)
            EbookFormat.Unsupported -> false
        }
        return ebookAvailable ||
            audiobookDownloadRepository.isDownloaded(item.sourceId, item.id) ||
            bundleAudiobookSource.isAvailableOffline(item.sourceId, item.id)
    }

    private fun key(sourceId: String, itemId: String) = "$sourceId::$itemId"
}
