import XCTest
import Riffle

// Unit tests for PdfKitNavigatorBridgeImpl. In the iosAppUnitTests target so the bridge
// Swift class and the Riffle KMP framework are both available.

final class PdfBridgeUnitTests: XCTestCase {

    // Regression: setPageChangeCallback was implemented on both sides of the bridge but never
    // called from the Kotlin PDF reader screen (IosPdfReaderScreen.kt), so page changes went
    // untracked until dispose. This test pins the callback contract so regressions are caught.
    func testPageChangeCallbackIsInvokedWhenRegistered() {
        let bridge = PdfKitNavigatorBridgeImpl()
        var receivedPage: Int32 = -1
        let callback = FakePdfPageChangeCallback { page in receivedPage = page }
        bridge.setPageChangeCallback(callback: callback)
        bridge.simulatePageChange(3)
        XCTAssertEqual(receivedPage, 3,
                       "setPageChangeCallback must fire when a page change is simulated")
    }

    func testPageChangeCallbackIsNotInvokedAfterClear() {
        let bridge = PdfKitNavigatorBridgeImpl()
        var callCount = 0
        let callback = FakePdfPageChangeCallback { _ in callCount += 1 }
        bridge.setPageChangeCallback(callback: callback)
        bridge.setPageChangeCallback(callback: nil)
        bridge.simulatePageChange(1)
        XCTAssertEqual(callCount, 0,
                       "Cleared callback must not fire on subsequent page changes")
    }

    func testDisposePdfClearsPageChangeCallback() {
        let bridge = PdfKitNavigatorBridgeImpl()
        var callCount = 0
        let callback = FakePdfPageChangeCallback { _ in callCount += 1 }
        bridge.setPageChangeCallback(callback: callback)
        bridge.disposePdf()
        bridge.simulatePageChange(2)
        XCTAssertEqual(callCount, 0,
                       "disposePdf must clear the page change callback")
    }

    func testTapCallbackIsInvokedWhenRegistered() {
        let bridge = PdfKitNavigatorBridgeImpl()
        var tapCount = 0
        let callback = FakePdfTapCallback { tapCount += 1 }
        bridge.setTapCallback(callback_: callback)
        bridge.simulateTap()
        XCTAssertEqual(tapCount, 1,
                       "setTapCallback must fire when a tap is simulated")
    }

    func testTapCallbackIsNotInvokedAfterClear() {
        let bridge = PdfKitNavigatorBridgeImpl()
        var tapCount = 0
        let callback = FakePdfTapCallback { tapCount += 1 }
        bridge.setTapCallback(callback_: callback)
        bridge.setTapCallback(callback_: nil)
        bridge.simulateTap()
        XCTAssertEqual(tapCount, 0,
                       "Cleared tap callback must not fire on subsequent taps")
    }

    func testDisposePdfClearsTapCallback() {
        let bridge = PdfKitNavigatorBridgeImpl()
        var tapCount = 0
        let callback = FakePdfTapCallback { tapCount += 1 }
        bridge.setTapCallback(callback_: callback)
        bridge.disposePdf()
        bridge.simulateTap()
        XCTAssertEqual(tapCount, 0,
                       "disposePdf must clear the tap callback")
    }

    func testOutlineReturnsEmptyForDocumentWithoutOutline() {
        let bridge = PdfKitNavigatorBridgeImpl()
        // No document opened — bridge should return an empty outline, not crash.
        let outline = bridge.outline()
        XCTAssertTrue(outline.isEmpty,
                      "outline() must return empty list when no document is open")
    }
}

// Minimal Riffle-protocol-conforming callbacks for unit tests.

private final class FakePdfPageChangeCallback: IosPdfPageChangeCallback {
    private let handler: (Int32) -> Void
    init(_ handler: @escaping (Int32) -> Void) { self.handler = handler }
    func onPageChanged(page: Int32) { handler(page) }
}

private final class FakePdfTapCallback: IosPdfTapCallback {
    private let handler: () -> Void
    init(_ handler: @escaping () -> Void) { self.handler = handler }
    func onTap() { handler() }
}
