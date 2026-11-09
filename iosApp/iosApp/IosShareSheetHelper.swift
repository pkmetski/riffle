import Foundation
import UIKit
import Riffle

/// Implements IosShareSheetPresenter (from Kotlin shared).
/// Wraps file paths and plain text in UIActivityItems and presents UIActivityViewController
/// from the key window's root view controller.
@objc class IosShareSheetHelper: NSObject, IosShareSheetPresenter {

    func shareFiles(filePaths: [String], subject: String?) {
        let urls = filePaths.compactMap { URL(fileURLWithPath: $0) }
        guard !urls.isEmpty else { return }
        Task { @MainActor in
            var items: [Any] = urls
            if let subject {
                items.insert(subject, at: 0)
            }
            Self.present(items: items)
        }
    }

    func shareText(text: String, subject: String?) {
        Task { @MainActor in
            var items: [Any] = [text]
            if let subject {
                items.insert(subject, at: 0)
            }
            Self.present(items: items)
        }
    }

    @MainActor
    private static func present(items: [Any]) {
        let activity = UIActivityViewController(activityItems: items, applicationActivities: nil)
        let rootVc = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow }?
            .rootViewController
        rootVc?.present(activity, animated: true)
    }
}
