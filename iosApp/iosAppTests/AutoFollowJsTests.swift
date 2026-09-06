import XCTest
import WebKit
import Riffle

// Covers scenarios from docs/testing/ios-scenarios/08-auto-follow-js.md
//
// Mirrors AutoFollowJsTest.kt: verifies ColumnSnap.autoFollowSnapJs() by injecting the
// generated JS into a real WKWebView and asserting the scroll/return behaviour.
// ColumnSnap is a shared KMP object (com.riffle.feature.reader), accessible from Swift as
// ColumnSnap.shared. The JS string is identical on Android and iOS; what differs is the
// WebView host (Android WebView vs WKWebView).

final class AutoFollowJsTests: XCTestCase {

    // MARK: - Fixture strings (mirror AutoFollowJsTest.kt)

    private let onPageText = "Onpage visible sentence to follow"
    private let offRightText = "Offright next page sentence here"
    private let targetText = "Narrated target sentence deep in the page"
    private let adjacentA = "Alpha narrated line on screen"
    private let adjacentB = "Bravo narrated line on screen"
    private let oldDateline = "LOG ENTRY: SOL 37"
    private let newDateline = "LOG ENTRY: SOL 38"
    private let splitSentence = "Once we got Hermes moving we coasted"

    // A document taller than the viewport → scroll (karaoke) mode.
    private var tallHtml: String {
        """
        <!DOCTYPE html>
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
          <body style="margin:0; height:6000px; position:relative">
            <div id="target" style="position:absolute; left:20px; top:3000px; width:300px; height:40px">\(targetText)</div>
          </body>
        </html>
        """
    }

    // A viewport-sized document → paginated mode. The on-page sentence is fully visible; the
    // off-page one sits far to the right.
    private var shortHtml: String {
        """
        <!DOCTYPE html>
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
          <body style="margin:0; height:40px; position:relative">
            <div id="onpage"   style="position:absolute; left:20px;   top:5px; width:240px; height:25px">\(onPageText)</div>
            <div id="offright" style="position:absolute; left:5000px; top:5px; width:240px; height:25px">\(offRightText)</div>
            <div id="spacer"   style="position:absolute; left:8000px; top:5px; width:240px; height:25px"></div>
          </body>
        </html>
        """
    }

    private var datelineHtml: String {
        """
        <!DOCTYPE html>
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
          <body style="margin:0; height:6000px; position:relative">
            <div id="dateline" style="position:absolute; left:20px; top:60px;  width:300px; height:40px">\(oldDateline)</div>
            <div id="prose"    style="position:absolute; left:20px; top:120px; width:300px; height:40px">The Hab is now a bomb.</div>
          </body>
        </html>
        """
    }

    private var splitNodeHtml: String {
        """
        <!DOCTYPE html>
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
          <body style="margin:0; height:6000px; position:relative">
            <div id="split" style="position:absolute; left:20px; top:3000px; width:320px; height:40px">Once we got <em>Hermes</em> moving we coasted, burning fuel.</div>
          </body>
        </html>
        """
    }

    private var twoAdjacentHtml: String {
        """
        <!DOCTYPE html>
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
          <body style="margin:0; height:6000px; position:relative">
            <div id="adjA" style="position:absolute; left:20px; top:2980px; width:320px; height:40px">\(adjacentA)</div>
            <div id="adjB" style="position:absolute; left:20px; top:3040px; width:320px; height:40px">\(adjacentB)</div>
          </body>
        </html>
        """
    }

    // MARK: - Scenario 08-A: scroll mode centres the narrated sentence

    func testScrollModeCentersTheNarratedSentence() throws {
        let webView = try loadedWebView(html: tallHtml, width: 390, height: 844)
        let result = evalSync(webView: webView, script: ColumnSnap.shared.autoFollowSnapJs(text: targetText))
        XCTAssertEqual("on", result?.trimmingQuotes, "sentence in an overflowing document → 'on'")

        let centerY = evalSync(webView: webView, script:
            "(function(){var r=document.getElementById('target').getBoundingClientRect();return Math.round((r.top+r.bottom)/2);})()"
        ).flatMap { Int($0.trimmingQuotes) } ?? -1
        let viewportH = evalSync(webView: webView, script: "window.innerHeight")
            .flatMap { Int($0.trimmingQuotes) } ?? 0
        XCTAssertGreaterThan(viewportH, 100, "viewport must have a real height")
        XCTAssertLessThanOrEqual(abs(centerY - viewportH / 2), 12,
            "sentence should be vertically centred (center=\(centerY), half=\(viewportH / 2))")
    }

    // MARK: - Scenario 08-B: scroll mode never scrolls horizontally

    func testScrollModeNeverScrollsHorizontally() throws {
        let webView = try loadedWebView(html: tallHtml, width: 390, height: 844)
        _ = evalSync(webView: webView, script: ColumnSnap.shared.autoFollowSnapJs(text: targetText))
        let scrollX = evalSync(webView: webView, script: "window.scrollX")
            .flatMap { Int($0.trimmingQuotes) } ?? -1
        XCTAssertEqual(0, scrollX, "scroll mode must not move the page sideways")
    }

    // MARK: - Scenario 08-C: paginated returns 'on' for a visible sentence

    func testPaginatedReturnsOnForVisibleSentence() throws {
        let webView = try loadedWebView(html: shortHtml, width: 390, height: 844)
        let result = evalSync(webView: webView, script: ColumnSnap.shared.autoFollowSnapJs(text: onPageText))
        XCTAssertEqual("on", result?.trimmingQuotes, "a fully visible sentence on the current page → 'on'")
    }

    // MARK: - Scenario 08-D: returns 'off' for text not on the page

    func testReturnsOffForTextNotOnPage() throws {
        let webView = try loadedWebView(html: shortHtml, width: 390, height: 844)
        let result = evalSync(webView: webView, script: ColumnSnap.shared.autoFollowSnapJs(text: "Zzz nonexistent sentence text"))
        XCTAssertEqual("off", result?.trimmingQuotes)
    }

    // MARK: - Scenario 08-E: empty text disables the probe

    func testEmptyTextDisablesTheProbe() throws {
        let webView = try loadedWebView(html: shortHtml, width: 390, height: 844)
        let result = evalSync(webView: webView, script: ColumnSnap.shared.autoFollowSnapJs(text: ""))
        XCTAssertEqual("off", result?.trimmingQuotes, "empty text → 'off'")
    }

    // MARK: - Scenario 08-F: dateline whole-sentence match prevents false match

    func testDatelineCollisionDoesNotFalseMatchPreviousChapter() throws {
        let webView = try loadedWebView(html: datelineHtml, width: 390, height: 844)
        let result = evalSync(webView: webView, script: ColumnSnap.shared.autoFollowSnapJs(text: newDateline))
        XCTAssertEqual("off", result?.trimmingQuotes,
            "a different chapter's dateline must NOT match this page → 'off'")
    }

    func testDatelinePresentOnItsOwnPageStillFollows() throws {
        let webView = try loadedWebView(html: datelineHtml, width: 390, height: 844)
        let result = evalSync(webView: webView, script: ColumnSnap.shared.autoFollowSnapJs(text: oldDateline))
        XCTAssertEqual("on", result?.trimmingQuotes,
            "the dateline that IS on the page is followed → 'on'")
    }

    // MARK: - Scenario 08-G: sentence split across inline markup nodes

    func testFollowsSentenceSplitAcrossInlineMarkupNodes() throws {
        let webView = try loadedWebView(html: splitNodeHtml, width: 390, height: 844)
        let result = evalSync(webView: webView, script: ColumnSnap.shared.autoFollowSnapJs(text: splitSentence))
        XCTAssertEqual("on", result?.trimmingQuotes,
            "a sentence split across inline-markup text nodes is still found → 'on'")
    }

    // MARK: - Helpers

    /// Creates a WKWebView sized to [width × height], loads [html] as a data URL, and blocks
    /// until the page has finished loading (up to 5 s).
    private func loadedWebView(html: String, width: CGFloat, height: CGFloat) throws -> WKWebView {
        let config = WKWebViewConfiguration()
        let webView = WKWebView(frame: CGRect(x: 0, y: 0, width: width, height: height), configuration: config)
        webView.scrollView.isScrollEnabled = true

        let exp = expectation(description: "page loaded")
        let nav = NavigationDelegate(exp: exp)
        webView.navigationDelegate = nav
        webView.loadHTMLString(html, baseURL: nil)
        wait(for: [exp], timeout: 5)
        _ = nav // keep alive
        return webView
    }

    /// Runs [script] synchronously on the main thread and returns the raw string result.
    private func evalSync(webView: WKWebView, script: String) -> String? {
        var result: String?
        let exp = expectation(description: "eval")
        DispatchQueue.main.async {
            webView.evaluateJavaScript(script) { value, _ in
                result = value.map { "\($0)" }
                exp.fulfill()
            }
        }
        wait(for: [exp], timeout: 5)
        return result
    }
}

// MARK: - WKNavigationDelegate helper

private class NavigationDelegate: NSObject, WKNavigationDelegate {
    private let exp: XCTestExpectation
    init(exp: XCTestExpectation) { self.exp = exp }
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) { exp.fulfill() }
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { exp.fulfill() }
}

// MARK: - String extension

private extension String {
    /// Strips the surrounding JSON quotes that WKWebView wraps around JS string results.
    var trimmingQuotes: String { trimmingCharacters(in: CharacterSet(charactersIn: "\"")) }
}
