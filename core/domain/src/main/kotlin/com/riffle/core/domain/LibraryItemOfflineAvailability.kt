package com.riffle.core.domain

/**
 * Decides whether a [LibraryItem] can be opened with no network — the single source of truth behind
 * the library's offline filtering. An item is available offline when its ebook is downloaded or
 * cached (EPUB/PDF), OR when its audiobook is downloaded. Audiobooks have a download-only tier (no
 * auto-cache), so the audio side is a plain `isDownloaded` check (ADR 0029). An item is ALSO
 * offline-available when a downloaded readaloud bundle can supply its audio ([BundleAudiobookSource]).
 */
class LibraryItemOfflineAvailability(
    private val epubRepository: EpubRepository,
    private val pdfRepository: PdfRepository,
    private val audiobookDownloadRepository: AudiobookDownloadRepository,
    private val bundleAudiobookSource: BundleAudiobookSource,
) {
    fun isAvailableOffline(item: LibraryItem): Boolean {
        val ebookAvailable = when (item.ebookFormat) {
            EbookFormat.Epub ->
                epubRepository.isDownloaded(item.sourceId, item.id) || epubRepository.isCached(item.sourceId, item.id)
            EbookFormat.Pdf ->
                pdfRepository.isDownloaded(item.sourceId, item.id) || pdfRepository.isCached(item.sourceId, item.id)
            EbookFormat.Unsupported -> false
        }
        return ebookAvailable ||
            audiobookDownloadRepository.isDownloaded(item.sourceId, item.id) ||
            bundleAudiobookSource.isAvailableOffline(item.sourceId, item.id)
    }
}
