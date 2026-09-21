import XCTest

/// The Swift half of "Open in Riffle".
///
/// `Info.plist` now declares `CFBundleDocumentTypes`, the EPUB/CBZ/CBR
/// `UTImportedTypeDeclarations`, `LSSupportsOpeningDocumentsInPlace` and `UIFileSharingEnabled`,
/// and `RiffleApp` routes `onOpenURL` here. Everything after the staging copy is shared Kotlin
/// (`SharedOpenInImporter`, covered by `OpenInImporterTest` on both platforms) — what only Swift
/// can do, and therefore what only an XCTest can pin, is getting the bytes out of a
/// security-scoped URL that stops being readable the moment the handler returns.
final class OpenInDocumentStagingTests: XCTestCase {

    private var scratch: URL!

    override func setUpWithError() throws {
        scratch = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("open-in-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: scratch)
    }

    private func makeIncoming(named name: String, contents: String = "PK\u{3}\u{4}") throws -> URL {
        let inbox = scratch.appendingPathComponent("inbox", isDirectory: true)
        try FileManager.default.createDirectory(at: inbox, withIntermediateDirectories: true)
        let url = inbox.appendingPathComponent(name)
        try contents.data(using: .utf8)!.write(to: url)
        return url
    }

    func testStagingCopiesTheBytesOutOfTheIncomingURL() throws {
        let incoming = try makeIncoming(named: "Novel.epub", contents: "book-bytes")
        let destination = scratch.appendingPathComponent("staged", isDirectory: true)
        try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)

        let staged = stageIncomingDocument(incoming, into: destination)

        let result = try XCTUnwrap(staged, "a readable file must stage")
        XCTAssertEqual(result.displayName, "Novel.epub")
        XCTAssertTrue(FileManager.default.fileExists(atPath: result.path))
        XCTAssertEqual(try String(contentsOfFile: result.path, encoding: .utf8), "book-bytes")
    }

    func testStagingSurvivesTheIncomingFileBeingDeletedAfterwards() throws {
        // This is the whole point of staging: the Inbox copy iOS makes is removed after the
        // launch that received it, and an in-place URL stops being readable when the security
        // scope closes. The import runs asynchronously, so it must not depend on either.
        let incoming = try makeIncoming(named: "Novel.epub", contents: "book-bytes")
        let destination = scratch.appendingPathComponent("staged", isDirectory: true)
        try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)

        let result = try XCTUnwrap(stageIncomingDocument(incoming, into: destination))
        try FileManager.default.removeItem(at: incoming)

        XCTAssertEqual(try String(contentsOfFile: result.path, encoding: .utf8), "book-bytes")
    }

    func testStagingTwoFilesWithTheSameNameDoesNotCollide() throws {
        let destination = scratch.appendingPathComponent("staged", isDirectory: true)
        try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)

        let first = try XCTUnwrap(
            stageIncomingDocument(try makeIncoming(named: "Novel.epub", contents: "one"), into: destination)
        )
        let inboxTwo = scratch.appendingPathComponent("inbox2", isDirectory: true)
        try FileManager.default.createDirectory(at: inboxTwo, withIntermediateDirectories: true)
        let secondSource = inboxTwo.appendingPathComponent("Novel.epub")
        try "two".data(using: .utf8)!.write(to: secondSource)
        let second = try XCTUnwrap(stageIncomingDocument(secondSource, into: destination))

        XCTAssertNotEqual(first.path, second.path, "a second import must not overwrite the first")
        XCTAssertEqual(try String(contentsOfFile: first.path, encoding: .utf8), "one")
        XCTAssertEqual(try String(contentsOfFile: second.path, encoding: .utf8), "two")
        XCTAssertEqual(first.displayName, second.displayName, "the user-facing name is unchanged")
    }

    func testStagingReturnsNilForAnUnreadableURLRatherThanCrashing() throws {
        let missing = scratch.appendingPathComponent("inbox/does-not-exist.epub")
        let destination = scratch.appendingPathComponent("staged", isDirectory: true)
        try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)

        XCTAssertNil(stageIncomingDocument(missing, into: destination))
    }

}
