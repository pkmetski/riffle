import XCTest
import Riffle

/// iOS counterparts to the Android cover-grid instrumentation/unit tests:
///
///  - `AdaptiveCoverGridTest`         — the grid indexes its adaptive cell on the window width
///  - `SectionPreviewCountTest`       — column/preview arithmetic and the no-orphan rule
///  - `BookSectionGridTest`           — when the "see more" tile appears and what it counts
///  - `SeriesDetailGridTest`          — the compact `#N` series position badge
///
/// All four behaviours were Android-only until `CoverGridLayout` moved into
/// `feature:library/commonMain`. The Compose-Multiplatform grids the iOS app renders
/// (`shared/.../library/LibraryItemsScreen.kt`, `SeriesDetailScreen.kt`,
/// `CollectionDetailScreen.kt`, `LibrarySectionScreen.kt`) now call into it — before that they
/// were pinned to a hard-coded `GridCells.Adaptive(120.dp)` with no size-class breakpoint, no
/// pinch clamp and no series badge. These tests assert the numbers those grids read.
final class LibraryCoverGridTests: XCTestCase {

    private let layout = CoverGridLayout.shared

    /// Content width for a canonical phone: 412dp screen − 24dp of horizontal grid padding.
    private let phoneContentWidth: Float = 388
    /// Content width at the Expanded breakpoint: 840dp screen − 24dp of horizontal grid padding.
    private let tabletContentWidth: Float = 816

    // MARK: - AdaptiveCoverGridTest: the cell size is indexed on the window width

    /// A window at or past the Expanded breakpoint must select a bigger cell than a phone window.
    /// If the breakpoint comparison regressed (or the iOS grid went back to a fixed cell), both
    /// sides would return the same number and this flips red.
    func testExpandedWindowSelectsLargerMinCellThanPhone() {
        let phone = layout.minCellSizeDp(windowWidthDp: 412, scale: 1)
        let tablet = layout.minCellSizeDp(windowWidthDp: 840, scale: 1)
        XCTAssertEqual(phone, layout.PHONE_MIN_CELL_DP, accuracy: 0.001)
        XCTAssertEqual(tablet, layout.EXPANDED_MIN_CELL_DP, accuracy: 0.001)
        XCTAssertGreaterThan(tablet, phone, "Expanded windows must get a larger adaptive cell")
    }

    /// The breakpoint is inclusive: exactly 840dp is already Expanded, one dp below is not.
    func testBreakpointIsInclusiveAtEightHundredForty() {
        let atBreakpoint = layout.minCellSizeDp(windowWidthDp: layout.EXPANDED_WIDTH_BREAKPOINT_DP, scale: 1)
        let justBelow = layout.minCellSizeDp(windowWidthDp: layout.EXPANDED_WIDTH_BREAKPOINT_DP - 1, scale: 1)
        XCTAssertEqual(atBreakpoint, layout.EXPANDED_MIN_CELL_DP, accuracy: 0.001)
        XCTAssertEqual(justBelow, layout.PHONE_MIN_CELL_DP, accuracy: 0.001)
    }

    /// Android's `AdaptiveCoverGridTest` asserts ≥ 4 columns on the tablet AVD because the phone
    /// fallback caps at 3. Same claim, expressed on the arithmetic the iOS grid feeds to
    /// `GridCells.Adaptive`.
    func testExpandedYieldsAtLeastFourColumnsWherePhoneYieldsThree() {
        let phoneColumns = layout.columns(
            availableWidthDp: phoneContentWidth,
            minCellDp: layout.minCellSizeDp(windowWidthDp: 412, scale: 1),
            spacingDp: layout.GRID_SPACING_DP
        )
        let tabletColumns = layout.columns(
            availableWidthDp: tabletContentWidth,
            minCellDp: layout.minCellSizeDp(windowWidthDp: 840, scale: 1),
            spacingDp: layout.GRID_SPACING_DP
        )
        XCTAssertEqual(phoneColumns, 3, "412dp phone must lay out 3 columns")
        XCTAssertGreaterThanOrEqual(tabletColumns, 4, "Expanded must lay out ≥ 4 columns, got \(tabletColumns)")
    }

    /// The denser home-shelf grid packs tighter than the full-page grid on an Expanded window
    /// (~5-6 covers per row instead of ~4), so a wide screen isn't dominated by huge covers.
    func testShelfGridPacksTighterThanFullPageGridOnExpanded() {
        let shelfColumns = layout.columns(
            availableWidthDp: tabletContentWidth,
            minCellDp: layout.shelfMinCellSizeDp(windowWidthDp: 840, scale: 1),
            spacingDp: layout.GRID_SPACING_DP
        )
        let pageColumns = layout.columns(
            availableWidthDp: tabletContentWidth,
            minCellDp: layout.minCellSizeDp(windowWidthDp: 840, scale: 1),
            spacingDp: layout.GRID_SPACING_DP
        )
        XCTAssertEqual(shelfColumns, 5, "816dp of content at a 140dp shelf cell is 5 columns")
        XCTAssertGreaterThan(shelfColumns, pageColumns)
    }

    /// A container narrower than one cell still renders a single column rather than zero.
    func testVeryNarrowContainerFallsBackToOneColumn() {
        XCTAssertEqual(
            layout.columns(availableWidthDp: 60, minCellDp: layout.PHONE_MIN_CELL_DP, spacingDp: layout.GRID_SPACING_DP),
            1
        )
    }

    // MARK: - Pinch-zoom clamp

    /// The persisted pinch multiplier is clamped both ways. Without the clamp a stored value from
    /// a runaway gesture would produce a cell size no grid can lay out.
    func testPinchScaleIsClampedToTheSupportedRange() {
        XCTAssertEqual(layout.clampScale(scale: 0.1), layout.MIN_COVER_SCALE, accuracy: 0.001)
        XCTAssertEqual(layout.clampScale(scale: 9), layout.MAX_COVER_SCALE, accuracy: 0.001)
        XCTAssertEqual(layout.clampScale(scale: 1), 1, accuracy: 0.001)
    }

    /// Zooming in grows the cell (fewer covers per row); zooming out shrinks it (more per row).
    func testMinCellSizeScalesWithThePinchMultiplier() {
        let zoomedIn = layout.minCellSizeDp(windowWidthDp: 412, scale: layout.MAX_COVER_SCALE)
        let zoomedOut = layout.minCellSizeDp(windowWidthDp: 412, scale: layout.MIN_COVER_SCALE)
        XCTAssertEqual(zoomedIn, layout.PHONE_MIN_CELL_DP * layout.MAX_COVER_SCALE, accuracy: 0.001)
        XCTAssertEqual(zoomedOut, layout.PHONE_MIN_CELL_DP * layout.MIN_COVER_SCALE, accuracy: 0.001)

        let inColumns = layout.columns(availableWidthDp: phoneContentWidth, minCellDp: zoomedIn, spacingDp: layout.GRID_SPACING_DP)
        let outColumns = layout.columns(availableWidthDp: phoneContentWidth, minCellDp: zoomedOut, spacingDp: layout.GRID_SPACING_DP)
        XCTAssertLessThan(inColumns, outColumns, "Zooming in must reduce the column count")
    }

    // MARK: - BookSectionGridTest / SectionPreviewCountTest: two-row preview

    func testSectionPreviewCountForCanonicalColumnCounts() {
        XCTAssertEqual(layout.sectionPreviewCount(columns: 3), 5, "phone: 3 columns → 5 covers + see-more")
        XCTAssertEqual(layout.sectionPreviewCount(columns: 5), 9, "tablet: 5 columns → 9 covers + see-more")
        XCTAssertEqual(layout.sectionPreviewCount(columns: 1), 1, "single column never previews zero covers")
    }

    /// The no-orphan invariant: preview + the see-more tile must fill whole rows for every column
    /// count, so the see-more tile can never end up alone on a third row.
    func testSeeMoreTileNeverOrphansOnItsOwnRow() {
        for columns in 1...10 {
            let slots = Int(layout.sectionPreviewCount(columns: Int32(columns))) + 1
            XCTAssertEqual(
                slots % columns, 0,
                "columns=\(columns): \(slots) slots must be a whole number of rows"
            )
        }
    }

    /// The see-more tile appears only once the section genuinely overflows its preview — an exact
    /// fit must not render one.
    func testSeeMoreAppearsOnlyWhenItemsExceedThePreview() {
        let preview = layout.sectionPreviewCount(columns: 3) // 5
        XCTAssertTrue(layout.shouldShowSeeMore(itemCount: 6, previewCount: preview))
        XCTAssertFalse(layout.shouldShowSeeMore(itemCount: 5, previewCount: preview), "an exact fit needs no see-more tile")
        XCTAssertFalse(layout.shouldShowSeeMore(itemCount: 1, previewCount: preview))
    }

    /// A 20-item section overflows at both ends of the pinch range, so the see-more affordance
    /// never silently disappears at a zoom extreme.
    func testSeeMoreSurvivesBothPinchExtremes() {
        for scale in [layout.MIN_COVER_SCALE, layout.MAX_COVER_SCALE] {
            let columns = layout.columns(
                availableWidthDp: phoneContentWidth,
                minCellDp: layout.minCellSizeDp(windowWidthDp: 412, scale: scale),
                spacingDp: layout.GRID_SPACING_DP
            )
            XCTAssertGreaterThanOrEqual(columns, 1)
            XCTAssertTrue(
                layout.shouldShowSeeMore(itemCount: 20, previewCount: layout.sectionPreviewCount(columns: columns)),
                "20 items must still overflow the preview at scale \(scale)"
            )
        }
    }

    // MARK: - SeriesDetailGridTest: compact series position badge

    /// The series detail app bar already names the series, so only the position goes on the cover.
    func testSeriesPositionBadgeKeepsOnlyTheSequence() {
        XCTAssertEqual(layout.seriesPositionBadge(seriesName: "The Expanse #4"), "#4")
        XCTAssertEqual(layout.seriesPositionBadge(seriesName: "The Expanse #2.5"), "#2.5")
        XCTAssertEqual(layout.seriesPositionBadge(seriesName: "Some #Odd Series #10"), "#10", "the last ' #' wins")
    }

    /// No sequence, no badge — an empty or missing suffix must not render a bare "#".
    func testSeriesPositionBadgeIsNilWithoutASequence() {
        XCTAssertNil(layout.seriesPositionBadge(seriesName: "The Expanse"))
        XCTAssertNil(layout.seriesPositionBadge(seriesName: nil))
        XCTAssertNil(layout.seriesPositionBadge(seriesName: "The Expanse # "), "whitespace-only sequence is not a position")
    }
}
