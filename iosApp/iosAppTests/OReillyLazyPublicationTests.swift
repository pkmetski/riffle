import XCTest
import Riffle
import ReadiumShared

// Covers scenarios from docs/testing/ios-scenarios/20-oreilly-lazy-publication.md

// MARK: - Stub fetcher

/// Stub IosLazyChapterFetcher for testing: records calls and returns canned responses.
final class StubLazyChapterFetcher: NSObject, IosLazyChapterFetcher {

    var chapterPathsByFullPath: [String: String] = [:]
    var assetPathsByFullPath: [String: String] = [:]
    var fetchChapterCallCount = 0
    var fetchAssetCallCount = 0
    var prefetchNextCallIndices: [Int32] = []
    var disposeCallCount = 0

    func fetchChapterXhtmlPath(
        fullPath: String,
        expectedByteSize: Int64,
        completion: ((String?) -> Void)?
    ) {
        fetchChapterCallCount += 1
        completion?(chapterPathsByFullPath[fullPath])
    }

    func fetchAssetPath(fullPath: String, completion: ((String?) -> Void)?) {
        fetchAssetCallCount += 1
        completion?(assetPathsByFullPath[fullPath])
    }

    func prefetchNext(currentIndex: Int32) {
        prefetchNextCallIndices.append(currentIndex)
    }

    func dispose() {
        disposeCallCount += 1
    }
}

// MARK: - Tests

final class OReillyLazyPublicationTests: XCTestCase {

    // MARK: - Scenario 20-A: shape JSON decoding

    private let validShapeJson = """
    {
      "bookId": "9781234567890",
      "identifier": "urn:orm:book:9781234567890",
      "title": "Swift Programming",
      "language": "en",
      "absoluteFilesPrefix": "https://oreilly.com/api/v2/epubs/urn:orm:book:9781234567890/files/",
      "pathFilesPrefix": "/api/v2/epubs/urn:orm:book:9781234567890/files/",
      "cssFullPaths": ["styles/main.css"],
      "spine": [
        {"index": 0, "fullPath": "xhtml/ch01.xhtml", "title": "Introduction", "declaredByteSize": 10000},
        {"index": 1, "fullPath": "xhtml/ch02.xhtml", "title": "Basics",        "declaredByteSize": 15000}
      ]
    }
    """

    func testBuildSucceedsWithValidShapeJson() throws {
        let fetcher = StubLazyChapterFetcher()
        let result = try OReillyPublicationBuilder.build(shapeJson: validShapeJson, fetcher: fetcher)
        XCTAssertEqual(result.publication.metadata.title, "Swift Programming")
    }

    func testBuildFailsWithInvalidJson() {
        let fetcher = StubLazyChapterFetcher()
        XCTAssertThrowsError(
            try OReillyPublicationBuilder.build(shapeJson: "not json", fetcher: fetcher)
        )
    }

    // MARK: - Scenario 20-F: special-character title survives JSON round-trip

    func testBuildSucceedsWithSpecialCharactersInTitle() throws {
        // Title contains a double-quote — must survive the Kotlin-side escaping and the Swift
        // JSONDecoder round-trip. If serializeShape's jsonStr() is incomplete, this throws.
        let specialJson = """
        {
          "bookId": "9781234567890",
          "identifier": "urn:orm:book:9781234567890",
          "title": "Swift \\"Programming\\": A Guide",
          "language": "en",
          "absoluteFilesPrefix": "https://example.com/files/",
          "pathFilesPrefix": "/api/files/",
          "cssFullPaths": [],
          "spine": [
            {"index": 0, "fullPath": "ch01.xhtml", "title": "Intro", "declaredByteSize": 5000}
          ]
        }
        """
        let fetcher = StubLazyChapterFetcher()
        let result = try OReillyPublicationBuilder.build(shapeJson: specialJson, fetcher: fetcher)
        XCTAssertTrue(result.publication.metadata.title.contains("Programming"))
    }

    // MARK: - Scenario 20-G: estimatedLength is nil when declaredByteSize is zero

    func testEstimatedLengthIsNilWhenDeclaredByteSizeIsZero() async throws {
        let zeroSizeJson = """
        {
          "bookId": "b1", "identifier": "urn:b1", "title": "T", "language": "en",
          "absoluteFilesPrefix": "", "pathFilesPrefix": "", "cssFullPaths": [],
          "spine": [{"index": 0, "fullPath": "ch01.xhtml", "title": "Ch1", "declaredByteSize": 0}]
        }
        """
        let fetcher = StubLazyChapterFetcher()
        let (_, container) = try OReillyPublicationBuilder.build(shapeJson: zeroSizeJson, fetcher: fetcher)
        guard let url = AnyURL(path: "ch01.xhtml"),
              let resource = container[url] else {
            XCTFail("Expected a resource for ch01.xhtml"); return
        }
        let result = await resource.estimatedLength()
        guard case .success(let length) = result else {
            XCTFail("Expected success"); return
        }
        XCTAssertNil(length, "estimatedLength must be nil when declaredByteSize is 0")
    }

    func testContainerEntriesMatchSpine() throws {
        let fetcher = StubLazyChapterFetcher()
        let (_, container) = try OReillyPublicationBuilder.build(shapeJson: validShapeJson, fetcher: fetcher)
        XCTAssertEqual(container.entries.count, 2)
    }

    // MARK: - Scenario 20-B: URL stripping

    func testExtractRelativePathStripsReadiumPackagePrefix() {
        let result = OReillyLazyContainer.extractRelativePath("https://readium_package/assets/cover.png")
        XCTAssertEqual(result, "assets/cover.png")
    }

    func testExtractRelativePathStripsLeadingSlash() {
        let result = OReillyLazyContainer.extractRelativePath("/xhtml/ch01.xhtml")
        XCTAssertEqual(result, "xhtml/ch01.xhtml")
    }

    func testExtractRelativePathLeavesRelativePathUnchanged() {
        let result = OReillyLazyContainer.extractRelativePath("images/fig.png")
        XCTAssertEqual(result, "images/fig.png")
    }

    // MARK: - Scenario 20-C: fetcher delegation

    func testChapterReadInvokesFetcher() async throws {
        // Arrange: write a known XHTML to a temp file and point the stub at it.
        let tmpDir = NSTemporaryDirectory()
        let tmpPath = "\(tmpDir)ch01_test.xhtml"
        let xhtml = "<html><body><p>Hello</p></body></html>"
        try xhtml.write(toFile: tmpPath, atomically: true, encoding: .utf8)
        defer { try? FileManager.default.removeItem(atPath: tmpPath) }

        let fetcher = StubLazyChapterFetcher()
        fetcher.chapterPathsByFullPath["xhtml/ch01.xhtml"] = tmpPath

        let (_, container) = try OReillyPublicationBuilder.build(shapeJson: validShapeJson, fetcher: fetcher)

        guard let chUrl = AnyURL(path: "xhtml/ch01.xhtml"),
              let resource = container[chUrl] else {
            XCTFail("Container should return a resource for ch01.xhtml")
            return
        }
        let result = await resource.read(range: nil)

        guard case .success(let data) = result else {
            XCTFail("Expected success reading chapter resource")
            return
        }
        XCTAssertEqual(String(data: data, encoding: .utf8), xhtml)
        XCTAssertEqual(fetcher.fetchChapterCallCount, 1)
    }

    func testChapterReadReturnsFailureWhenFetcherReturnsNil() async throws {
        let fetcher = StubLazyChapterFetcher()
        // No path registered → fetcher returns nil.
        let (_, container) = try OReillyPublicationBuilder.build(shapeJson: validShapeJson, fetcher: fetcher)

        guard let chUrl2 = AnyURL(path: "xhtml/ch01.xhtml"),
              let resource = container[chUrl2] else {
            XCTFail("Container should return a resource (not nil) even for un-cached chapters")
            return
        }
        let result = await resource.read(range: nil)
        guard case .failure = result else {
            XCTFail("Expected failure when fetcher returns nil")
            return
        }
    }

    // MARK: - Scenario 20-D: openLazyEpub bridge wiring

    func testOpenLazyEpubDoesNotCrash() {
        let bridge = ReadiumEpubNavigatorBridge()
        let fetcher = StubLazyChapterFetcher()
        // openLazyEpub dispatches on the main queue; just verify no crash during dispatch.
        bridge.openLazyEpub(shapeJson: validShapeJson, locatorJson: nil, fetcher: fetcher)
    }
}
