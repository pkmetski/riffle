import XCTest
import Riffle

// Counterpart to shared/src/commonTest/kotlin/com/riffle/shared/library/LibraryTabLogicTest.kt
//
// Verifies the LibraryTabVisibility value type exported from the Riffle framework and the
// isTabVisible / shouldClampSelectedTab logic that the iOS tab bar depends on. The commonTest
// suite exercises every branch of these functions on both JVM and K/N; this file pins the
// Swift-callable signatures and the sentinel boundary values that the iOS host reads directly.
final class LibraryTabVisibilityTests: XCTestCase {

    private var empty: LibraryTabVisibility { LibraryTabVisibility.companion.Empty }
    private var all: LibraryTabVisibility { LibraryTabVisibility.companion.All }

    // MARK: — Companion sentinels

    func testEmptyHidesAllOptionalTabs() {
        XCTAssertFalse(empty.toRead, "Empty sentinel must have toRead=false")
        XCTAssertFalse(empty.series, "Empty sentinel must have series=false")
        XCTAssertFalse(empty.collections, "Empty sentinel must have collections=false")
        XCTAssertFalse(empty.annotations, "Empty sentinel must have annotations=false")
        XCTAssertFalse(empty.playlists, "Empty sentinel must have playlists=false")
    }

    func testAllShowsAllOptionalTabs() {
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

    // MARK: — isTabVisible behavioural coverage (mirrors LibraryTabLogicTest in commonTest)
    //
    // The home tab (0) and all-books tab (5) are always visible regardless of the visibility
    // flags; optional tabs 1–4 and 6 follow their flag. A regression that hardcodes `true`
    // for all tabs would break the UI by showing tabs for empty libraries.

    func testHomeTabAlwaysVisibleEvenWithEmptyVisibility() {
        XCTAssertTrue(LibraryTabsKt.isTabVisible(selectedTab: 0, visibility: empty),
                      "Home tab must always be visible")
    }

    func testAllBooksTabAlwaysVisibleEvenWithEmptyVisibility() {
        XCTAssertTrue(LibraryTabsKt.isTabVisible(selectedTab: 5, visibility: empty),
                      "All-books tab must always be visible")
    }

    func testToReadTabHiddenWhenFlagFalse() {
        XCTAssertFalse(LibraryTabsKt.isTabVisible(selectedTab: 1, visibility: empty),
                       "To-read tab must be hidden when toRead=false")
    }

    func testToReadTabVisibleWhenFlagTrue() {
        XCTAssertTrue(LibraryTabsKt.isTabVisible(selectedTab: 1, visibility: all),
                      "To-read tab must be visible when toRead=true")
    }

    func testShouldClampWhenSelectedTabBecomesHidden() {
        // Series tab (3) becomes hidden: the selected tab must be clamped back to home.
        XCTAssertTrue(LibraryTabsKt.shouldClampSelectedTab(searchQuery: "", visibility: empty, selectedTab: 3),
                      "Should clamp when the selected tab is hidden and no search is active")
    }

    func testNoClampWhileSearching() {
        // Active search overrides tab visibility clamping.
        XCTAssertFalse(LibraryTabsKt.shouldClampSelectedTab(searchQuery: "kotlin", visibility: empty, selectedTab: 3),
                       "Should not clamp while a search is active")
    }
}
