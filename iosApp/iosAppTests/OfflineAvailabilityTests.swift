import XCTest
import Riffle

// Covers scenarios from docs/testing/ios-scenarios/4-offline-availability.md
//
// Scenarios 4.1–4.4 require a running ABS instance or simulator file injection and must be
// verified manually. The tests below pin the filesystem path convention defined by
// IosLibraryItemOfflineAvailabilityImpl so that a future iOS download implementation
// uses consistent namespaces.
final class OfflineAvailabilityTests: XCTestCase {

    // Regression: if the EPUB downloads namespace changes, the offline filter silently
    // breaks for all previously-downloaded books — they remain on disk but are no longer
    // found by the availability check.
    func testEpubDownloadsNamespace() {
        XCTAssertEqual(IosLibraryItemOfflineAvailabilityImplKt.NS_EPUB_DOWNLOADS, "epub-downloads")
    }

    func testEpubCacheNamespace() {
        XCTAssertEqual(IosLibraryItemOfflineAvailabilityImplKt.NS_EPUB_CACHE, "epub-cache")
    }

    func testPdfDownloadsNamespace() {
        XCTAssertEqual(IosLibraryItemOfflineAvailabilityImplKt.NS_PDF_DOWNLOADS, "pdf-downloads")
    }

    func testPdfCacheNamespace() {
        XCTAssertEqual(IosLibraryItemOfflineAvailabilityImplKt.NS_PDF_CACHE, "pdf-cache")
    }

    func testCbzDownloadsNamespace() {
        XCTAssertEqual(IosLibraryItemOfflineAvailabilityImplKt.NS_CBZ_DOWNLOADS, "cbz-downloads")
    }

    func testCbzCacheNamespace() {
        XCTAssertEqual(IosLibraryItemOfflineAvailabilityImplKt.NS_CBZ_CACHE, "cbz-cache")
    }

    func testAudiobookDownloadsNamespace() {
        XCTAssertEqual(IosLibraryItemOfflineAvailabilityImplKt.NS_AUDIOBOOK_DOWNLOADS, "audiobook-downloads")
    }

    // Integration: place a real file at the expected EPUB download path and verify the
    // implementation detects it as available offline.
    func testDetectsEpubDownloadFile() throws {
        let fm = FileManager.default
        let docs = try fm.url(
            for: .documentDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let sourceId = "test-source"
        let itemId = "test-item"
        let dir = docs
            .appendingPathComponent("epub-downloads")
            .appendingPathComponent(sourceId)
        try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let epub = dir.appendingPathComponent("\(itemId).epub")
        try "epub-placeholder".write(to: epub, atomically: true, encoding: .utf8)
        defer { try? fm.removeItem(at: docs.appendingPathComponent("epub-downloads")) }

        let impl = IosLibraryItemOfflineAvailabilityImpl(fileStore: IosFileStore())
        let item = LibraryItem(
            id: itemId,
            libraryId: "lib",
            title: "Test Book",
            author: "Author",
            coverUrl: nil,
            readingProgress: 0,
            isCached: false,
            isDownloaded: false,
            ebookFormat: EbookFormat.epub,
            ebookFileIno: nil,
            hasAudio: false,
            audioDurationSec: 0,
            description: nil,
            seriesName: nil,
            publishedYear: nil,
            genres: [],
            publisher: nil,
            language: nil,
            lastOpenedAt: nil,
            addedAt: nil,
            isbn: nil,
            asin: nil,
            sourceId: sourceId,
            pageCount: nil
        )
        XCTAssertTrue(impl.isAvailableOffline(item: item),
                      "Item with epub file at epub-downloads path must be available offline")
    }

    // Integration: item with no local files must NOT be available offline.
    func testNoFilesReturnsFalse() {
        let impl = IosLibraryItemOfflineAvailabilityImpl(fileStore: IosFileStore())
        let item = LibraryItem(
            id: "no-such-item",
            libraryId: "lib",
            title: "Ghost Book",
            author: "Author",
            coverUrl: nil,
            readingProgress: 0,
            isCached: false,
            isDownloaded: false,
            ebookFormat: EbookFormat.epub,
            ebookFileIno: nil,
            hasAudio: false,
            audioDurationSec: 0,
            description: nil,
            seriesName: nil,
            publishedYear: nil,
            genres: [],
            publisher: nil,
            language: nil,
            lastOpenedAt: nil,
            addedAt: nil,
            isbn: nil,
            asin: nil,
            sourceId: "no-such-source",
            pageCount: nil
        )
        XCTAssertFalse(impl.isAvailableOffline(item: item),
                       "Item with no local files must not be available offline")
    }
}
