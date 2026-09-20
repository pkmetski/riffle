import XCTest
import UIKit
import Riffle

/// The one piece of auto-scroll that Kotlin cannot cover: the JavaScript that actually moves
/// Readium's document.
///
/// Everything above this seam — the ticker, the WPM→px/s conversion, the state machine, the HUD
/// pill and the Settings panel — runs in `commonTest` on `iosSimulatorArm64`. But those all stop
/// at `ReadiumSwiftNavigator.scrollByPx`, which hands a pixel delta to this bridge; if the script
/// targets the wrong element, or Readium's spread view does not scroll the document at all, every
/// Kotlin test stays green and the reader simply never moves.
///
/// These drive a real `EPUBNavigatorViewController` over the bundled fixture, in scroll mode.
/// In the `iosAppUnitTests` target (no `XCUIApplication`).
final class AutoScrollBridgeTests: XCTestCase {

    private var window: UIWindow?

    override func tearDown() {
        window?.isHidden = true
        window = nil
        super.tearDown()
    }

    private func bundledEpubPath(_ name: String) -> String {
        guard let url = Bundle(for: AutoScrollBridgeTests.self).url(forResource: name, withExtension: nil) else {
            XCTFail("Missing test asset '\(name)' in test bundle")
            return ""
        }
        return url.path
    }

    private func scrollPreferences() -> IosReaderPreferences {
        // scrollMode: true is what `epubScrollMode` produces for Vertical and Continuous — the
        // only orientations the auto-scroll toggle is offered in. In paginated mode there is no
        // scrolling document and the feature is deliberately unavailable.
        IosReaderPreferences(
            fontSizePercent: 1.0,
            scrollMode: true,
            theme: "light",
            fontFamilyCss: "",
            lineHeightMultiplier: 0.0,
            pageMargins: 1.0,
            justifyText: false,
            textColorArgb: 0,
            publisherStyles: true,
            columnCount: 0
        )
    }

    /// Opens the fixture in a hosted window and waits for the first page-load callback.
    private func openFixture() -> ReadiumEpubNavigatorBridge? {
        let bridge = ReadiumEpubNavigatorBridge()
        bridge.applyReaderPreferences(preferences: scrollPreferences())

        let loaded = expectation(description: "first chapter loaded")
        loaded.assertForOverFulfill = false
        bridge.setPageLoadCallback { loaded.fulfill() }

        let hostWindow = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        hostWindow.rootViewController = bridge.viewController()
        hostWindow.makeKeyAndVisible()
        window = hostWindow

        bridge.openEpub(filePath: bundledEpubPath("test.epub"), locatorJson: nil)

        let outcome = XCTWaiter().wait(for: [loaded], timeout: 30)
        guard outcome == .completed else {
            XCTFail("the fixture EPUB never reported a page load, so the scroll seam cannot be exercised")
            return nil
        }
        return bridge
    }

    private func scroll(_ bridge: ReadiumEpubNavigatorBridge, by pixels: Int32) -> Bool {
        let done = expectation(description: "scrollByPx(\(pixels))")
        var moved = false
        bridge.scrollByPx(pixels: pixels) { result in
            moved = result.boolValue
            done.fulfill()
        }
        let outcome = XCTWaiter().wait(for: [done], timeout: 10)
        XCTAssertEqual(outcome, .completed, "scrollByPx must always invoke its callback")
        return moved
    }

    func testScrollByPxMovesReadiumsDocument() {
        guard let bridge = openFixture() else { return }
        XCTAssertTrue(
            scroll(bridge, by: 200),
            "scrollByPx must move the scrolling element of the open resource; if this fails the "
                + "auto-scroll ticker runs and nothing on screen moves"
        )
        bridge.disposeNavigator()
    }

    func testScrollByPxReportsFalseWhenThereIsNoNavigator() {
        // The reader closes while the ticker is mid-flight; the callback must still fire, with
        // false, so the ticker stops rather than waiting forever on a suspended coroutine.
        let bridge = ReadiumEpubNavigatorBridge()
        XCTAssertFalse(scroll(bridge, by: 200))
    }

    func testScrollByPxReportsFalseWhenTheDocumentDoesNotMove() {
        guard let bridge = openFixture() else { return }
        // A zero-pixel scroll is the one delta that provably cannot move the document, with a
        // live navigator and a loaded resource. It is what pins the before/after comparison: a
        // seam that answered `true` unconditionally — losing the "this resource cannot scroll
        // further" signal the ticker stops on — would pass every other test in this file and
        // fail this one.
        XCTAssertFalse(
            scroll(bridge, by: 0),
            "scrollByPx must report whether scrollTop actually changed, not merely that the "
                + "script ran"
        )
        // …and the same bridge still scrolls when given a real delta, so the false above is the
        // comparison talking and not a navigator that has stopped responding.
        XCTAssertTrue(scroll(bridge, by: 200))
        bridge.disposeNavigator()
    }
}
