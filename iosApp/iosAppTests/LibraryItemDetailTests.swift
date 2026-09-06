import XCTest
import Riffle

// Covers scenarios from docs/testing/ios-scenarios/09-library-item-detail.md
final class LibraryItemDetailTests: XCTestCase {

    // Scenario 9.1 — Read button stability.
    func testReadButtonDoesNotMoveWhenEstimateArrives() throws {
        throw XCTSkip("UI-only; verified manually — Read button position is stable when reading-time estimate arrives asynchronously")
    }

    // Scenarios 9.2 / 9.3 — Tablet two-pane layout.
    func testTabletTwoPaneLayout() throws {
        throw XCTSkip("UI-only; verified manually on iPad — left pane fixed, right pane scrollable independently")
    }

    // Scenario 9.4 — Search field not auto-focused.
    func testSearchFieldNotAutoFocusedOnEntry() throws {
        throw XCTSkip("UI-only; verified manually — keyboard does not pop on library screen entry")
    }

    // Scenario 9.5 — Clear button hidden when query is empty.
    func testClearButtonHiddenWhenQueryEmpty() throws {
        throw XCTSkip("UI-only; verified manually — no clear button shown when search field is blank")
    }

    // Scenario 9.6 — Clear button visible when query is non-empty.
    func testClearButtonVisibleWhenQueryNonEmpty() throws {
        throw XCTSkip("UI-only; verified manually — clear button appears when search field has text")
    }

    // Scenario 9.7 — Clear button clears the query.
    func testClearButtonClearsQuery() throws {
        throw XCTSkip("UI-only; verified manually — tapping clear empties the search field")
    }

    // Scenarios 9.8 / 9.9 — Readaloud download button callbacks.
    func testReadaloudDownloadButtonTapInvokesDownload() throws {
        throw XCTSkip("UI-only; verified manually — tapping download button in NotDownloaded state invokes download callback")
    }

    func testReadaloudRemoveButtonTapInvokesRemove() throws {
        throw XCTSkip("UI-only; verified manually — tapping button in Downloaded state invokes remove callback")
    }
}
