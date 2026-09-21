import Foundation
import Riffle

/// "Open in Riffle": a book handed to the app from Files, Mail, Safari, a Finder drop, or the
/// share sheet.
///
/// The URL is only usable inside a security scope that ends the moment the handler returns, and
/// the Inbox copy iOS makes for a non-in-place open is deleted after the launch that received it
/// — so the bytes are copied into our own temporary directory first and only then handed to
/// Kotlin. Everything past that point (classification, the copy into "Riffle Imports", the Local
/// Files scan, the snackbar) is the shared importer that Android runs too.
func openIncomingDocument(_ url: URL) {
    guard let staged = stageIncomingDocument(url) else { return }
    IosOpenInKt.importIncomingFile(path: staged.path, displayName: staged.displayName)
}

/// Copies `url` into `directory` (the process temporary directory by default) while holding its
/// security scope, under a name that cannot collide with a concurrent import.
///
/// Returns nil when the bytes cannot be read — a revoked scope, a file that vanished between the
/// system handing us the URL and this call, or a URL with no last path component at all.
/// `directory` is a parameter purely so `OpenInDocumentStagingTests` can point it at a scratch
/// directory it owns.
func stageIncomingDocument(
    _ url: URL,
    into directory: URL = URL(fileURLWithPath: NSTemporaryDirectory())
) -> (path: String, displayName: String)? {
    let scoped = url.startAccessingSecurityScopedResource()
    defer { if scoped { url.stopAccessingSecurityScopedResource() } }

    let displayName = url.lastPathComponent
    guard !displayName.isEmpty else { return nil }
    let destination = directory.appendingPathComponent("\(UUID().uuidString)-\(displayName)")
    do {
        if FileManager.default.fileExists(atPath: destination.path) {
            try FileManager.default.removeItem(at: destination)
        }
        try FileManager.default.copyItem(at: url, to: destination)
    } catch {
        return nil
    }
    return (destination.path, displayName)
}
