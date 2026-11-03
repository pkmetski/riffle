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

        let webView = app.webViews.firstMatch
        XCTAssertTrue(webView.waitForExistence(timeout: 30),
                      "EPUB WebView must appear after opening the reader")

        let annotateButton = triggerTextSelectionInReader(webView: webView)
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

        let webView = app.webViews.firstMatch
        XCTAssertTrue(webView.waitForExistence(timeout: 30))

        let annotateButton = triggerTextSelectionInReader(webView: webView)
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

    // Long-presses in the EPUB WebView to trigger Readium's text-selection pipeline. Retries up to
    // three times across candidate y-positions so the press always lands on rendered body text even
    // when the EPUB layout shifts the first paragraph. Returns the Annotate button element (caller
    // must assert waitForExistence).
    private func triggerTextSelectionInReader(webView: XCUIElement) -> XCUIElement {
        // 8 s covers Readium's full page-load pipeline (EPUB fetch → parse → CSS inject → render)
        // on a loaded CI runner. 2 s was not enough and produced intermittent "Annotate not found"
        // failures when the long-press landed before body text was painted.
        _ = XCTWaiter.wait(for: [XCTNSNotificationExpectation(name: .init("does-not-fire"))],
                           timeout: 8)

        // Long-press near the top-centre of the WebView to land on body text. Readium fires
        // shouldShowMenuForSelection → selectionCallback → Kotlin selectionFlow → Compose recomposes
        // showing SelectionActionStrip, which now includes the Annotate button. Try three y-offsets
        // so a chapter whose first paragraph is pushed down by a title block doesn't break the test.
        let annotateButton = app.buttons["Annotate"].firstMatch
        let yOffsets: [CGFloat] = [0.25, 0.40, 0.55]
        for yOffset in yOffsets {
            let textArea = webView.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: yOffset))
            textArea.press(forDuration: 1.5)
            if annotateButton.waitForExistence(timeout: 5) { break }
        }
        return annotateButton
    }
}
