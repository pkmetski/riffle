import XCTest
@testable import Riffle

// Tests for the elided Annotations View iOS path — IosElidedEpubAssembler and
// ReadiumEpubNavigatorBridge.openSyntheticEpub.
//
// Scope note: the chapter-grouping logic, OPF/nav XML generation, and UI-suppression
// decisions all live in feature:reader commonMain and run on iOS via
// :feature:reader:iosSimulatorArm64Test. This file covers the iOS-only portions:
//   1. IosElidedEpubAssembler returns nil for chapters with no highlights.
//   2. IosElidedEpubAssembler writes a valid directory tree to NSTemporaryDirectory when
//      highlights are present.
//   3. ReadiumEpubNavigatorBridge.openSyntheticEpub accepts the path without crashing.

final class ElidedAnnotationsViewTests: XCTestCase {

    // MARK: - Helpers

    private func fakeItem(id: String = "book1", title: String = "My Book") -> Riffle.LibraryItem {
        Riffle.LibraryItem(
            id: id,
            libraryId: "lib1",
            title: title,
            author: "Author",
            coverUrl: nil,
            readingProgress: 0,
            isCached: false,
            isDownloaded: false,
            ebookFormat: .epub,
            ebookFileIno: nil,
            hasAudio: false,
            audioDurationSec: 0,
            description: nil,
            seriesName: nil,
            publishedYear: nil,
            genres: [],
            publisher: nil,
            language: nil,
            lastOpenedAt: nil,
            addedAt: nil,
            isbn: nil,
            asin: nil,
            sourceId: "src",
            pageCount: nil
        )
    }

    /// Returns a minimal AnnotationEntity for a highlight, providing all Kotlin constructor
    /// parameters (Kotlin/Native does not expose default values to Swift).
    private func fakeHighlight(id: String = "h1", chapterHref: String = "ch1.xhtml") -> AnnotationEntity {
        AnnotationEntity(
            id: id,
            sourceId: "src",
            itemId: "book1",
            type: AnnotationEntity.companion.TYPE_HIGHLIGHT,
            cfi: "epubcfi(/6/4!/4,/1,/2)",
            color: "yellow",
            note: nil,
            textSnippet: "Hello world",
            textBefore: "",
            textAfter: "",
            chapterHref: chapterHref,
            spineIndex: 0,
            progression: 0.1,
            bookmarkTitle: "",
            createdAt: 1000,
            updatedAt: 1000,
            originDeviceId: "",
            lastModifiedByDeviceId: "",
            deleted: false,
            lastSyncedAt: 0,
            embeddedFigures: nil,
            imageHref: nil,
            imageSvg: nil,
            imageBytes: nil,
            originFontFamily: nil,
            emphasisStyles: nil,
            textSnippetHtml: nil,
            fragmentAnchor: nil
        )
    }

    // MARK: - IosElidedEpubAssembler

    func testAssemblerReturnsNilForEmptyChapters() {
        let result = IosElidedEpubAssembler.shared.assemble(item: fakeItem(), chapters: [])
        XCTAssertNil(result, "assemble should return nil when no chapters provided")
    }

    func testAssemblerReturnsNilForChaptersWithNoHighlights() {
        let emptyChapter = ChapterElision(href: "ch1.xhtml", title: "Chapter 1", highlights: [])
        let result = IosElidedEpubAssembler.shared.assemble(item: fakeItem(), chapters: [emptyChapter])
        XCTAssertNil(result, "assemble should return nil when all chapters have empty highlights")
    }

    func testAssemblerCreatesDirectoryWithRequiredFiles() {
        let chapters = [ChapterElision(href: "ch1.xhtml", title: "Chapter 1", highlights: [fakeHighlight()])]
        guard let dirPath = IosElidedEpubAssembler.shared.assemble(item: fakeItem(), chapters: chapters) else {
            XCTFail("assemble returned nil for non-empty chapters")
            return
        }
        let fm = FileManager.default
        XCTAssertTrue(fm.fileExists(atPath: dirPath + "/mimetype"),
                      "mimetype file must exist")
        XCTAssertTrue(fm.fileExists(atPath: dirPath + "/META-INF/container.xml"),
                      "META-INF/container.xml must exist")
        XCTAssertTrue(fm.fileExists(atPath: dirPath + "/content.opf"),
                      "content.opf must exist")
        XCTAssertTrue(fm.fileExists(atPath: dirPath + "/nav.xhtml"),
                      "nav.xhtml must exist")
        XCTAssertTrue(fm.fileExists(atPath: dirPath + "/highlights/ch0.xhtml"),
                      "highlights/ch0.xhtml must exist")
    }

    func testAssemblerMimetypeContent() throws {
        let chapters = [ChapterElision(href: "ch1.xhtml", title: "Chapter 1", highlights: [fakeHighlight()])]
        guard let dirPath = IosElidedEpubAssembler.shared.assemble(item: fakeItem(), chapters: chapters) else {
            XCTFail("assemble returned nil")
            return
        }
        let data = try Data(contentsOf: URL(fileURLWithPath: dirPath + "/mimetype"))
        let content = String(data: data, encoding: .utf8)
        XCTAssertEqual("application/epub+zip", content,
                       "mimetype must contain exactly application/epub+zip")
    }

    // MARK: - openSyntheticEpub bridge smoke test

    func testOpenSyntheticEpubDoesNotCrashWithNonExistentPath() {
        // openSyntheticEpub is async and swallows errors internally; calling it with a
        // non-existent path must not crash the test process.
        let bridge = ReadiumEpubNavigatorBridge()
        bridge.openSyntheticEpub(dirPath: "/tmp/does_not_exist_riffle_test", locatorJson: nil)
        let expectation = expectation(description: "settle")
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { expectation.fulfill() }
        waitForExpectations(timeout: 1.0)
    }
}
