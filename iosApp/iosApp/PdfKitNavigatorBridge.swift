import PDFKit
import UIKit
import Riffle

/// Swift implementation of IosPdfNavigatorBridge, backed by PDFKit's PDFView.
/// One instance per PDF reader open — created by PdfKitNavigatorBridgeFactoryImpl.
@objc final class PdfKitNavigatorBridgeImpl: NSObject, IosPdfNavigatorBridge {

    private let pdfViewController = PdfKitViewController()
    private var pageChangeCallback: (any IosPdfPageChangeCallback)?

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

    func setPageChangeCallback(callback: (any IosPdfPageChangeCallback)?) {
        pageChangeCallback = callback
    }

    func disposePdf() {
        pdfViewController.onPageChanged = nil
        pageChangeCallback = nil
        pdfViewController.close()
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

    private let pdfView: PDFView = {
        let view = PDFView()
        view.displayMode = .singlePageContinuous
        view.autoScales = true
        view.translatesAutoresizingMaskIntoConstraints = false
        return view
    }()

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

    func close() {
        pdfView.document = nil
    }

    @objc private func pageDidChange() {
        onPageChanged?(currentPageIndex())
    }

    // MARK: - Test helpers

    @objc func simulatePageChange(_ pageIndex: Int) {
        onPageChanged?(pageIndex)
    }
}
