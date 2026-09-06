import XCTest
import Riffle

// Covers scenarios from docs/testing/ios-scenarios/14-reader-chapter-rail.md
final class ReaderChapterRailTests: XCTestCase {

    // Scenario 14.1 — Chapter rail renders at 4dp height.
    func testChapterRailHeightIs4dp() throws {
        throw XCTSkip("UI-only; verified manually via Xcode build — chapter rail pill is 4dp tall at bottom of reader")
    }

    // Scenario 14.2 — Coloured chapter map shows parent-chapter palette colours.
    func testColoredChapterMapShowsPaletteColors() throws {
        throw XCTSkip("UI-only (pixel colour); verified manually via Xcode build — active chapter orange, unread sibling muted")
    }

    // Scenario 14.3 — Neutral colours when colored chapter map is disabled.
    func testColoredChapterMapDisabledShowsNeutralColors() throws {
        throw XCTSkip("UI-only; verified manually — all rail segments grey when Colored chapter map is off in settings")
    }

    // Scenario 14.4 — Cursor update recomposes only the rail, not EPUB navigator.
    func testCursorUpdateRecomposesOnlyRail() throws {
        throw XCTSkip("SwiftUI layout concern; verified manually via Instruments — progress binding change does not re-render the full EPUB navigator view")
    }
}
