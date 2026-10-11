import XCTest
@testable import Riffle

/// Drives `ElidedPdfBridge.renderPdf` — the Share-as-PDF path of the elided Annotations View.
/// It must render through WebKit's Objective-C `createPDFWithConfiguration:completionHandler:`;
/// the Swift overlay equivalents (`pdf(configuration:)`, `WKPDFConfiguration.rect`) link
/// libswiftWebKit.dylib, which iOS 18 runtimes lack, and the app then aborts in dyld at launch.
/// The iOS harness job's overlay check guards the link; this pins that the ObjC path produces
/// a real PDF.
final class ElidedPdfBridgeTests: XCTestCase {

    @MainActor
    func testRenderPdfProducesPdfDocumentData() async throws {
        let html = "<html><body><h1>Highlights</h1><p>Hello, PDF.</p></body></html>"

        let data = await ElidedPdfBridge.renderPdf(html: html)

        let pdf = try XCTUnwrap(data, "renderPdf must return PDF data for simple HTML")
        let header = String(decoding: pdf.prefix(5), as: UTF8.self)
        XCTAssertEqual(header, "%PDF-", "rendered data must be a PDF document, got header \(header)")
        XCTAssertGreaterThan(pdf.count, 1_000, "a rendered page should not be an empty PDF shell")
    }
}
