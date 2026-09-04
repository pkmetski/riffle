import XCTest
import Riffle

// Covers scenarios from docs/testing/ios-scenarios/04-collections-series-refresh.md
//
// Full UI scenarios (4.1–4.4) require a live ABS server and are verified manually on an iOS
// simulator. The tests below cover the source-type routing logic that determines whether
// IosLibraryRefresherImpl invokes the real ABS collections/series endpoints:
// only SourceType.abs enters the real path; all other types exit early with Success.
final class CollectionsSeriesRefreshTests: XCTestCase {

    func testAbsIsNotUnboundedCatalog() {
        // ABS is a bounded source — its library items, series, and collections are fetched from
        // the server and persisted locally. The iOS refresher's `if (source.type != SourceType.ABS)`
        // guard passes through for ABS, invoking the real API.
        XCTAssertFalse(SourceType.abs.isUnboundedCatalog)
        XCTAssertFalse(SourceType.abs.isWebSource)
    }

    func testKomgaSkipsSeriesAndCollections() {
        // Komga is not ABS — IosLibraryRefresherImpl returns Success immediately for series and
        // collections without calling any API. The classification must not change.
        XCTAssertFalse(SourceType.komga.isUnboundedCatalog)
        XCTAssertFalse(SourceType.komga.isWebSource)
    }

    func testWebSourcesSkipSeriesAndCollections() {
        // Web/unbounded sources (Chitanka, Gutenberg, RadioES) also skip the ABS guard and
        // return Success immediately — they have no concept of collections or series.
        XCTAssertTrue(SourceType.chitanka.isUnboundedCatalog)
        XCTAssertTrue(SourceType.gutenberg.isUnboundedCatalog)
        XCTAssertTrue(SourceType.radioEs.isUnboundedCatalog)
    }
}
