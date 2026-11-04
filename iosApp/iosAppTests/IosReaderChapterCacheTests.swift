import XCTest
@testable import Riffle

// MARK: - IosReaderChapterCacheTests

/// Tests for `IosReaderChapterCache` and `iosReaderPrefsFingerprint`.
/// iOS counterpart of Android's `ReaderChapterCacheTest` and `FormattingPreferencesFingerprintTest`.
final class IosReaderChapterCacheTests: XCTestCase {

    private var tempDir: URL!
    private var cache: IosReaderChapterCache!

    override func setUp() {
        super.setUp()
        tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("IosReaderChapterCacheTests-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        cache = IosReaderChapterCache(cacheDir: tempDir)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: tempDir)
        super.tearDown()
    }

    // MARK: - Cache tests

    func testLoadReturnsNilWhenNoEntry() {
        XCTAssertNil(cache.load(itemId: "item1", chapterHref: "ch1.xhtml", prefsFingerprint: "abc"))
    }

    func testLoadReturnsNilOnItemIdMismatch() {
        let data = Data("hello".utf8)
        cache.save(itemId: "item1", chapterHref: "ch1.xhtml", prefsFingerprint: "abc", xhtmlData: data, heightPx: 1200)
        XCTAssertNil(cache.load(itemId: "item2", chapterHref: "ch1.xhtml", prefsFingerprint: "abc"))
    }

    func testLoadReturnsNilOnPrefsFingerPrintMismatch() {
        let data = Data("hello".utf8)
        cache.save(itemId: "item1", chapterHref: "ch1.xhtml", prefsFingerprint: "abc", xhtmlData: data, heightPx: 1200)
        XCTAssertNil(cache.load(itemId: "item1", chapterHref: "ch1.xhtml", prefsFingerprint: "xyz"))
    }

    func testRoundTrip() {
        let xhtml = Data("<html><body>hello</body></html>".utf8)
        cache.save(itemId: "item1", chapterHref: "ch1.xhtml", prefsFingerprint: "abc", xhtmlData: xhtml, heightPx: 1500)
        let result = cache.load(itemId: "item1", chapterHref: "ch1.xhtml", prefsFingerprint: "abc")
        XCTAssertNotNil(result)
        XCTAssertEqual(result?.data, xhtml)
        XCTAssertEqual(result?.heightPx, 1500)
    }

    func testOverwritesPreviousEntry() {
        let xhtml1 = Data("chapter1".utf8)
        let xhtml2 = Data("chapter2".utf8)
        cache.save(itemId: "item1", chapterHref: "ch1.xhtml", prefsFingerprint: "abc", xhtmlData: xhtml1, heightPx: 1000)
        cache.save(itemId: "item1", chapterHref: "ch2.xhtml", prefsFingerprint: "abc", xhtmlData: xhtml2, heightPx: 2000)
        XCTAssertNil(cache.load(itemId: "item1", chapterHref: "ch1.xhtml", prefsFingerprint: "abc"))
        let result = cache.load(itemId: "item1", chapterHref: "ch2.xhtml", prefsFingerprint: "abc")
        XCTAssertEqual(result?.data, xhtml2)
        XCTAssertEqual(result?.heightPx, 2000)
    }

    // MARK: - Fingerprint tests

    func testFingerprintIsStableAcrossCalls() {
        XCTAssertEqual(makeFingerprint(), makeFingerprint())
    }

    func testFingerprintDiffersOnThemeChange() {
        let light = makeFingerprint(theme: "light")
        let dark = makeFingerprint(theme: "dark")
        XCTAssertNotEqual(light, dark)
    }

    func testFingerprintDiffersOnFontSizeChange() {
        let normal = makeFingerprint(fontSizePercent: 1.0)
        let large = makeFingerprint(fontSizePercent: 1.5)
        XCTAssertNotEqual(normal, large)
    }

    func testFingerprintIsNonEmptyHexString() {
        let fingerprint = makeFingerprint()
        XCTAssertFalse(fingerprint.isEmpty)
        XCTAssertTrue(fingerprint.allSatisfy { $0.isHexDigit }, "Expected hex string, got: \(fingerprint)")
    }

    // MARK: - Helpers

    private func makeFingerprint(
        fontSizePercent: Float = 1.0,
        theme: String = "light",
        fontFamilyCss: String = "",
        lineHeightMultiplier: Float = 1.5,
        pageMargins: Double = 1.0,
        justifyText: Bool = false,
        textColorArgb: Int64 = 0,
        publisherStyles: Bool = false,
        columnCount: Int32 = 1
    ) -> String {
        iosReaderPrefsFingerprint(IosReaderPrefsSnapshot(
            fontSizePercent: fontSizePercent,
            theme: theme,
            fontFamilyCss: fontFamilyCss,
            lineHeightMultiplier: lineHeightMultiplier,
            pageMargins: pageMargins,
            justifyText: justifyText,
            textColorArgb: textColorArgb,
            publisherStyles: publisherStyles,
            columnCount: columnCount
        ))
    }
}
