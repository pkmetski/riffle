import XCTest
import Riffle

// Counterpart to shared/src/commonTest/kotlin/com/riffle/shared/library/LibraryTabLogicTest.kt
//
// Verifies the LibraryTabVisibility data class exported from the Riffle framework. The tab-bar
// switching logic (isTabVisible, shouldClampSelectedTab) is internal Kotlin and exercised by the
// KMP commonTest suite on both JVM and K/N; this file exercises the exported value-type contract
// that iOS consumers depend on.
final class LibraryTabVisibilityTests: XCTestCase {

    // MARK: — Companion sentinels

    func testEmptyHidesAllOptionalTabs() {
        let empty = LibraryTabVisibility.companion.Empty
        XCTAssertFalse(empty.toRead, "Empty sentinel must have toRead=false")
        XCTAssertFalse(empty.series, "Empty sentinel must have series=false")
        XCTAssertFalse(empty.collections, "Empty sentinel must have collections=false")
        XCTAssertFalse(empty.annotations, "Empty sentinel must have annotations=false")
        XCTAssertFalse(empty.playlists, "Empty sentinel must have playlists=false")
    }

    func testAllShowsAllOptionalTabs() {
        let all = LibraryTabVisibility.companion.All
        XCTAssertTrue(all.toRead, "All sentinel must have toRead=true")
        XCTAssertTrue(all.series, "All sentinel must have series=true")
        XCTAssertTrue(all.collections, "All sentinel must have collections=true")
        XCTAssertTrue(all.annotations, "All sentinel must have annotations=true")
        XCTAssertTrue(all.playlists, "All sentinel must have playlists=true")
    }

    // MARK: — Value construction

    func testPartialVisibility() {
        let visibility = LibraryTabVisibility(
            toRead: true,
            series: false,
            collections: true,
            annotations: false,
            playlists: false
        )
        XCTAssertTrue(visibility.toRead)
        XCTAssertFalse(visibility.series)
        XCTAssertTrue(visibility.collections)
        XCTAssertFalse(visibility.annotations)
        XCTAssertFalse(visibility.playlists)
    }

    // MARK: — Equality

    func testEqualityMatchesComponentValues() {
        let lhsVisibility = LibraryTabVisibility(toRead: true, series: true, collections: false, annotations: true, playlists: false)
        let rhsVisibility = LibraryTabVisibility(toRead: true, series: true, collections: false, annotations: true, playlists: false)
        XCTAssertEqual(lhsVisibility, rhsVisibility)
    }

    func testInequalityOnDifferentValues() {
        let lhsVisibility = LibraryTabVisibility(toRead: true, series: false, collections: false, annotations: false, playlists: false)
        let rhsVisibility = LibraryTabVisibility(toRead: false, series: false, collections: false, annotations: false, playlists: false)
        XCTAssertNotEqual(lhsVisibility, rhsVisibility)
    }
}
