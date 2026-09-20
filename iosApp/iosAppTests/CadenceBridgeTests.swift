import XCTest
import UIKit
import Riffle

/// The part of Cadence that Kotlin cannot cover: the JavaScript that has to change Readium's
/// document.
///
/// Everything above this seam — the ticker, the quote accumulation, the start-ref resolution, the
/// decoration mapper, the HUD pill and the Settings panel — runs in `commonTest` on
/// `iosSimulatorArm64`. All of it stops at `ReadiumSwiftNavigator`, which hands a script to this
/// bridge. If the tokeniser never wraps a sentence, or the column snap never moves the page,
/// every Kotlin test stays green and the reader highlights nothing.
///
/// So these drive a real `EPUBNavigatorViewController` over the bundled fixture and assert on the
/// *document*, not on the fact that a script returned something. In the `iosAppUnitTests` target
/// (no `XCUIApplication`).
final class CadenceBridgeTests: XCTestCase {

    private var window: UIWindow?

    override func tearDown() {
        window?.isHidden = true
        window = nil
        super.tearDown()
    }

    // MARK: - Fixture

    private func bundledEpubPath(_ name: String) -> String {
        guard let url = Bundle(for: CadenceBridgeTests.self).url(forResource: name, withExtension: nil) else {
            XCTFail("Missing test asset '\(name)' in test bundle")
            return ""
        }
        return url.path
    }

    /// `scroll: false` is what `epubScrollMode` produces for `ReaderOrientation.Horizontal` —
    /// the only mode with a column grid, and therefore the only one where Cadence's measure and
    /// snap do anything. `scroll: true` covers Vertical and Continuous, which both land there.
    private func preferences(scroll: Bool) -> IosReaderPreferences {
        IosReaderPreferences(
            fontSizePercent: 1.0,
            scrollMode: scroll,
            theme: "light",
            fontFamilyCss: "",
            lineHeightMultiplier: 0.0,
            pageMargins: 1.0,
            justifyText: false,
            textColorArgb: 0,
            publisherStyles: true,
            columnCount: 1
        )
    }

    private func openFixture(scroll: Bool) -> ReadiumEpubNavigatorBridge? {
        let bridge = ReadiumEpubNavigatorBridge()
        bridge.applyReaderPreferences(preferences: preferences(scroll: scroll))

        let loaded = expectation(description: "first chapter loaded")
        loaded.assertForOverFulfill = false
        bridge.setPageLoadCallback { loaded.fulfill() }

        let hostWindow = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        hostWindow.rootViewController = bridge.viewController()
        hostWindow.makeKeyAndVisible()
        window = hostWindow

        bridge.openEpub(filePath: bundledEpubPath("test.epub"), locatorJson: nil)

        guard XCTWaiter().wait(for: [loaded], timeout: 30) == .completed else {
            XCTFail("the fixture EPUB never reported a page load, so the Cadence seam cannot be exercised")
            return nil
        }
        // Readium finalises the column layout a few frames after the first locator. Let it settle
        // so the very first measurement is not taken against a half-laid-out document.
        settle(seconds: 1.0)
        return bridge
    }

    private func settle(seconds: TimeInterval) {
        RunLoop.current.run(until: Date(timeIntervalSinceNow: seconds))
    }

    // MARK: - The seam under test

    /// Runs `script` through the production seam — the same call `ReadiumSwiftNavigator` makes.
    @discardableResult
    private func eval(_ bridge: ReadiumEpubNavigatorBridge, _ script: String) -> String? {
        let done = expectation(description: "evaluateJavaScript")
        var result: String?
        bridge.evaluateJavaScript(script: script) { value in
            result = value
            done.fulfill()
        }
        let outcome = XCTWaiter().wait(for: [done], timeout: 20)
        XCTAssertEqual(outcome, .completed, "evaluateJavaScript must always invoke its callback")
        return result
    }

    private func evalInt(_ bridge: ReadiumEpubNavigatorBridge, _ script: String) -> Int {
        Int(Double(eval(bridge, script) ?? "") ?? -1)
    }

    private func tokeniseCurrentChapter(_ bridge: ReadiumEpubNavigatorBridge) -> CadenceInjectorResultReady? {
        let script = CadenceDomScript.shared.tokeniseChapterJs(chapterHref: "OEBPS/chapter1.xhtml", localeTag: nil)
        let raw = eval(bridge, script)
        guard let ready = CadenceInjector.shared.parse(rawWebViewJson: raw) as? CadenceInjectorResultReady else {
            XCTFail("tokenise returned \(raw ?? "nil"), which the shared parser rejected")
            return nil
        }
        return ready
    }

    // MARK: - Feature detect

    func testFeatureDetectFindsIntlSegmenterInReadiumsWebView() {
        guard let bridge = openFixture(scroll: true) else { return }
        // Cadence has no fallback tokeniser: a `false` here hides the toggle and the Settings
        // row on every device. The answer also has to be the literal "true" the Kotlin gate
        // compares against, not "1" — the marshalling is as load-bearing as the probe.
        XCTAssertEqual("true", eval(bridge, CadenceDomScript.shared.FEATURE_DETECT_JS))
        bridge.disposeNavigator()
    }

    func testBooleanResultsMarshalAsTrueOrFalseRatherThanOneOrZero() {
        // CFBoolean bridges to NSNumber, so the obvious `number.stringValue` would answer "1"
        // and silently fail the feature-detect comparison above on every device.
        XCTAssertEqual("true", ReadiumEpubNavigatorBridge.stringifyJavaScriptResult(true))
        XCTAssertEqual("false", ReadiumEpubNavigatorBridge.stringifyJavaScriptResult(false))
        XCTAssertEqual("7", ReadiumEpubNavigatorBridge.stringifyJavaScriptResult(7))
        XCTAssertEqual("cd-3", ReadiumEpubNavigatorBridge.stringifyJavaScriptResult("cd-3"))
        XCTAssertNil(ReadiumEpubNavigatorBridge.stringifyJavaScriptResult(nil))
        XCTAssertNil(ReadiumEpubNavigatorBridge.stringifyJavaScriptResult(NSNull()))
    }

    func testEvaluateJavaScriptReportsNilWhenThereIsNoNavigator() {
        // The reader closes while a tokenise is in flight; the callback must still fire so the
        // suspended Kotlin coroutine resumes instead of hanging the session.
        let bridge = ReadiumEpubNavigatorBridge()
        XCTAssertNil(eval(bridge, "1 + 1"))
    }

    // MARK: - Tokenisation actually rewrites the DOM

    func testTokeniseWrapsEverySentenceInASpanInTheLiveDocument() {
        guard let bridge = openFixture(scroll: true) else { return }
        let before = evalInt(bridge, "document.querySelectorAll('span.riffle-cd').length")
        XCTAssertEqual(0, before, "the document must start untokenised")

        guard let ready = tokeniseCurrentChapter(bridge) else { return }

        let after = evalInt(bridge, "document.querySelectorAll('span.riffle-cd').length")
        XCTAssertGreaterThan(
            after, 1,
            "the tokeniser must inject a span per sentence into Readium's document; a script that "
                + "merely ran leaves the reader with nothing to highlight"
        )
        XCTAssertEqual(
            after, ready.quotes.count,
            "every injected span must have a matching quote — the decoration is keyed on the pair"
        )
        // The refs are the `href#cd-N` contract the ticker and the decoration both key on.
        let firstRef = ready.quotes.keys.first { ($0 as? String)?.contains("#cd-") == true }
        XCTAssertNotNil(firstRef, "fragment refs must be 'chapterHref#cd-N', got \(Array(ready.quotes.keys).prefix(3))")

        // …and the span the JS reports is really in the document with the sentence's text.
        let spanId = (firstRef as? String)?.components(separatedBy: "#").last ?? ""
        let text = eval(bridge, "(document.getElementById('\(spanId)')||{}).textContent || ''")
        XCTAssertFalse((text ?? "").isEmpty, "span \(spanId) must exist in the document and carry text")

        // The chapter is stamped onto <html> so the start probe can build a chapter-authoritative
        // ref even when Readium's locator href lags the rendered DOM by one chapter.
        XCTAssertEqual(
            "OEBPS/chapter1.xhtml",
            eval(bridge, "document.documentElement.getAttribute('data-riffle-chapter')")
        )
        bridge.disposeNavigator()
    }

    func testTokenisingTwiceDoesNotNestOrDuplicateSpans() {
        // Readium reports a resource load again after a reflow and after a backward turn. A
        // second walk over already-wrapped text produces nested spans, duplicate ids and
        // zero-sized rects that break the start probe.
        guard let bridge = openFixture(scroll: true) else { return }
        guard let first = tokeniseCurrentChapter(bridge) else { return }
        let afterFirst = evalInt(bridge, "document.querySelectorAll('span.riffle-cd').length")

        guard let second = tokeniseCurrentChapter(bridge) else { return }
        let afterSecond = evalInt(bridge, "document.querySelectorAll('span.riffle-cd').length")

        XCTAssertEqual(afterFirst, afterSecond, "a repeat tokenise must not add spans")
        XCTAssertEqual(first.quotes.count, second.quotes.count)
        XCTAssertEqual(
            0,
            evalInt(bridge, "document.querySelectorAll('span.riffle-cd span.riffle-cd').length"),
            "spans must never nest"
        )
        bridge.disposeNavigator()
    }

    // MARK: - The start-position probe

    func testStartProbeResolvesASpanThatIsActuallyInTheDocument() {
        guard let bridge = openFixture(scroll: false) else { return }
        guard tokeniseCurrentChapter(bridge) != nil else { return }

        // nil bounds: the probe reads the WebView's own scroll, which is correct in both of
        // Readium's modes because the WKWebView owns its scroll in each.
        let probeJs = CadenceDomScript.shared.cadenceStartSpanIdJs(
            viewportTopDocPx: nil,
            viewportHeightPx: nil,
            viewportLeftDocPx: nil,
            viewportWidthPx: nil
        )
        let ref = CadenceDomScript.shared.parseCadenceStartId(raw: eval(bridge, probeJs))
        guard let ref, !ref.isEmpty else {
            XCTFail("the start probe found no sentence on a fully tokenised, fully laid out page")
            return
        }
        XCTAssertTrue(ref.contains("#cd-"), "probe must return a chapter-qualified cd ref, got '\(ref)'")

        let spanId = ref.components(separatedBy: "#").last ?? ""
        XCTAssertEqual(
            "1",
            eval(bridge, "document.getElementById('\(spanId)') ? 1 : 0"),
            "the probe must name a span that exists — a ref the ticker cannot find makes play() "
                + "fall back to the first sentence of the book"
        )
        bridge.disposeNavigator()
    }

    // MARK: - Paginated column snapping

    func testMeasureAndSnapMoveThePaginatedDocumentToTheSentencesColumn() {
        guard let bridge = openFixture(scroll: false) else { return }
        guard tokeniseCurrentChapter(bridge) != nil else { return }

        let innerWidth = evalInt(bridge, "window.innerWidth")
        XCTAssertGreaterThan(innerWidth, 100, "the spread view must have a real width")
        let scrollWidth = evalInt(bridge, "(document.scrollingElement||document.documentElement).scrollWidth")
        XCTAssertGreaterThan(
            scrollWidth, innerWidth,
            "the fixture chapter must paginate to more than one column for this to mean anything"
        )

        // The last sentence of the chapter is, by construction, not in the first column.
        let lastId = eval(bridge, """
        (function(){var e=document.querySelectorAll('span.riffle-cd');\
        return e.length?e[e.length-1].id:'';})()
        """) ?? ""
        XCTAssertTrue(lastId.hasPrefix("cd-"), "expected a tokenised span id, got '\(lastId)'")

        // measure: non-empty is the "this mode has a column grid" signal the reader's
        // intra-sentence page follow keys on. Empty here would silently disable it.
        let columns = ColumnSnap.shared.parseNarratedColumnsResult(
            raw: eval(bridge, ColumnSnap.shared.measureCadenceColumnsJs(fragmentId: lastId))
        )
        XCTAssertFalse(columns.isEmpty, "paginated measure must report the sentence's columns")
        XCTAssertEqual(1.0, columns.last?.doubleValue ?? 0, accuracy: 0.001,
                       "the fractions are cumulative and must end at 1.0")

        // snap: the page must actually move, and land flush on the column grid.
        eval(bridge, "(document.scrollingElement||document.documentElement).scrollLeft=0")
        eval(bridge, ColumnSnap.shared.snapCadenceColumnJs(fragmentId: lastId, columnIndex: 0))
        let landed = settledScrollLeft(bridge)
        XCTAssertGreaterThan(
            landed, 0,
            "snapping to the last sentence's column must turn the page; a stub that returns "
                + "without scrolling leaves the highlight off-screen"
        )
        XCTAssertEqual(0, landed % innerWidth,
                       "the page must land ON the grid (scrollLeft=\(landed), innerWidth=\(innerWidth))")
        bridge.disposeNavigator()
    }

    func testFollowingASpanReportsMovedSameAndAbsent() {
        guard let bridge = openFixture(scroll: false) else { return }
        guard tokeniseCurrentChapter(bridge) != nil else { return }

        eval(bridge, "(document.scrollingElement||document.documentElement).scrollLeft=0")
        let lastId = eval(bridge, """
        (function(){var e=document.querySelectorAll('span.riffle-cd');\
        return e.length?e[e.length-1].id:'';})()
        """) ?? ""

        let moved = eval(bridge, ColumnSnap.shared.scrollToColumnJs(fragmentId: lastId, animated: false))?
            .trimmingCharacters(in: CharacterSet(charactersIn: "\""))
        XCTAssertEqual("moved", moved, "following an off-page sentence must turn to its column")

        // `scrollToColumnJs` decides "same" by comparing the live `scrollLeft` against its target,
        // and WKWebView does not commit the assignment synchronously — asking again before the
        // scroll has settled reads the pre-scroll value and reports "moved" a second time. Wait
        // for it, as the sibling snap test does. Production never hits this: the reader follows
        // only on a sentence change.
        _ = settledScrollLeft(bridge)

        let again = eval(bridge, ColumnSnap.shared.scrollToColumnJs(fragmentId: lastId, animated: false))?
            .trimmingCharacters(in: CharacterSet(charactersIn: "\""))
        XCTAssertEqual("same", again, "a second follow of the same sentence must be a no-op, not a re-turn")

        let absent = eval(bridge, ColumnSnap.shared.scrollToColumnJs(fragmentId: "cd-999999", animated: false))?
            .trimmingCharacters(in: CharacterSet(charactersIn: "\""))
        XCTAssertEqual("absent", absent, "a sentence from another resource must report absent so the "
                       + "reader navigates to its chapter")
        bridge.disposeNavigator()
    }

    func testScrollModeReportsNoColumnGrid() {
        // Vertical and Continuous both map to Readium's scroll mode. There is no column grid, so
        // the measure must come back empty and the intra-sentence page follow must stay off —
        // exactly as Android's Vertical/Continuous presenters behave.
        guard let bridge = openFixture(scroll: true) else { return }
        guard tokeniseCurrentChapter(bridge) != nil else { return }

        let firstId = eval(bridge, """
        (function(){var e=document.querySelector('span.riffle-cd');return e?e.id:'';})()
        """) ?? ""
        XCTAssertTrue(firstId.hasPrefix("cd-"), "expected a tokenised span id, got '\(firstId)'")

        let columns = ColumnSnap.shared.parseNarratedColumnsResult(
            raw: eval(bridge, ColumnSnap.shared.measureCadenceColumnsJs(fragmentId: firstId))
        )
        XCTAssertTrue(columns.isEmpty, "a scrolling document has no columns to turn between")
        bridge.disposeNavigator()
    }

    // MARK: - Helpers

    /// WKWebView commits scroll offsets asynchronously; read until two consecutive reads agree.
    private func settledScrollLeft(_ bridge: ReadiumEpubNavigatorBridge) -> Int {
        var previous = Int.min
        for _ in 0..<40 {
            let current = evalInt(bridge, "(document.scrollingElement||document.documentElement).scrollLeft")
            if current == previous { return current }
            previous = current
            settle(seconds: 0.05)
        }
        return previous
    }
}
