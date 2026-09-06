import XCTest
import Riffle

// Covers scenarios from docs/testing/ios-scenarios/08-library-cover-grid.md
final class LibraryCoverGridTests: XCTestCase {

    // Scenario 8.7 / 8.8 — HighlightColor enum values drive the readaloud badge tinting logic
    // that is also used by BookCoverTile. All four values must be present and distinct.
    func testHighlightColorValuesDistinct() {
        let yellow = HighlightColor.yellow
        let green = HighlightColor.green
        let blue = HighlightColor.blue
        let red = HighlightColor.red
        // Each colour has a unique ARGB value baked in; no two should be the same.
        XCTAssertNotEqual(yellow.argb, green.argb)
        XCTAssertNotEqual(yellow.argb, blue.argb)
        XCTAssertNotEqual(yellow.argb, red.argb)
        XCTAssertNotEqual(green.argb, blue.argb)
        XCTAssertNotEqual(green.argb, red.argb)
        XCTAssertNotEqual(blue.argb, red.argb)
    }

    // Scenario 8.7 — The token persisted in AnnotationEntity.color is lowercase.
    func testHighlightColorTokensAreLowercase() {
        XCTAssertEqual(HighlightColor.yellow.token, "yellow")
        XCTAssertEqual(HighlightColor.green.token, "green")
        XCTAssertEqual(HighlightColor.blue.token, "blue")
        XCTAssertEqual(HighlightColor.red.token, "red")
    }
}
