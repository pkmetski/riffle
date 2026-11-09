import PDFKit
import Riffle
import XCTest

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
        bridge.setTapCallback(handler: callback)
        bridge.simulateTap()
        XCTAssertEqual(tapCount, 1,
                       "setTapCallback must fire when a tap is simulated")
    }

    func testTapCallbackIsNotInvokedAfterClear() {
        let bridge = PdfKitNavigatorBridgeImpl()
        var tapCount = 0
        let callback = FakePdfTapCallback { tapCount += 1 }
        bridge.setTapCallback(handler: callback)
        bridge.setTapCallback(handler: nil)
        bridge.simulateTap()
        XCTAssertEqual(tapCount, 0,
                       "Cleared tap callback must not fire on subsequent taps")
    }

    func testDisposePdfClearsTapCallback() {
        let bridge = PdfKitNavigatorBridgeImpl()
        var tapCount = 0
        let callback = FakePdfTapCallback { tapCount += 1 }
        bridge.setTapCallback(handler: callback)
        bridge.disposePdf()
        bridge.simulateTap()
        XCTAssertEqual(tapCount, 0,
                       "disposePdf must clear the tap callback")
    }

    func testSetPageSpacingUpdatesPageBreakMargins() {
        let bridge = PdfKitNavigatorBridgeImpl()
        bridge.setPageSpacing(spacingPts: 24.0)
        XCTAssertEqual(bridge.currentPageSpacingTop(), 24.0,
                       "setPageSpacing must propagate spacing to PDFView.pageBreakMargins.top")
    }

    func testSetPageSpacingZeroClearsMargins() {
        let bridge = PdfKitNavigatorBridgeImpl()
        bridge.setPageSpacing(spacingPts: 32.0)
        bridge.setPageSpacing(spacingPts: 0.0)
        XCTAssertEqual(bridge.currentPageSpacingTop(), 0.0,
                       "setPageSpacing(0) must clear the top page-break margin")
    }

    // R-P1: horizontal insets must mirror Android's (margins-1)*step/2 horizontal padding.
    func testSetHorizontalInsetsUpdatesLeadingConstraint() {
        let bridge = PdfKitNavigatorBridgeImpl()
        bridge.setHorizontalInsets(insetPts: 16.0)
        XCTAssertEqual(bridge.currentLeadingInset(), 16.0,
                       "setHorizontalInsets must propagate the inset to the PDFView leading constraint")
    }

    func testSetHorizontalInsetsZeroClearsInset() {
        let bridge = PdfKitNavigatorBridgeImpl()
        bridge.setHorizontalInsets(insetPts: 16.0)
        bridge.setHorizontalInsets(insetPts: 0.0)
        XCTAssertEqual(bridge.currentLeadingInset(), 0.0,
                       "setHorizontalInsets(0) must clear the leading inset")
    }

    // R-P2: openPdf must return false when the file cannot be opened, not silently succeed.
    func testOpenPdfReturnsFalseForMissingFile() {
        let bridge = PdfKitNavigatorBridgeImpl()
        let opened = bridge.openPdf(filePath: "/nonexistent/path/to.pdf", initialPage: 0)
        XCTAssertFalse(opened, "openPdf must return false when PDFDocument cannot open the file")
    }

    func testOpenPdfReturnsTrueForValidDocument() throws {
        let document = PDFDocument()
        let page = PDFPage()
        document.insert(page, at: 0)
        let tempUrl = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension("pdf")
        XCTAssertTrue(document.write(to: tempUrl))
        defer { try? FileManager.default.removeItem(at: tempUrl) }

        let bridge = PdfKitNavigatorBridgeImpl()
        let opened = bridge.openPdf(filePath: tempUrl.path, initialPage: 0)
        XCTAssertTrue(opened, "openPdf must return true when the PDF document opens successfully")
        bridge.disposePdf()
    }

    func testOutlineReturnsEmptyForDocumentWithoutOutline() {
        let bridge = PdfKitNavigatorBridgeImpl()
        // No document opened — bridge should return an empty outline, not crash.
        let outline = bridge.outline()
        XCTAssertTrue(outline.isEmpty,
                      "outline() must return empty list when no document is open")
    }

    // Regression for #1285: buildOutlineNodes previously dropped entire subtrees of container
    // nodes (nodes with children but no destination/page) because the guard dropped `continue`d
    // before recursing. Fixed so container nodes with children are still emitted as wrapper nodes
    // using the first child's pageIndex.
    func testOutlineIncludesContainerNodeChildren() throws {
        let document = PDFDocument()
        let page = PDFPage()
        document.insert(page, at: 0)

        let root = PDFOutline()
        let section = PDFOutline()
        section.label = "Section A"
        let chapter = PDFOutline()
        chapter.label = "Chapter 1"
        chapter.destination = PDFDestination(page: page, at: .zero)
        section.insertChild(chapter, at: 0)
        root.insertChild(section, at: 0)
        document.outlineRoot = root

        let tempUrl = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension("pdf")
        XCTAssertTrue(document.write(to: tempUrl), "Must be able to write PDF to temp file")
        defer { try? FileManager.default.removeItem(at: tempUrl) }

        let bridge = PdfKitNavigatorBridgeImpl()
        bridge.openPdf(filePath: tempUrl.path, initialPage: 0)
        let outline = bridge.outline()

        XCTAssertFalse(outline.isEmpty, "Outline must be non-empty: container node subtree must not be dropped")
        let topLevel = outline[0]
        XCTAssertEqual(topLevel.title, "Section A", "Container node must appear at the top level")
        XCTAssertFalse(topLevel.children.isEmpty, "Container node must carry its child Chapter 1")
        XCTAssertEqual(topLevel.children[0].title, "Chapter 1")
        bridge.disposePdf()
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
