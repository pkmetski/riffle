import XCTest
import UIKit
import WebKit
import Riffle

/// The part of Continuous mode that Kotlin cannot cover: whether the shared scroll probes read a
/// real Readium document's real scroll state.
///
/// Everything above this seam — `ContinuousBoundaryAdvancePolicy`'s edge trigger, its cooldown,
/// the short-chapter guard, `autoScrollStallAction`, the two probe parsers — runs in `commonTest`
/// on `iosSimulatorArm64`. All of it is fed by exactly one fact: `ScrollProbes.BOUNDARY_PROBE_JS`
/// evaluated inside Readium's WKWebView. If that script reads the wrong element, or reports the
/// bottom of a document that has not been scrolled, every Kotlin test stays green and the reader
/// either never crosses a chapter or skips through the whole book.
///
/// The risk is concrete, not theoretical. Readium-Swift's own
/// `EPUBReflowableSpreadView.scroll(toProgression:)` refuses to use JavaScript for exactly this
/// measurement — *"The JS layer does not take into account the scroll view's content inset. So it
/// can't be used to reliably scroll to the top or the bottom of the page in scroll mode."* These
/// tests are what establish that a *read* of the same state is nonetheless trustworthy.
///
/// Drives a real `EPUBNavigatorViewController` over the bundled fixture. In the `iosAppUnitTests`
/// target (no `XCUIApplication`).
final class ScrollProbeBridgeTests: XCTestCase {

    private var window: UIWindow?

    override func tearDown() {
        window?.isHidden = true
        window = nil
        super.tearDown()
    }

    // MARK: - Fixture

    private func bundledEpubPath(_ name: String) -> String {
        guard let url = Bundle(for: ScrollProbeBridgeTests.self).url(forResource: name, withExtension: nil) else {
            XCTFail("Missing test asset '\(name)' in test bundle")
            return ""
        }
        return url.path
    }

    /// `scroll: true` is what `epubScrollMode` produces for Vertical AND Continuous — the two
    /// modes the boundary probe exists for. `scroll: false` is paginated, where the document
    /// overflows horizontally and `window.scrollY` never moves.
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
            columnCount: 1,
            isContinuousMode: false
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
            XCTFail("the fixture EPUB never reported a page load, so the scroll probe cannot be exercised")
            return nil
        }
        // Readium finalises layout a few frames after the first locator; a measurement taken
        // against a half-laid-out document reports a scrollHeight that is about to change.
        settle(seconds: 1.0)
        return bridge
    }

    private func settle(seconds: TimeInterval) {
        RunLoop.current.run(until: Date(timeIntervalSinceNow: seconds))
    }

    // MARK: - The seam under test

    @discardableResult
    private func eval(_ bridge: ReadiumEpubNavigatorBridge, _ script: String) -> String? {
        let done = expectation(description: "evaluateJavaScript")
        var result: String?
        bridge.evaluateJavaScript(script: script) { value in
            result = value
            done.fulfill()
        }
        XCTAssertEqual(
            XCTWaiter().wait(for: [done], timeout: 20),
            .completed,
            "evaluateJavaScript must always invoke its callback"
        )
        return result
    }

    /// Reads the boundary exactly the way `ReadiumSwiftNavigator.scrollBoundary()` does.
    private func boundary(_ bridge: ReadiumEpubNavigatorBridge) -> NavigatorScrollBoundary {
        ScrollProbes.shared.parseScrollBoundary(raw: eval(bridge, ScrollProbes.shared.BOUNDARY_PROBE_JS))
    }

    @discardableResult
    private func scroll(_ bridge: ReadiumEpubNavigatorBridge, by pixels: Int32) -> Bool {
        let done = expectation(description: "scrollByPx(\(pixels))")
        var moved = false
        bridge.scrollByPx(pixels: pixels) { result in
            moved = result.boolValue
            done.fulfill()
        }
        XCTAssertEqual(XCTWaiter().wait(for: [done], timeout: 10), .completed)
        return moved
    }

    /// Scrolls a viewport at a time until the document stops moving, then returns how many
    /// scrolls it took. Zero means the resource never scrolled at all.
    @discardableResult
    private func scrollToBottom(_ bridge: ReadiumEpubNavigatorBridge) -> Int {
        var steps = 0
        while steps < 200, scroll(bridge, by: 800) {
            steps += 1
        }
        settle(seconds: 0.3)
        return steps
    }

    // MARK: - Boundary

    func testTheProbeReportsTheTopOfAFreshlyOpenedResource() {
        guard let bridge = openFixture(scroll: true) else { return }
        let bounds = boundary(bridge)
        XCTAssertTrue(
            bounds.atBackwardBoundary,
            "a resource that has just opened is scrolled to its top; if the probe cannot see "
                + "that, Continuous can never cross backwards into the previous chapter"
        )
        bridge.disposeNavigator()
    }

    func testTheProbeDoesNotReportTheBottomUntilTheReaderIsThere() {
        guard let bridge = openFixture(scroll: true) else { return }
        guard scroll(bridge, by: 400) else {
            XCTFail("the fixture's first chapter must overflow a 390×844 viewport for this test to mean anything")
            return
        }
        // 1.0 s: give the WKWebView layout a full repaint cycle after the scroll so the boundary
        // probe reads a stable scrollY rather than a mid-animation value.
        settle(seconds: 1.0)
        XCTAssertFalse(
            boundary(bridge).atForwardBoundary,
            "one viewport into an overflowing chapter is not the end of it — a probe that says "
                + "otherwise makes Continuous skip chapters the reader has not read"
        )
        XCTAssertFalse(
            boundary(bridge).atBackwardBoundary,
            "…and it is no longer the top either, so the probe is tracking the scroll rather "
                + "than answering a constant"
        )
        bridge.disposeNavigator()
    }

    func testTheProbeReportsTheBottomOnceTheDocumentStopsScrolling() {
        guard let bridge = openFixture(scroll: true) else { return }
        let steps = scrollToBottom(bridge)
        XCTAssertGreaterThan(steps, 0, "the fixture's first chapter must be scrollable")
        XCTAssertTrue(
            boundary(bridge).atForwardBoundary,
            "the probe must agree with scrollByPx about where the resource ends. Readium's own "
                + "scroll-to-bottom avoids JS because of the content inset; if that inset also "
                + "breaks the READ, this is where it shows and the 4px slack needs widening"
        )
        bridge.disposeNavigator()
    }

    func testPaginatedModeGivesTheProbeNothingToSay() {
        // Paginated overflows *horizontally*, so `window.scrollY` never moves and the two
        // vertical predicates answer the same thing on the first page of a chapter as on the
        // last. The probe is therefore inert here — which is why
        // `ContinuousBoundaryAdvancePolicy` gates on the orientation BEFORE it looks at the
        // boundary at all, rather than trusting the measurement to be self-describing.
        guard let bridge = openFixture(scroll: false) else { return }
        let before = boundary(bridge)
        bridge.goForward()
        settle(seconds: 1.0)
        let after = boundary(bridge)
        XCTAssertEqual(
            before.atForwardBoundary, after.atForwardBoundary,
            "paginated page turns must not change the vertical forward predicate "
                + "(before=\(before.atForwardBoundary)/\(before.atBackwardBoundary), "
                + "after=\(after.atForwardBoundary)/\(after.atBackwardBoundary))"
        )
        XCTAssertEqual(
            before.atBackwardBoundary, after.atBackwardBoundary,
            "paginated page turns must not change the vertical backward predicate"
        )
        bridge.disposeNavigator()
    }

    // MARK: - Viewport fraction

    func testViewportFractionMeasuresAScrollingResource() {
        guard let bridge = openFixture(scroll: true) else { return }
        guard let fraction = ScrollProbes.shared.parseViewportFraction(
            raw: eval(bridge, ScrollProbes.shared.VIEWPORT_FRACTION_JS)
        ) else {
            XCTFail("the viewport-fraction probe returned nothing for a live scrolling resource")
            return
        }
        XCTAssertGreaterThan(fraction.doubleValue, 0.0)
        XCTAssertLessThan(
            fraction.doubleValue,
            1.0,
            "one screen must cover less than the whole of an overflowing chapter; a fraction of "
                + "1.0 would widen the bookmark window to the entire chapter and light the corner "
                + "ribbon on every page of it"
        )
        bridge.disposeNavigator()
    }

    func testViewportFractionMeasuresThePaginatedAxisToo() {
        // The probe picks the overflow axis. In paginated mode that is the horizontal one, and a
        // height-only measure would answer ~1.0 for every chapter — silently disabling the
        // highest-priority branch of `bookmarkEpsFor` in the mode most people read in.
        guard let bridge = openFixture(scroll: false) else { return }
        guard let fraction = ScrollProbes.shared.parseViewportFraction(
            raw: eval(bridge, ScrollProbes.shared.VIEWPORT_FRACTION_JS)
        ) else {
            XCTFail("the viewport-fraction probe returned nothing for a live paginated resource")
            return
        }
        XCTAssertGreaterThan(fraction.doubleValue, 0.0)
        XCTAssertLessThan(fraction.doubleValue, 1.0)
        bridge.disposeNavigator()
    }

    // MARK: - Chapter crossing

    /// Regression guard for the Continuous-mode auto-advance mechanism. The boundary loop in
    /// IosEpubReaderScreen calls bridge.goForwardInstant() when the scroll probe reports
    /// atForwardBoundary=true. This test verifies that call actually crosses the chapter: if it
    /// silently fails (wrong state, nil navigator, stale index) the loop fires but nothing moves.
    func testGoForwardInstantCrossesToNextChapter() {
        guard let bridge = openFixture(scroll: true) else { return }

        let steps = scrollToBottom(bridge)
        XCTAssertGreaterThan(steps, 0, "test.epub chapter 1 must overflow the viewport")

        XCTAssertTrue(
            boundary(bridge).atForwardBoundary,
            "must be at the forward boundary before the crossing; if false the test is mis-measuring"
        )

        let crossed = expectation(description: "locationDidChange fires with chapter2 href")
        bridge.setLocatorCallback { json in
            guard
                let data = json.data(using: .utf8),
                let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                let href = obj["href"] as? String,
                href.contains("chapter2")
            else { return }
            crossed.fulfill()
        }

        bridge.goForwardInstant()

        let outcome = XCTWaiter().wait(for: [crossed], timeout: 3.0)
        XCTAssertEqual(
            outcome, .completed,
            "goForwardInstant() must navigate to chapter 2; if this fails the crossing mechanism "
                + "is broken — check that the EPUBNavigator state is .idle when goForwardInstant "
                + "fires and that goToIndex receives an in-bounds index"
        )

        bridge.setLocatorCallback(callback: nil)
        bridge.disposeNavigator()
    }

    /// Guards that the probe reads correctly when scrolling is driven by the UIScrollView directly
    /// (i.e. physical touch), not by JS `window.scrollBy`. The two scroll paths are distinct:
    /// `scrollByPx` moves `document.scrollingElement.scrollTop` via JavaScript; a finger gesture
    /// moves `webView.scrollView.contentOffset` via UIKit. If Readium's content inset or
    /// `contentInsetAdjustmentBehavior=.never` causes `window.scrollY` to lag behind the UIScrollView
    /// position, the boundary loop in IosEpubReaderScreen would never fire a chapter crossing even
    /// though the reader is visually at the bottom of the chapter.
    func testUIScrollViewScrollReachesForwardBoundary() {
        guard let bridge = openFixture(scroll: true) else { return }

        // Find the WKWebView inside Readium's view hierarchy so we can drive its scroll view
        // directly, bypassing JS, the same way a finger gesture does.
        guard let vc = bridge.viewController() as? UIViewController else {
            XCTFail("bridge.viewController() must return a UIViewController")
            bridge.disposeNavigator()
            return
        }
        guard let wkWebView = findWKWebView(in: vc.view) else {
            XCTFail("Could not locate a WKWebView in the navigator's view tree — Readium changed its layout")
            bridge.disposeNavigator()
            return
        }

        let scrollView = wkWebView.scrollView

        // Confirm the document is scrollable; if scrollHeight <= innerHeight the test cannot
        // distinguish "at the top/bottom simultaneously" from "genuinely at the bottom".
        let sh = Float(eval(bridge, "document.body.scrollHeight") ?? "0") ?? 0
        let ih = Float(eval(bridge, "window.innerHeight") ?? "0") ?? 0
        guard sh > ih + 10 else {
            XCTFail(
                "test.epub chapter 1 must overflow the viewport (scrollHeight=\(sh), innerHeight=\(ih)). "
                    + "Increase chapter length or reduce the host window size."
            )
            bridge.disposeNavigator()
            return
        }

        // Drive the UIScrollView to its physical maximum — exactly what the user's finger does.
        let maxY = scrollView.contentSize.height - scrollView.bounds.height + scrollView.contentInset.bottom
        scrollView.setContentOffset(CGPoint(x: 0, y: max(0, maxY)), animated: false)
        settle(seconds: 0.5)

        let bounds = boundary(bridge)
        XCTAssertTrue(
            bounds.atForwardBoundary,
            "The boundary probe must detect the chapter end when the UIScrollView reaches its "
                + "physical maximum (contentSize.height=\(scrollView.contentSize.height), "
                + "bounds.height=\(scrollView.bounds.height), "
                + "contentInset.bottom=\(scrollView.contentInset.bottom), "
                + "scrollY from JS=\(eval(bridge, "window.scrollY") ?? "?"), "
                + "sh=\(sh), ih=\(ih)). "
                + "If false: window.scrollY does not track UIScrollView scroll — the boundary loop in "
                + "IosEpubReaderScreen will never fire a chapter crossing during physical reading."
        )

        bridge.disposeNavigator()
    }

    /// Same as `testUIScrollViewScrollReachesForwardBoundary` but with a non-zero content inset
    /// that matches a real iPhone's safe area (Dynamic Island top ~59pt, home indicator bottom ~34pt).
    /// In the unit-test window `view.window?.safeAreaInsets` is zero, so Readium applies no inset
    /// and `window.scrollY` tracks UIScrollView scroll 1:1. The real app has safe-area insets and
    /// Readium applies them via `EPUBReflowableSpreadView.updateContentInset`. If the WebKit DOM
    /// does not expose `window.scrollY` beyond the content's natural scroll limit, the probe would
    /// report `atForwardBoundary=false` even though the UIScrollView is at its physical maximum.
    func testUIScrollViewScrollWithSafeAreaInsetReachesForwardBoundary() {
        guard let bridge = openFixture(scroll: true) else { return }

        guard let vc = bridge.viewController() as? UIViewController else {
            XCTFail("bridge.viewController() must return a UIViewController")
            bridge.disposeNavigator()
            return
        }
        guard let wkWebView = findWKWebView(in: vc.view) else {
            XCTFail("Could not locate a WKWebView in the navigator's view tree")
            bridge.disposeNavigator()
            return
        }

        let scrollView = wkWebView.scrollView

        // Simulate iPhone 17 safe area insets (Dynamic Island top=59, home indicator bottom=34).
        // Readium applies these via spreadViewContentInset in the real app; we inject them here.
        let topInset: CGFloat = 59
        let bottomInset: CGFloat = 34
        scrollView.contentInset = UIEdgeInsets(top: topInset, left: 0, bottom: bottomInset, right: 0)
        settle(seconds: 0.3)

        let sh = Float(eval(bridge, "document.body.scrollHeight") ?? "0") ?? 0
        let ih = Float(eval(bridge, "window.innerHeight") ?? "0") ?? 0
        guard sh > ih + 10 else {
            XCTFail("test.epub chapter 1 must overflow the viewport (sh=\(sh), ih=\(ih))")
            bridge.disposeNavigator()
            return
        }

        // Drive UIScrollView to max — includes the bottomInset extension (same as physical scroll).
        let maxY = scrollView.contentSize.height - scrollView.bounds.height + scrollView.contentInset.bottom
        scrollView.setContentOffset(CGPoint(x: 0, y: max(0, maxY)), animated: false)
        settle(seconds: 0.5)

        let scrollY = eval(bridge, "window.scrollY") ?? "?"
        let bounds = boundary(bridge)
        XCTAssertTrue(
            bounds.atForwardBoundary,
            "The boundary probe must detect the chapter end with a real-device-like content inset "
                + "(top=\(topInset), bottom=\(bottomInset)). "
                + "UIScrollView maxY=\(maxY), "
                + "contentSize.height=\(scrollView.contentSize.height), "
                + "bounds.height=\(scrollView.bounds.height), "
                + "window.scrollY=\(scrollY), sh=\(sh), ih=\(ih). "
                + "If false: the boundary loop will never advance chapters on a real iPhone."
        )

        bridge.disposeNavigator()
    }

    private func findWKWebView(in view: UIView) -> WKWebView? {
        if let wv = view as? WKWebView { return wv }
        for sub in view.subviews {
            if let found = findWKWebView(in: sub) { return found }
        }
        return nil
    }

    /// End-to-end regression for the Continuous-mode boundary loop:
    /// UIScrollView scroll to physical bottom → boundary probe fires → goForwardInstant() → chapter advances.
    ///
    /// The existing tests cover each link in this chain in isolation. This test covers them together
    /// because a timing or ordering bug between the scroll and the boundary read could pass every
    /// isolated check while still silently breaking the loop in the real app.
    func testUIScrollViewScrollThenBoundaryDetectionThenChapterCrossing() {
        guard let bridge = openFixture(scroll: true) else { return }

        guard let vc = bridge.viewController() as? UIViewController,
              let wkWebView = findWKWebView(in: vc.view) else {
            XCTFail("Could not locate WKWebView in navigator view tree")
            bridge.disposeNavigator()
            return
        }

        let scrollView = wkWebView.scrollView
        let sh = Float(eval(bridge, "document.body.scrollHeight") ?? "0") ?? 0
        let ih = Float(eval(bridge, "window.innerHeight") ?? "0") ?? 0
        guard sh > ih + 10 else {
            XCTFail("test.epub chapter 1 must overflow the viewport (sh=\(sh), ih=\(ih))")
            bridge.disposeNavigator()
            return
        }

        let maxY = scrollView.contentSize.height - scrollView.bounds.height + scrollView.contentInset.bottom
        scrollView.setContentOffset(CGPoint(x: 0, y: max(0, maxY)), animated: false)
        settle(seconds: 0.5)

        XCTAssertTrue(
            boundary(bridge).atForwardBoundary,
            "UIScrollView at max offset must yield atForwardBoundary=true (scrollY=\(eval(bridge, "window.scrollY") ?? "?"), sh=\(sh), ih=\(ih))"
        )

        let crossed = expectation(description: "chapter 2 href received after goForwardInstant")
        bridge.setLocatorCallback { json in
            guard
                let data = json.data(using: .utf8),
                let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                let href = obj["href"] as? String,
                href.contains("chapter2")
            else { return }
            crossed.fulfill()
        }

        bridge.goForwardInstant()

        XCTAssertEqual(
            XCTWaiter().wait(for: [crossed], timeout: 5.0),
            .completed,
            "After UIScrollView reaches bottom and boundary is confirmed, goForwardInstant() must navigate to chapter 2"
        )

        bridge.setLocatorCallback(callback: nil)
        bridge.disposeNavigator()
    }

    func testTheProbesAnswerNothingWhenThereIsNoNavigator() {
        // The reader closes while the Continuous poll is mid-flight. Both parsers must degrade to
        // the inert answer rather than throwing or reporting a boundary that fires a navigation.
        let bridge = ReadiumEpubNavigatorBridge()
        XCTAssertFalse(boundary(bridge).atForwardBoundary)
        XCTAssertFalse(boundary(bridge).atBackwardBoundary)
        XCTAssertNil(
            ScrollProbes.shared.parseViewportFraction(
                raw: eval(bridge, ScrollProbes.shared.VIEWPORT_FRACTION_JS)
            )
        )
    }
}
