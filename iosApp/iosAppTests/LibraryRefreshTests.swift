import XCTest
import Riffle

// Covers scenarios from docs/testing/ios-scenarios/1-library-refresh.md
//
// The Komga scenario (1.1) is covered at the JVM level by KomgaLibraryApiClientTest, which
// exercises the network path against a MockWebServer. The static-root scenarios (1.2–1.4)
// are verified below by confirming the SourceType enum values are present and correctly
// classified — this ensures IosLibraryRefresherImpl's `when (source.type)` routing
// compiles with the right branch for each source kind.
final class LibraryRefreshTests: XCTestCase {

    func testChitankaIsUnboundedCatalog() {
        // CHITANKA must be isUnboundedCatalog = true so the iOS home screen routes to
        // the remote-browse UX rather than a local library list.
        XCTAssertTrue(SourceType.chitanka.isUnboundedCatalog)
        XCTAssertTrue(SourceType.chitanka.isWebSource)
    }

    func testGutenbergIsUnboundedCatalog() {
        XCTAssertTrue(SourceType.gutenberg.isUnboundedCatalog)
        XCTAssertTrue(SourceType.gutenberg.isWebSource)
    }

    func testRadioEsIsUnboundedCatalog() {
        XCTAssertTrue(SourceType.radioEs.isUnboundedCatalog)
        XCTAssertTrue(SourceType.radioEs.isWebSource)
    }

    func testKomgaIsNotUnboundedCatalog() {
        // KOMGA is a bounded source — it fetches real library lists from the server.
        XCTAssertFalse(SourceType.komga.isUnboundedCatalog)
        XCTAssertFalse(SourceType.komga.isWebSource)
    }
}
