import Foundation
import UIKit
import WebKit
import Riffle

// MARK: - ElidedPdfBridge

/// Implements IosElidedPdfBridge (from Kotlin shared).
/// Renders the combined annotation HTML to a PDF via WKWebView.createPDF, writes it to a temp
/// file, then presents UIActivityViewController from the key window's root view controller.
@objc class ElidedPdfBridge: NSObject, IosElidedPdfBridge {

    func exportAndShare(html: String, fileName: String) {
        Task { @MainActor in
            guard let pdfData = await Self.renderPdf(html: html) else { return }
            let tempUrl = Self.writeTempFile(data: pdfData, fileName: fileName)
            guard let tempUrl else { return }
            Self.presentShareSheet(fileUrl: tempUrl)
        }
    }

    @MainActor
    private static func renderPdf(html: String) async -> Data? {
        let webView = WKWebView(frame: CGRect(x: 0, y: 0, width: 595, height: 842))
        webView.isHidden = true

        // Attach to a window so the WKWebView can render off-screen.
        let window = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow }
        window?.addSubview(webView)
        defer { webView.removeFromSuperview() }

        await webView.loadHtmlAndWait(html: html)

        let config = WKPDFConfiguration()
        config.rect = CGRect(x: 0, y: 0, width: 595, height: 842)
        return try? await webView.pdf(configuration: config)
    }

    private static func writeTempFile(data: Data, fileName: String) -> URL? {
        let tempDir = FileManager.default.temporaryDirectory
        let fileUrl = tempDir.appendingPathComponent(fileName)
        do {
            try data.write(to: fileUrl, options: .atomic)
            return fileUrl
        } catch {
            return nil
        }
    }

    @MainActor
    private static func presentShareSheet(fileUrl: URL) {
        let activity = UIActivityViewController(
            activityItems: [fileUrl],
            applicationActivities: nil
        )
        let rootVc = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow }?
            .rootViewController
        rootVc?.present(activity, animated: true)
    }
}

// MARK: - WKWebView loading helper

private extension WKWebView {
    /// Loads HTML string and suspends until `webView(_:didFinish:)` fires.
    func loadHtmlAndWait(html: String) async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            let delegate = FinishDelegate(continuation: continuation)
            self.navigationDelegate = delegate
            objc_setAssociatedObject(
                self,
                &AssociatedKey.delegate,
                delegate,
                .OBJC_ASSOCIATION_RETAIN_NONATOMIC
            )
            self.loadHTMLString(html, baseURL: nil)
        }
    }
}

private enum AssociatedKey {
    static var delegate: UInt8 = 0
}

private class FinishDelegate: NSObject, WKNavigationDelegate {
    private let continuation: CheckedContinuation<Void, Never>
    private var resumed = false

    init(continuation: CheckedContinuation<Void, Never>) {
        self.continuation = continuation
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard !resumed else { return }
        resumed = true
        continuation.resume()
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        guard !resumed else { return }
        resumed = true
        continuation.resume()
    }
}
