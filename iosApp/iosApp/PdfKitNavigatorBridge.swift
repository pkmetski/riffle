import PDFKit
import UIKit
import Riffle

/// Swift implementation of IosPdfNavigatorBridge, backed by PDFKit's PDFView.
/// One instance per PDF reader open — created by PdfKitNavigatorBridgeFactoryImpl.
@objc final class PdfKitNavigatorBridgeImpl: NSObject, IosPdfNavigatorBridge {

    private let pdfViewController = PdfKitViewController()
    private var pageChangeCallback: (any IosPdfPageChangeCallback)?
    private var tapCallback: (any IosPdfTapCallback)?

    // MARK: - IosPdfNavigatorBridge

    func viewController() -> UIViewController {
        pdfViewController
    }

    func openPdf(filePath: String, initialPage: Int32) {
        let url = URL(fileURLWithPath: filePath)
        guard let document = PDFDocument(url: url) else { return }
        pdfViewController.load(document: document, initialPage: Int(initialPage))
        pdfViewController.onPageChanged = { [weak self] page in
            self?.pageChangeCallback?.onPageChanged(page: Int32(page))
        }
        pdfViewController.onTap = { [weak self] in
            self?.tapCallback?.onTap()
        }
    }

    func currentPage() -> Int32 {
        Int32(pdfViewController.currentPageIndex())
    }

    func pageCount() -> Int32 {
        Int32(pdfViewController.pageCount())
    }

    func goToPage(pageIndex: Int32) {
        pdfViewController.goToPage(index: Int(pageIndex))
    }

    func setPageSpacing(spacingPts: Double) {
        pdfViewController.setPageSpacing(CGFloat(spacingPts))
    }

    func setPageChangeCallback(callback: (any IosPdfPageChangeCallback)?) {
        pageChangeCallback = callback
    }

    func setTapCallback(handler: (any IosPdfTapCallback)?) {
        tapCallback = handler
    }

    func outline() -> [PdfOutlineNode] {
        guard let root = pdfViewController.documentOutline() else { return [] }
        return buildOutlineNodes(from: root)
    }

    func disposePdf() {
        pdfViewController.onPageChanged = nil
        pdfViewController.onTap = nil
        pageChangeCallback = nil
        tapCallback = nil
        pdfViewController.close()
    }

    // MARK: - Private

    private func buildOutlineNodes(from node: PDFOutline) -> [PdfOutlineNode] {
        var result: [PdfOutlineNode] = []
        for idx in 0..<node.numberOfChildren {
            guard let child = node.child(at: idx) else { continue }
            let children = buildOutlineNodes(from: child)
            if let destination = child.destination,
               let page = destination.page,
               let document = pdfViewController.pdfDocument {
                let pageIndex = document.index(for: page)
                result.append(PdfOutlineNode(
                    title: child.label ?? "",
                    pageIndex: Int32(pageIndex),
                    children: children
                ))
            } else if !children.isEmpty {
                result.append(PdfOutlineNode(
                    title: child.label ?? "",
                    pageIndex: children.first?.pageIndex ?? 0,
                    children: children
                ))
            }
        }
        return result
    }
}

// MARK: - Test helpers

extension PdfKitNavigatorBridgeImpl {
    /// Simulate a page change as if PDFKit fired PDFViewPageChanged. Used in unit tests only.
    @objc func simulatePageChange(_ pageIndex: Int) {
        pageChangeCallback?.onPageChanged(page: Int32(pageIndex))
    }

    /// Simulate a single tap as if the user tapped the PDFView. Used in unit tests only.
    @objc func simulateTap() {
        tapCallback?.onTap()
    }

    /// Returns the top page-break margin currently set on the PDF view. Used in unit tests only.
    @objc func currentPageSpacingTop() -> CGFloat {
        pdfViewController.currentPageBreakMarginTop
    }
}

// MARK: - Factory

@objc final class PdfKitNavigatorBridgeFactoryImpl: NSObject, IosPdfNavigatorBridgeFactory {
    func create() -> any IosPdfNavigatorBridge {
        PdfKitNavigatorBridgeImpl()
    }
}

// MARK: - PDFKit view controller

/// UIViewController that hosts a full-screen PDFView.
final class PdfKitViewController: UIViewController {

    var onPageChanged: ((Int) -> Void)?
    var onTap: (() -> Void)?

    private let pdfView: PDFView = {
        let view = PDFView()
        view.displayMode = .singlePageContinuous
        view.autoScales = true
        view.translatesAutoresizingMaskIntoConstraints = false
        return view
    }()

    var pdfDocument: PDFDocument? { pdfView.document }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        view.addSubview(pdfView)
        NSLayoutConstraint.activate([
            pdfView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            pdfView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            pdfView.topAnchor.constraint(equalTo: view.topAnchor),
            pdfView.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(pageDidChange),
            name: .PDFViewPageChanged,
            object: pdfView,
        )

        let tap = UITapGestureRecognizer(target: self, action: #selector(handleTap))
        // cancelsTouchesInView = false so PDFKit's own tap handling still fires.
        tap.cancelsTouchesInView = false
        pdfView.addGestureRecognizer(tap)
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    func load(document: PDFDocument, initialPage: Int) {
        pdfView.document = document
        if initialPage > 0, let page = document.page(at: initialPage) {
            pdfView.go(to: page)
        }
    }

    func currentPageIndex() -> Int {
        guard let page = pdfView.currentPage,
              let doc = pdfView.document else { return 0 }
        return doc.index(for: page)
    }

    func pageCount() -> Int {
        pdfView.document?.pageCount ?? 0
    }

    func goToPage(index: Int) {
        guard let doc = pdfView.document, let page = doc.page(at: index) else { return }
        pdfView.go(to: page)
    }

    func setPageSpacing(_ spacingPts: CGFloat) {
        pdfView.pageBreakMargins = UIEdgeInsets(top: spacingPts, left: 0, bottom: spacingPts, right: 0)
    }

    func documentOutline() -> PDFOutline? {
        pdfView.document?.outlineRoot
    }

    func close() {
        pdfView.document = nil
    }

    @objc private func pageDidChange() {
        onPageChanged?(currentPageIndex())
    }

    @objc private func handleTap() {
        onTap?()
    }

    // MARK: - Test helpers

    @objc func simulatePageChange(_ pageIndex: Int) {
        onPageChanged?(pageIndex)
    }

    @objc func simulateTap() {
        onTap?()
    }

    @objc var currentPageBreakMarginTop: CGFloat {
        pdfView.pageBreakMargins.top
    }
}
