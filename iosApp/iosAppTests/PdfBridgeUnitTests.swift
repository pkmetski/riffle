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
}

// Minimal Riffle-protocol-conforming callback for unit tests.
private final class FakePdfPageChangeCallback: IosPdfPageChangeCallback {
    private let handler: (Int32) -> Void
    init(_ handler: @escaping (Int32) -> Void) { self.handler = handler }
    func onPageChanged(page: Int32) { handler(page) }
}
