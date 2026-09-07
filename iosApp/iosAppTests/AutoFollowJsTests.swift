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

    // MARK: - Scenario 08-B: scroll mode does not bounce between adjacent visible sentences

    func testScrollModeDoesNotBounceBetweenAdjacentVisibleSentences() throws {
        let webView = try loadedWebView(html: twoAdjacentHtml, width: 390, height: 844)
        // Centre sentence A first (so A is at mid-viewport and B is one line below it — both well
        // inside the central comfort band, whatever the device's CSS innerHeight works out to).
        let ih = evalDouble(webView: webView, script: "window.innerHeight") ?? 0
        XCTAssertGreaterThan(ih, 100, "viewport must have a real height")
        setScrollTop(webView: webView, to: Int(2980 + 20 - ih / 2))
        // Each scrollY read is settled, since WKWebView commits scroll offsets asynchronously
        // and an unsettled read would report a stale position rather than the real deviation.
        let y0 = settledInt(webView: webView, script: "window.scrollY") ?? -1

        // Simulate the active sentence flapping A → B → A (a backward position blip across the
        // clip boundary). Each follow must leave the page where it is — no re-centre, no bounce.
        _ = evalSync(webView: webView, script: ColumnSnap.shared.autoFollowSnapJs(text: adjacentA))
        let ya = settledInt(webView: webView, script: "window.scrollY") ?? -1
        _ = evalSync(webView: webView, script: ColumnSnap.shared.autoFollowSnapJs(text: adjacentB))
        let yb = settledInt(webView: webView, script: "window.scrollY") ?? -1
        _ = evalSync(webView: webView, script: ColumnSnap.shared.autoFollowSnapJs(text: adjacentA))
        let yc = settledInt(webView: webView, script: "window.scrollY") ?? -1

        let maxDev = max(abs(ya - y0), abs(yb - y0), abs(yc - y0))
        XCTAssertLessThanOrEqual(maxDev, 8,
            "auto-follow must not bounce the page when the active sentence flaps between two " +
            "already-visible adjacent sentences (y0=\(y0) ya=\(ya) yb=\(yb) yc=\(yc) maxDev=\(maxDev))")
    }

    // MARK: - Scenario 08-C: scroll mode never scrolls horizontally

    func testScrollModeNeverScrollsHorizontally() throws {
        let webView = try loadedWebView(html: tallHtml, width: 390, height: 844)
        _ = evalSync(webView: webView, script: ColumnSnap.shared.autoFollowSnapJs(text: targetText))
        assertSettles(webView: webView, script: "window.scrollX", to: 0,
                      "scroll mode must not move the page sideways")
    }

    // MARK: - Scenario 08-D: paginated returns 'on' for a visible sentence

    func testPaginatedReturnsOnForVisibleSentence() throws {
        let webView = try loadedWebView(html: shortHtml, width: 390, height: 844)
        let result = evalSync(webView: webView, script: ColumnSnap.shared.autoFollowSnapJs(text: onPageText))
        XCTAssertEqual("on", result?.trimmingQuotes, "a fully visible sentence on the current page → 'on'")
        assertSettles(webView: webView, script: "window.scrollX", to: 0,
                      "paginated mode must not scroll horizontally")
        assertSettles(webView: webView, script: "window.scrollY", to: 0,
                      "paginated mode must not scroll vertically")
    }

    // MARK: - Scenarios 08-E / 08-F: paginated snaps a visible sentence back onto the column grid

    func testPaginatedSnapsAVisibleSentenceToItsGridAlignedColumn() throws {
        let webView = try loadedWebView(html: shortHtml, width: 390, height: 844)
        // Drift the page a few px off the column grid with the sentence on the current page.
        // Following floors scrollLeft to the sentence's column, which lands flush on the grid —
        // so the page can never rest between two pages (the "shifted left, sliver of the next
        // page showing" readaloud bug).
        setScrollLeft(webView: webView, to: 10)
        let result = evalSync(webView: webView, script: ColumnSnap.shared.autoFollowSnapJs(text: onPageText))
        XCTAssertEqual("on", result?.trimmingQuotes, "following a visible sentence returns 'on'")
        assertSettles(webView: webView, script: "window.scrollX", to: 0,
                      "a visible sentence snaps to its grid-aligned column")
    }

    func testPaginatedFollowsToTheSentencesColumnSymmetrically() throws {
        let webView = try loadedWebView(html: shortHtml, width: 390, height: 844)
        // With the page scrolled off the grid, following floors to the column that contains the
        // sentence — the same column move it makes whichever way the reader had paged (no
        // asymmetric keep-visible window). Here that lands back on the grid at 0.
        setScrollLeft(webView: webView, to: 40)
        let result = evalSync(webView: webView, script: ColumnSnap.shared.autoFollowSnapJs(text: onPageText))
        XCTAssertEqual("on", result?.trimmingQuotes, "following the sentence returns 'on'")
        assertSettles(webView: webView, script: "window.scrollX", to: 0,
                      "follows to the sentence's grid-aligned column")
    }

    // MARK: - Scenario 08-G: paginated snaps to the column containing an off-page sentence

    func testPaginatedSnapsToTheColumnContainingTheSentence() throws {
        let webView = try loadedWebView(html: shortHtml, width: 390, height: 844)
        let iw = evalInt(webView: webView, script: "window.innerWidth") ?? 0
        XCTAssertGreaterThan(iw, 100, "viewport must have a real width")

        let result = evalSync(webView: webView, script: ColumnSnap.shared.autoFollowSnapJs(text: offRightText))
        XCTAssertEqual("on", result?.trimmingQuotes, "a sentence on another page is followed by snapping → 'on'")

        // The invariant: following an off-page sentence lands the page ON the column grid —
        // scrollLeft a whole multiple of innerWidth — so the page never rests between two
        // columns. The spacer in shortHtml keeps the target off the end-of-content clamp so the
        // snap reaches a clean column rather than clamping to a non-grid max.
        let sx = settledInt(webView: webView, script: "window.scrollX") ?? -1
        XCTAssertGreaterThan(sx, 0, "snapping a next-page sentence must move the page")
        XCTAssertEqual(0, sx % iw, "page must land on the column grid (scrollX=\(sx), iw=\(iw))")
    }

    // MARK: - Scenario 08-H: returns 'off' for text not on the page

    func testReturnsOffForTextNotOnPage() throws {
        let webView = try loadedWebView(html: shortHtml, width: 390, height: 844)
        let result = evalSync(webView: webView, script: ColumnSnap.shared.autoFollowSnapJs(text: "Zzz nonexistent sentence text"))
        XCTAssertEqual("off", result?.trimmingQuotes)
    }

    // MARK: - Scenario 08-I: empty text disables the probe

    func testEmptyTextDisablesTheProbe() throws {
        let webView = try loadedWebView(html: shortHtml, width: 390, height: 844)
        let result = evalSync(webView: webView, script: ColumnSnap.shared.autoFollowSnapJs(text: ""))
        XCTAssertEqual("off", result?.trimmingQuotes, "empty text → 'off'")
    }

    // MARK: - Scenarios 08-J / 08-K: dateline whole-sentence match prevents false match

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

    // MARK: - Scenario 08-L: sentence split across inline markup nodes

    func testFollowsSentenceSplitAcrossInlineMarkupNodes() throws {
        let webView = try loadedWebView(html: splitNodeHtml, width: 390, height: 844)
        let result = evalSync(webView: webView, script: ColumnSnap.shared.autoFollowSnapJs(text: splitSentence))
        XCTAssertEqual("on", result?.trimmingQuotes,
            "a sentence split across inline-markup text nodes is still found → 'on'")
    }

    // MARK: - Helpers

    /// Windows hosting the web views created during a test. Held for the test's lifetime and
    /// released in tearDown — see [loadedWebView] for why the hosting matters.
    private var hostWindows: [UIWindow] = []

    override func tearDown() {
        hostWindows.forEach { $0.isHidden = true }
        hostWindows.removeAll()
        super.tearDown()
    }

    /// Creates a WKWebView sized to [width × height] hosted in a visible window, loads [html],
    /// and blocks until the page has finished loading.
    ///
    /// The window is not cosmetic. A WKWebView that is not in a window hierarchy gets its
    /// WebContent process marked "NearSuspended", and under load (a parallel Gradle build, a
    /// busy CI runner) that process can be throttled long enough that `didFinish` never
    /// arrives — the load simply never completes, whatever the wait budget. Hosting the view
    /// keeps the process foregrounded so the load is deterministic.
    ///
    /// The wait budget is also deliberately generous: this suite spins up a fresh WKWebView
    /// (and therefore a fresh WebContent process) per test, and process launch alone can eat
    /// seconds on a loaded machine.
    private func loadedWebView(html: String, width: CGFloat, height: CGFloat) throws -> WKWebView {
        let config = WKWebViewConfiguration()
        let webView = WKWebView(frame: CGRect(x: 0, y: 0, width: width, height: height), configuration: config)
        webView.scrollView.isScrollEnabled = true

        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: width, height: height))
        let host = UIViewController()
        host.view = webView
        window.rootViewController = host
        window.isHidden = false
        window.makeKeyAndVisible()
        hostWindows.append(window)

        let exp = expectation(description: "page loaded")
        let nav = NavigationDelegate(exp: exp)
        webView.navigationDelegate = nav
        webView.loadHTMLString(html, baseURL: nil)
        wait(for: [exp], timeout: 30)
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
        wait(for: [exp], timeout: 30)
        return result
    }

    /// Polls [script] until it reports [expected], then asserts it does.
    ///
    /// WKWebView commits scroll offsets to its UIScrollView asynchronously, so `window.scrollX`
    /// read immediately after the snap JS can still be the pre-snap value — the same async
    /// commit that [setScrollLeft] polls for on the write side. The Android WebView commits
    /// synchronously, which is why AutoFollowJsTest.kt asserts directly. Convergence does not
    /// weaken the claim: a value that is genuinely wrong never converges and still fails, with
    /// the last observed value in the message.
    private func assertSettles(webView: WKWebView, script: String, to expected: Int, _ message: String) {
        var last: Int?
        for _ in 0..<50 {
            last = evalInt(webView: webView, script: script)
            if last == expected { return }
        }
        XCTFail("\(message) (expected \(expected), last observed \(last.map(String.init) ?? "nil"))")
    }

    /// Reads [script] repeatedly until three consecutive reads agree, and returns that value.
    /// Used where the assertion is an invariant (e.g. "a multiple of innerWidth") rather than
    /// one expected number, so there is nothing to converge *to* — only a value to let settle.
    private func settledInt(webView: WKWebView, script: String) -> Int? {
        var stableFor = 0
        var previous: Int?
        for _ in 0..<50 {
            let current = evalInt(webView: webView, script: script)
            if current == previous {
                stableFor += 1
                if stableFor >= 2 { return current }
            } else {
                stableFor = 0
                previous = current
            }
        }
        return previous
    }

    /// Sets `document.scrollingElement.scrollLeft` and blocks until the web process reports the
    /// new value back.
    ///
    /// A bare `evaluateJavaScript("…scrollLeft=40")` returns as soon as the statement runs, but
    /// WKWebView commits the offset to its UIScrollView asynchronously; a snap evaluated in the
    /// very next call can therefore observe the pre-scroll layout and appear not to snap. The
    /// Android WebView commits synchronously, which is why AutoFollowJsTest.kt needs no
    /// equivalent. Polling here keeps the assertion (the snap lands on the column grid) intact
    /// while removing the platform's async-commit flake.
    private func setScrollLeft(webView: WKWebView, to value: Int) {
        _ = evalSync(webView: webView, script: "document.scrollingElement.scrollLeft=\(value)")
        for _ in 0..<50 {
            if evalInt(webView: webView, script: "document.scrollingElement.scrollLeft") == value { return }
        }
        XCTFail("WKWebView never committed scrollLeft=\(value)")
    }

    /// Sets `document.scrollingElement.scrollTop` and blocks until the web process reports it back.
    /// See [setScrollLeft] for why the poll is needed.
    private func setScrollTop(webView: WKWebView, to value: Int) {
        _ = evalSync(webView: webView, script: "document.scrollingElement.scrollTop=\(value)")
        for _ in 0..<50 {
            if evalInt(webView: webView, script: "document.scrollingElement.scrollTop") == value { return }
        }
        XCTFail("WKWebView never committed scrollTop=\(value)")
    }

    /// Runs [script] and coerces the result to a Double (WKWebView may return "0" or "0.5").
    private func evalDouble(webView: WKWebView, script: String) -> Double? {
        evalSync(webView: webView, script: script).flatMap { Double($0.trimmingQuotes) }
    }

    /// Runs [script] and coerces the result to an Int via Double (handles fractional pixels).
    private func evalInt(webView: WKWebView, script: String) -> Int? {
        evalDouble(webView: webView, script: script).map { Int($0) }
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
