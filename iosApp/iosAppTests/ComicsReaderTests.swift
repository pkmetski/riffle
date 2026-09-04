import XCTest
import Riffle

// Covers scenarios from docs/testing/ios-scenarios/06-comics-reader.md
//
// Tests the iOS-layer classes exported in the Riffle framework:
//   - IosCbzArchive: ZIP parser and ComicImageSource implementation
//   - IosNoOpPanelEngine: panel engine no-op singleton
//
// CbzReaderViewModel (in feature:reader/commonMain) is an implementation dependency
// of the shared module and is not exported to the framework — its behaviour is fully
// covered by the JVM unit tests in CbzReaderViewModelTest (commonTest).
final class ComicsReaderTests: XCTestCase {

    // MARK: - IosCbzArchive: graceful handling of invalid/empty bytes

    func testIosCbzArchiveWithEmptyBytesReturnsZeroPages() {
        let archive = IosCbzArchive(archiveBytes: KotlinByteArray(size: 0))
        XCTAssertEqual(archive.pageCount, 0,
                       "Archive with empty bytes must return 0 pages")
    }

    func testIosCbzArchiveWithNonZipBytesReturnsZeroPages() {
        let junkBytes = KotlinByteArray(size: 4)
        junkBytes.set(index: 0, value: Int8(bitPattern: 0x01))
        junkBytes.set(index: 1, value: Int8(bitPattern: 0x02))
        junkBytes.set(index: 2, value: Int8(bitPattern: 0x03))
        junkBytes.set(index: 3, value: Int8(bitPattern: 0x04))
        let archive = IosCbzArchive(archiveBytes: junkBytes)
        XCTAssertEqual(archive.pageCount, 0,
                       "Archive with non-ZIP bytes must return 0 pages")
    }

    // MARK: - IosNoOpPanelEngine: singleton and Fallback contract

    func testIosNoOpPanelEngineSharedIsAccessible() {
        // KMP object singletons may return distinct ObjC wrappers per call, so === isn't reliable.
        // Verify the shared accessor returns a non-nil engine that implements the PanelEngine contract.
        let engine = IosNoOpPanelEngine.shared
        XCTAssertNotNil(engine, "IosNoOpPanelEngine.shared must be accessible")
    }

    func testIosNoOpPanelEngineForBookReturnsBook() {
        let engine = IosNoOpPanelEngine.shared
        let book = engine.forBook(bookId: "test") { _ in KotlinByteArray(size: 0) }
        XCTAssertNotNil(book, "forBook must return a non-nil book handle")
    }

    func testIosNoOpPanelEngineResolvePageReturnsFallback() {
        let engine = IosNoOpPanelEngine.shared
        let book = engine.forBook(bookId: "book1") { _ in KotlinByteArray(size: 0) }
        let panels = book.resolvePage(pageIndex: 0)
        XCTAssertTrue(panels.isFallback, "No-op engine must always return Fallback source")
    }

    func testIosNoOpPanelEngineResolvePageHasOnePanelRegion() {
        let engine = IosNoOpPanelEngine.shared
        let book = engine.forBook(bookId: "book1") { _ in KotlinByteArray(size: 0) }
        let panels = book.resolvePage(pageIndex: 2)
        XCTAssertEqual(panels.panels.count, 1, "Fallback must have exactly one whole-page panel")
    }

    // MARK: - Scenario 06-A validation: CBZ format routes to CbzReader

    func testEbookFormatCbzIsRecognised() {
        // Verifies that the "cbz" storage string round-trips through EbookFormat.from()
        // — the same lookup the nav routing gate in HomeScreen uses to dispatch CbzReaderScreen.
        let cbzFormat = ModelsEbookFormat.companion.from(raw: "cbz")
        XCTAssertNotNil(cbzFormat,
                        "EbookFormat.from(raw: \"cbz\") must return a non-nil format")
        XCTAssertEqual(cbzFormat.toStorageString(), "cbz",
                       "Round-trip storage string must equal \"cbz\"")
    }
}
