import XCTest
import Riffle

// Unit tests for the footnote-callback wiring in ReadiumEpubNavigatorBridge.
//
// The delegate path (navigator(_:shouldNavigateToNoteAt:content:referrer:)) cannot be driven
// directly in a unit test because it requires a live Readium Navigator instance. The bridge
// exposes simulateFootnoteTap(content:) as a test seam that exercises the same stripping and
// dispatch logic. Reverting setFootnoteCallback's storage, removing the stripping regex, or
// deleting the guard/return-false branch would each flip at least one test here red.
final class FootnoteBridgeTests: XCTestCase {

    func testCallbackReceivesStrippedPlainText() {
        let bridge = ReadiumEpubNavigatorBridge()
        var received: String?
        bridge.setFootnoteCallback { text in received = text }

        _ = bridge.simulateFootnoteTap(content: "<aside><p>A footnote <em>here</em>.</p></aside>")

        XCTAssertEqual(received, "A footnote here .",
                       "HTML tags must be stripped and excess whitespace collapsed")
    }

    func testReturnsFalseWhenCallbackRegistered() {
        let bridge = ReadiumEpubNavigatorBridge()
        bridge.setFootnoteCallback { _ in }

        let handled = bridge.simulateFootnoteTap(content: "<p>Note</p>")

        XCTAssertFalse(handled,
                       "Must return false (intercept navigation) when a callback is registered")
    }

    func testReturnsTrueWhenNoCallbackRegistered() {
        let bridge = ReadiumEpubNavigatorBridge()

        let handled = bridge.simulateFootnoteTap(content: "<p>Note</p>")

        XCTAssertTrue(handled,
                      "Must return true (allow Readium to navigate) when no callback is registered")
    }

    func testCallbackNotInvokedAfterCleared() {
        let bridge = ReadiumEpubNavigatorBridge()
        var callCount = 0
        bridge.setFootnoteCallback { _ in callCount += 1 }
        bridge.setFootnoteCallback(callback: nil)

        _ = bridge.simulateFootnoteTap(content: "<p>Note</p>")

        XCTAssertEqual(callCount, 0,
                       "Cleared callback must not fire on subsequent footnote taps")
    }

    func testRawHtmlPassedThroughWhenStrippingYieldsEmptyString() {
        let bridge = ReadiumEpubNavigatorBridge()
        var received: String?
        bridge.setFootnoteCallback { text in received = text }

        _ = bridge.simulateFootnoteTap(content: "<p></p>")

        XCTAssertEqual(received, "<p></p>",
                       "When stripping yields only whitespace, the original HTML must be forwarded")
    }
}
