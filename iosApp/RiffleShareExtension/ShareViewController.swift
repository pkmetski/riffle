import UIKit
import UniformTypeIdentifiers

private let appGroupId = "group.com.riffle.iosApp"
private let inboxSubdirectory = "ShareInbox"

/// iOS Share/Action extension: accepts epub, pdf, and cbz files shared from other apps and stages
/// them into the App Group shared container. The main Riffle app scans that container on every
/// foreground transition and calls the shared `importIncomingFile` importer for each pending file.
///
/// The extension runs in a separate process and therefore cannot call the Kotlin framework. Its
/// only job is to copy bytes into a location the main app can read.
final class ShareViewController: UIViewController {

    override func beginRequest(with context: NSExtensionContext) {
        guard let items = context.inputItems as? [NSExtensionItem] else {
            context.completeRequest(returningItems: nil)
            return
        }
        let supportedTypes: [UTType] = [
            UTType(filenameExtension: "epub") ?? .data,
            UTType(filenameExtension: "pdf") ?? .pdf,
            UTType(filenameExtension: "cbz") ?? .data,
        ]
        var providers: [NSItemProvider] = []
        for item in items {
            for provider in item.attachments ?? [] {
                for type in supportedTypes where provider.hasItemConformingToTypeIdentifier(type.identifier) {
                    providers.append(provider)
                    break
                }
            }
        }
        guard !providers.isEmpty else {
            context.completeRequest(returningItems: nil)
            return
        }
        let group = DispatchGroup()
        for provider in providers {
            group.enter()
            provider.loadFileRepresentation(forTypeIdentifier: UTType.data.identifier) { url, error in
                defer { group.leave() }
                guard let url, error == nil else { return }
                Self.stageFile(from: url)
            }
        }
        group.notify(queue: .main) {
            context.completeRequest(returningItems: nil)
        }
    }

    private static func stageFile(from url: URL) {
        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: appGroupId
        ) else { return }
        let inbox = container.appendingPathComponent(inboxSubdirectory, isDirectory: true)
        try? FileManager.default.createDirectory(at: inbox, withIntermediateDirectories: true)
        let destination = inbox.appendingPathComponent("\(UUID().uuidString)-\(url.lastPathComponent)")
        _ = url.startAccessingSecurityScopedResource()
        defer { url.stopAccessingSecurityScopedResource() }
        try? FileManager.default.copyItem(at: url, to: destination)
    }
}
