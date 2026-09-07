import XCTest
import Riffle

// Covers scenarios from docs/testing/ios-scenarios/13-reader-highlights.md
final class ReaderHighlightsTests: XCTestCase {

    // Scenario 13.5 — HighlightColor.yellow is the default cadence colour and the pre-selected
    // colour in the highlight popup. The note-only popup mode is reached only when
    // annotation.type == AnnotationEntity.TYPE_IMAGE; the popup still reads the highlight colour
    // from the same HighlightColor enum, so if the enum is missing or renamed the note-only branch
    // silently falls back to an incorrect colour.
    func testDefaultHighlightColorIsYellow() {
        XCTAssertEqual(FormattingPreferences.companion.defaults().cadenceHighlightColor, HighlightColor.yellow)
    }

    // Scenario 13.1–13.7 — HighlightColor token strings are the exact values persisted in
    // AnnotationEntity.color and sent to Readium as decoration identifiers. A token mismatch
    // silently produces a decoration that never matches any persisted annotation.
    func testHighlightColorTokensMatchPersistedValues() {
        XCTAssertEqual(HighlightColor.yellow.token, "yellow")
        XCTAssertEqual(HighlightColor.green.token, "green")
        XCTAssertEqual(HighlightColor.blue.token, "blue")
        XCTAssertEqual(HighlightColor.red.token, "red")
    }
}
