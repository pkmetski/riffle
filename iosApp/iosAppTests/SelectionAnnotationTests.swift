import XCTest

// Validates that the selection action strip (Copy / Look Up / Search / Share / Annotate) appears
// when the user selects text in the EPUB reader and that "Annotate" is present and tappable.
// Counterpart of Android's EpubReaderViewModel selection action mode tests.
final class SelectionAnnotationTests: AbsHarnessTestCase {

    // MARK: - Scenario: Annotate button appears in the selection strip on text selection

    func testAnnotateButtonAppearsOnTextSelection() throws {
        let tile = app.staticTexts[StubAbsServer.testItemTitle].firstMatch
        XCTAssertTrue(tile.waitForExistence(timeout: 30),
                      "Test EPUB tile must appear in the library home")

        openReader(from: tile, in: app)

        let webView = app.webViews.firstMatch
        XCTAssertTrue(webView.waitForExistence(timeout: 30),
                      "EPUB WebView must appear after opening the reader")

        // Give Readium's page-load pipeline time to render actual text before we try to select.
        _ = XCTWaiter.wait(for: [XCTNSNotificationExpectation(name: .init("does-not-fire"))],
                            timeout: 2)

        // Long-press near the top-centre of the WebView to land on body text. Readium fires
        // shouldShowMenuForSelection → selectionCallback → Kotlin selectionFlow → Compose recomposes
        // showing SelectionActionStrip, which now includes the Annotate button.
        let textArea = webView.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.25))
        textArea.press(forDuration: 1.5)

        let annotateButton = app.buttons["Annotate"].firstMatch
        XCTAssertTrue(
            annotateButton.waitForExistence(timeout: 10),
            "SelectionActionStrip must show an 'Annotate' button when text is selected"
        )
        XCTAssertTrue(annotateButton.isHittable,
                      "Annotate button must be hittable (not clipped by row overflow)")
    }

    // MARK: - Scenario: Tapping Annotate opens the annotation colour sheet

    func testTappingAnnotateOpensColourSheet() throws {
        let tile = app.staticTexts[StubAbsServer.testItemTitle].firstMatch
        XCTAssertTrue(tile.waitForExistence(timeout: 30))

        openReader(from: tile, in: app)

        let webView = app.webViews.firstMatch
        XCTAssertTrue(webView.waitForExistence(timeout: 30))

        _ = XCTWaiter.wait(for: [XCTNSNotificationExpectation(name: .init("does-not-fire"))],
                            timeout: 2)

        let textArea = webView.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.25))
        textArea.press(forDuration: 1.5)

        let annotateButton = app.buttons["Annotate"].firstMatch
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
}
