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
        let prefs = makePrefs()
        XCTAssertEqual(iosReaderPrefsFingerprint(prefs), iosReaderPrefsFingerprint(prefs))
    }

    func testFingerprintDiffersOnThemeChange() {
        let light = makePrefs(theme: "light")
        let dark = makePrefs(theme: "dark")
        XCTAssertNotEqual(iosReaderPrefsFingerprint(light), iosReaderPrefsFingerprint(dark))
    }

    func testFingerprintDiffersOnFontSizeChange() {
        let normal = makePrefs(fontSizePercent: 1.0)
        let large = makePrefs(fontSizePercent: 1.5)
        XCTAssertNotEqual(iosReaderPrefsFingerprint(normal), iosReaderPrefsFingerprint(large))
    }

    func testFingerprintIsNonEmptyHexString() {
        let fingerprint = iosReaderPrefsFingerprint(makePrefs())
        XCTAssertFalse(fingerprint.isEmpty)
        XCTAssertTrue(fingerprint.allSatisfy { $0.isHexDigit }, "Expected hex string, got: \(fingerprint)")
    }

    // MARK: - Helpers

    private func makePrefs(
        fontSizePercent: Float = 1.0,
        theme: String = "light",
        fontFamilyCss: String = "",
        lineHeightMultiplier: Float = 1.5,
        pageMargins: Double = 1.0,
        justifyText: Bool = false
    ) -> IosReaderPreferences {
        IosReaderPreferences(
            fontSizePercent: fontSizePercent,
            scrollMode: true,
            theme: theme,
            fontFamilyCss: fontFamilyCss,
            lineHeightMultiplier: lineHeightMultiplier,
            pageMargins: pageMargins,
            justifyText: justifyText,
            textColorArgb: 0,
            publisherStyles: false,
            columnCount: 1,
            isContinuousMode: true
        )
    }
}
