import XCTest
import PDFKit
import Riffle

// Covers scenarios from docs/testing/ios-scenarios/06-pdf-reader.md

final class PdfReaderTests: XCTestCase {

    // MARK: - Scenario 06-A: Bridge lifecycle

    func testFactoryCreatesDistinctInstances() {
        let factory = PdfKitNavigatorBridgeFactoryImpl()
        let b1 = factory.create()
        let b2 = factory.create()
        XCTAssertFalse(b1 === (b2 as AnyObject), "Factory must return distinct instances")
    }

    func testViewControllerIsNonNilBeforeOpen() {
        let bridge = PdfKitNavigatorBridgeImpl()
        XCTAssertNotNil(bridge.viewController())
    }

    func testPageCountIsZeroBeforeOpen() {
        let bridge = PdfKitNavigatorBridgeImpl()
        XCTAssertEqual(bridge.pageCount(), 0)
    }

    func testCurrentPageIsZeroBeforeOpen() {
        let bridge = PdfKitNavigatorBridgeImpl()
        XCTAssertEqual(bridge.currentPage(), 0)
    }

    func testDisposeIsIdempotent() {
        let bridge = PdfKitNavigatorBridgeImpl()
        bridge.disposePdf()
        bridge.disposePdf()
        // Must not crash
    }

    // MARK: - Scenario 06-B: Opening a PDF

    func testPageCountAfterOpenReturnsDocumentPageCount() {
        let (bridge, url) = makeBridgeWithPdf(pageCount: 3)
        bridge.openPdf(filePath: url.path, initialPage: 0)
        XCTAssertEqual(bridge.pageCount(), 3)
    }

    func testOpenAtInitialPageZeroStartsAtFirstPage() {
        let (bridge, url) = makeBridgeWithPdf(pageCount: 3)
        bridge.openPdf(filePath: url.path, initialPage: 0)
        XCTAssertEqual(bridge.currentPage(), 0)
    }

    func testOpenAtNonZeroInitialPageNavigatesToThatPage() {
        let (bridge, url) = makeBridgeWithPdf(pageCount: 5)
        bridge.openPdf(filePath: url.path, initialPage: 2)
        XCTAssertEqual(bridge.currentPage(), 2)
    }

    // MARK: - Scenario 06-C: Page navigation

    func testGoToPageUpdatesCurrentPage() {
        let (bridge, url) = makeBridgeWithPdf(pageCount: 5)
        bridge.openPdf(filePath: url.path, initialPage: 0)
        bridge.goToPage(pageIndex: 3)
        XCTAssertEqual(bridge.currentPage(), 3)
    }

    func testGoToPageOutOfRangeDoesNotCrash() {
        let (bridge, url) = makeBridgeWithPdf(pageCount: 3)
        bridge.openPdf(filePath: url.path, initialPage: 0)
        bridge.goToPage(pageIndex: 99)
        // Must not crash
    }

    // MARK: - Scenario 06-D: Page-change callback

    func testPageChangeCallbackIsInvokedOnSimulatedChange() {
        let bridge = PdfKitNavigatorBridgeImpl()
        var received: Int?
        bridge.setPageChangeCallback(callback: PageChangeCallbackCapture { received = $0 })
        // Drive via test helper on the inner view controller
        let vc = bridge.viewController() as! PdfKitViewController
        vc.simulatePageChange(2)
        XCTAssertEqual(received, 2)
    }

    func testClearingCallbackStopsFiring() {
        let bridge = PdfKitNavigatorBridgeImpl()
        var count = 0
        bridge.setPageChangeCallback(callback: PageChangeCallbackCapture { _ in count += 1 })
        bridge.setPageChangeCallback(callback: nil)
        let vc = bridge.viewController() as! PdfKitViewController
        vc.simulatePageChange(1)
        XCTAssertEqual(count, 0)
    }

    // MARK: - Helpers

    private func makeBridgeWithPdf(pageCount: Int) -> (PdfKitNavigatorBridgeImpl, URL) {
        let url = writeTempPdf(pageCount: pageCount)
        let bridge = PdfKitNavigatorBridgeImpl()
        return (bridge, url)
    }

    /// Creates a minimal in-memory PDF with [pageCount] A4 pages and writes it to a temp file.
    private func writeTempPdf(pageCount: Int) -> URL {
        let doc = PDFDocument()
        for i in 0..<pageCount {
            let page = PDFPage()
            doc.insert(page, at: i)
        }
        let url = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("test_\(pageCount)p.pdf")
        doc.write(to: url)
        return url
    }
}

// MARK: - Test double for IosPdfPageChangeCallback

private final class PageChangeCallbackCapture: NSObject, IosPdfPageChangeCallback {
    private let handler: (Int) -> Void
    init(_ handler: @escaping (Int) -> Void) { self.handler = handler }
    func onPageChanged(page: Int32) { handler(Int(page)) }
}
