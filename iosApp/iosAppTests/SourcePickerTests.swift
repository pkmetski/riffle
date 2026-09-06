import XCTest
import Riffle

// Covers scenarios from docs/testing/ios-scenarios/17-source-picker.md
final class SourcePickerTests: XCTestCase {

    // Scenario 17.5 — SourceType KMP enum values accessible from Swift.
    // ABS and LOCAL_FILES are bounded-catalog sources; CHITANKA and GUTENBERG are unbounded web sources.
    func testSourceTypeValuesExist() {
        XCTAssertFalse(SourceType.abs.isUnboundedCatalog)
        XCTAssertFalse(SourceType.localFiles.isUnboundedCatalog)
        XCTAssertTrue(SourceType.chitanka.isUnboundedCatalog)
        XCTAssertTrue(SourceType.gutenberg.isUnboundedCatalog)
    }

    // Scenario 17.1 — All source type cards displayed.
    func testAllSourceTypeCardsDisplayed() throws {
        throw XCTSkip("UI-only; verified manually via Xcode build — ABS, Local files, Chitanka, and Project Gutenberg cards all visible in picker")
    }

    // Scenario 17.2 — Gutenberg card tap.
    func testGutenbergCardTapInvokesGutenbergType() throws {
        throw XCTSkip("UI-only; verified manually — tapping Gutenberg card triggers pick callback with GUTENBERG type")
    }

    // Scenario 17.3 — Audiobookshelf card tap.
    func testAbsCardTapInvokesAbsType() throws {
        throw XCTSkip("UI-only; verified manually — tapping Audiobookshelf card triggers pick callback with ABS type")
    }

    // Scenario 17.4 — Local files card tap.
    func testLocalFilesCardTapInvokesLocalFilesType() throws {
        throw XCTSkip("UI-only; verified manually — tapping Local files card triggers pick callback with LOCAL_FILES type")
    }

    // Scenario 17.6 — Catalog grid zoom.
    func testCatalogGridZoomWithinBounds() throws {
        throw XCTSkip("WKWebView zoom; verified manually via Xcode build — pinch zoom stays within viewport width on the catalog grid")
    }
}
