import XCTest
import UIKit
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
        let b = boundary(bridge)
        XCTAssertTrue(
            b.atBackwardBoundary,
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
        settle(seconds: 0.3)
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
