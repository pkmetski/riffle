import XCTest

// Validates that the selection action strip (Copy / Look Up / Search / Share / Annotate) appears
// when the user selects text in the EPUB reader and that "Annotate" is present and tappable.
// Counterpart of Android's EpubReaderViewModel selection action mode tests.
final class SelectionAnnotationTests: AbsHarnessTestCase {

    // MARK: - Scenario: Annotate button appears in the selection strip on text selection

    func testAnnotateButtonAppearsOnTextSelection() throws {
        let tile = app.buttons[StubAbsServer.testItemTitle].firstMatch
        XCTAssertTrue(tile.waitForExistence(timeout: 30),
                      "Test EPUB tile must appear in the library home")

        openReader(from: tile, in: app)
        waitForReaderContentLoaded()

        let annotateButton = triggerTextSelectionInReader()
        XCTAssertTrue(
            annotateButton.waitForExistence(timeout: 10),
            "SelectionActionStrip must show an 'Annotate' button when text is selected"
        )
        XCTAssertTrue(annotateButton.isHittable,
                      "Annotate button must be hittable (not clipped by row overflow)")
    }

    // MARK: - Scenario: Tapping Annotate opens the annotation colour sheet

    func testTappingAnnotateOpensColourSheet() throws {
        let tile = app.buttons[StubAbsServer.testItemTitle].firstMatch
        XCTAssertTrue(tile.waitForExistence(timeout: 30))

        openReader(from: tile, in: app)
        waitForReaderContentLoaded()

        let annotateButton = triggerTextSelectionInReader()
        XCTAssertTrue(annotateButton.waitForExistence(timeout: 10),
                      "Annotate button must appear after text selection")

        annotateButton.tap()

        // After tapping Annotate, the AnnotationActionsSheet opens.
        // It contains colour swatches — the "Yellow" button (or any highlight colour) marks its presence.
        let colourSwatch = app.buttons.matching(
            NSPredicate(format: "label CONTAINS[c] 'Yellow' OR label CONTAINS[c] 'Green' OR label CONTAINS[c] 'highlight'")
        ).firstMatch
        XCTAssertTrue(
            colourSwatch.waitForExistence(timeout: 5),
            "AnnotationActionsSheet (colour swatches) must open after tapping Annotate"
        )
    }

    // MARK: - Helpers

    // Waits for the EPUB reader content to be fully loaded. openReader() returns as soon as the
    // Compose back button appears, but the reader shows a loading spinner (READER_LOADING) while
    // the EPUB downloads and Readium initialises. The UIKitViewController with Readium's WKWebView
    // is not added to the Compose tree until localPath is set, so app.webViews queries return empty
    // until then. Waiting for the loading spinner to disappear is the correct signal — the same
    // technique used by ProgressPipelineTests.
    private func waitForReaderContentLoaded() {
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 60)
    }

    // Long-presses in the EPUB reader to trigger Readium's text-selection pipeline. Uses
    // app.coordinate() rather than webView.coordinate() because Readium's WKWebView is embedded
    // inside a UIKitViewController and may not be reachable via app.webViews in the accessibility
    // tree — but XCUITest can still deliver gestures to raw screen coordinates. Retries across
    // three y-offsets so the press lands on rendered body text even if the first paragraph is
    // pushed down by a title block. Returns the Annotate button element (caller asserts existence).
    private func triggerTextSelectionInReader() -> XCUIElement {
        let annotateButton = app.buttons["Annotate"].firstMatch
        let yOffsets: [CGFloat] = [0.35, 0.50, 0.65]
        for yOffset in yOffsets {
            let textArea = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: yOffset))
            textArea.press(forDuration: 1.5)
            if annotateButton.waitForExistence(timeout: 5) { break }
        }
        return annotateButton
    }
}
