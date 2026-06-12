package com.riffle.core.domain

/**
 * Decides whether a [LibraryItem] can be opened with no network — the single source of truth behind
 * the library's offline filtering. An item is available offline when its ebook is downloaded or
 * cached (EPUB/PDF), OR when its audiobook is downloaded. Audiobooks have a download-only tier (no
 * auto-cache), so the audio side is a plain `isDownloaded` check (ADR 0029).
 */
class LibraryItemOfflineAvailability(
    private val epubRepository: EpubRepository,
    private val pdfRepository: PdfRepository,
    private val audiobookDownloadRepository: AudiobookDownloadRepository,
) {
    fun isAvailableOffline(item: LibraryItem): Boolean {
        val ebookAvailable = when (item.ebookFormat) {
            EbookFormat.Epub ->
                epubRepository.isDownloaded(item.serverId, item.id) || epubRepository.isCached(item.serverId, item.id)
            EbookFormat.Pdf ->
                pdfRepository.isDownloaded(item.serverId, item.id) || pdfRepository.isCached(item.serverId, item.id)
            EbookFormat.Unsupported -> false
        }
        return ebookAvailable || audiobookDownloadRepository.isDownloaded(item.serverId, item.id)
    }
}
