import XCTest
import Riffle

// Unit tests for ReadiumPublicationInspector, in the iosAppUnitTests target so the bridge class
// and the bundled test.epub fixture are both available without needing XCUIApplication.
final class ReadiumPublicationInspectorTests: XCTestCase {

    private func bundledEpubPath(_ name: String) -> String {
        guard let url = Bundle(for: ReadiumPublicationInspectorTests.self).url(forResource: name, withExtension: nil) else {
            XCTFail("Missing test asset '\(name)' in test bundle")
            return ""
        }
        return url.path
    }

    func testInspectEpubReturnsNonEmptyTocAndPositiveTotalPositions() {
        let inspector = ReadiumPublicationInspector()
        let expectation = expectation(description: "inspectEpub result")
        var resultJson: String?

        inspector.inspectEpub(filePath: bundledEpubPath("test.epub")) { json in
            resultJson = json
            expectation.fulfill()
        }

        wait(for: [expectation], timeout: 10)

        guard let json = resultJson else {
            XCTFail("inspectEpub must return a result for a valid bundled EPUB")
            return
        }
        XCTAssertTrue(json.contains("\"tocJson\""), "result must contain a tocJson field")
        XCTAssertTrue(json.contains("\"totalPositions\""), "result must contain a totalPositions field")
        XCTAssertFalse(json.contains("\"totalPositions\":null"), "a real EPUB must report a non-null position count")
    }

    func testInspectEpubReturnsNilForAMissingFile() {
        let inspector = ReadiumPublicationInspector()
        let expectation = expectation(description: "inspectEpub result")
        var resultJson: String? = "not-yet-called"

        inspector.inspectEpub(filePath: "/nonexistent/path/does-not-exist.epub") { json in
            resultJson = json
            expectation.fulfill()
        }

        wait(for: [expectation], timeout: 10)

        XCTAssertNil(resultJson, "inspectEpub must return nil when the file cannot be opened")
    }

    func testExtractPublisherFontFaceCssReturnsCssStringForValidEpub() {
        let inspector = ReadiumPublicationInspector()
        let expectation = expectation(description: "extractPublisherFontFaceCss result")
        var resultCss: String?

        inspector.extractPublisherFontFaceCss(filePath: bundledEpubPath("test.epub")) { css in
            resultCss = css
            expectation.fulfill()
        }

        wait(for: [expectation], timeout: 15)

        XCTAssertNotNil(resultCss, "extractPublisherFontFaceCss must invoke the callback for a valid EPUB")
        // resultCss may be empty when the test EPUB has no font-face rules — that is acceptable.
    }

    func testExtractPublisherFontFaceCssReturnsEmptyStringForMissingFile() {
        let inspector = ReadiumPublicationInspector()
        let expectation = expectation(description: "extractPublisherFontFaceCss missing file")
        var resultCss: String?

        inspector.extractPublisherFontFaceCss(filePath: "/nonexistent/path/does-not-exist.epub") { css in
            resultCss = css
            expectation.fulfill()
        }

        wait(for: [expectation], timeout: 10)

        XCTAssertEqual("", resultCss, "extractPublisherFontFaceCss must return an empty string when the file cannot be opened")
    }
}
