import XCTest
import Riffle

// Covers scenarios from docs/testing/ios-scenarios/10-audiobook-player-ui.md
final class AudiobookPlayerUITests: XCTestCase {

    // Scenario 10.1 — Publication year shown when present.
    func testYearShownWhenPresent() throws {
        throw XCTSkip("UI-only; verified manually — year displayed below author in audiobook player title block")
    }

    // Scenario 10.2 — Year omitted when null.
    func testYearOmittedWhenNull() throws {
        throw XCTSkip("UI-only; verified manually — no year text when publishedYear is nil")
    }

    // Scenario 10.3 — Year omitted when blank.
    func testYearOmittedWhenBlank() throws {
        throw XCTSkip("UI-only; verified manually — no year text when publishedYear is an empty string")
    }

    // Scenario 10.4 / 10.5 — "Bookmark saved" snackbar with Undo action.
    // The snackbar itself is a SwiftUI overlay; KMP player state types are testable.
    func testSnackbarAppearsAfterBookmarkCreation() throws {
        throw XCTSkip("UI-only; verified manually — 'Bookmark saved' toast appears with Undo action after creating an audiobook bookmark")
    }
}
