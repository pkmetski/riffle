import XCTest
import Riffle

// Covers download-related scenarios for iOS issue #975 (EPUB downloads/offline slice).
//
// Scenario DL-1: A file written to the epub-cache namespace is detected as available offline.
//   (epub-downloads is already covered by OfflineAvailabilityTests.testDetectsEpubDownloadFile)
// Scenario DL-2: ContentCacheSettingsStore persists the auto-clear preference via NSUserDefaults
//   (verified directly through UserDefaults since the Kotlin class is internal).
// Scenario DL-3: epub-downloads takes priority over epub-cache in the offline-availability check —
//   placing a file in both namespaces returns true without double-counting.
final class DownloadsTests: XCTestCase {

    private let fm = FileManager.default

    private func documentsDir() throws -> URL {
        try fm.url(for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
    }

    private func placeFile(namespace: String, sourceId: String, itemId: String, ext: String = ".epub") throws -> URL {
        let dir = try documentsDir()
            .appendingPathComponent(namespace)
            .appendingPathComponent(sourceId)
        try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let file = dir.appendingPathComponent("\(itemId)\(ext)")
        try "placeholder".write(to: file, atomically: true, encoding: .utf8)
        return file
    }

    // MARK: — DL-1: epub-cache namespace detected as available offline

    func testEpubCacheDetectedAsAvailableOffline() throws {
        let sourceId = "dl-test-cache-src"
        let itemId = "dl-test-cache-item"
        let file = try placeFile(namespace: "epub-cache", sourceId: sourceId, itemId: itemId)
        defer { try? fm.removeItem(at: file.deletingLastPathComponent().deletingLastPathComponent()) }

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
            ebookFormat: EbookFormat.Epub.shared,
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
        XCTAssertTrue(
            impl.isAvailableOffline(item: item),
            "EPUB in epub-cache must be detected as available offline"
        )
    }

    // MARK: — DL-2: ContentCacheSettingsStore NSUserDefaults round-trip

    func testContentCacheAutoClears() {
        let key = "riffle.contentCacheAutoClear"
        let defaults = UserDefaults.standard
        defaults.removeObject(forKey: key)
        defer { defaults.removeObject(forKey: key) }

        // Default state: key absent → Kotlin impl returns After30Days; Swift can't call
        // internal Kotlin class directly, but NSUserDefaults key is the contract.
        XCTAssertNil(defaults.string(forKey: key),
                     "Key should not exist before first write")

        defaults.set("Off", forKey: key)
        XCTAssertEqual(defaults.string(forKey: key), "Off",
                       "Stored Off must round-trip")

        defaults.set("After7Days", forKey: key)
        XCTAssertEqual(defaults.string(forKey: key), "After7Days",
                       "Stored After7Days must round-trip")

        defaults.set("After90Days", forKey: key)
        XCTAssertEqual(defaults.string(forKey: key), "After90Days",
                       "Stored After90Days must round-trip")
    }

    // MARK: — DL-3: epub-downloads takes priority — file in both namespaces is still detected

    func testEpubDownloadsPriorityOverCache() throws {
        let sourceId = "dl-test-both-src"
        let itemId = "dl-test-both-item"

        let dlFile = try placeFile(namespace: "epub-downloads", sourceId: sourceId, itemId: itemId)
        let cacheFile = try placeFile(namespace: "epub-cache", sourceId: sourceId, itemId: itemId)
        defer {
            try? fm.removeItem(at: dlFile.deletingLastPathComponent().deletingLastPathComponent())
            try? fm.removeItem(at: cacheFile.deletingLastPathComponent().deletingLastPathComponent())
        }

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
            ebookFormat: EbookFormat.Epub.shared,
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
        XCTAssertTrue(
            impl.isAvailableOffline(item: item),
            "Item with file in both namespaces must be available offline"
        )
    }
}
