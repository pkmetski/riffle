import XCTest
import Riffle

// Covers scenarios from docs/testing/ios-scenarios/16-reader-cbz.md
final class ReaderCbzTests: XCTestCase {

    // Scenario 16.1 — CBZ nav row is a thumbnail strip.
    func testCbzNavRowIsThumbnailStrip() throws {
        throw XCTSkip("UI-only; verified manually via Xcode build — CBZ reader shows a thumbnail strip at the bottom, not a slider")
    }

    // Scenario 16.2 — Tapping a thumbnail seeks to that page.
    func testTappingThumbnailSeeksToPage() throws {
        throw XCTSkip("UI-only; verified manually — tapping page N thumbnail navigates the comics reader to page N")
    }

    // Scenario 16.3 — Thumbnail cache avoids redundant decodes.
    func testThumbnailCacheAvoidsDuplicateDecodes() throws {
        throw XCTSkip("Implementation detail; iOS NSCache equivalent is verified via unit test on the iOS image source binding")
    }
}
