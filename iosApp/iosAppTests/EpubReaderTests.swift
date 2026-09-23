import XCTest
import Riffle
import ReadiumShared
import ReadiumNavigator


final class EpubReaderTests: XCTestCase {

    // MARK: - Scenario 03-A / 03-E: Bridge lifecycle

    func testBridgeFactoryCreatesDistinctInstances() {
        // Each call to create() must produce an independent bridge (one per book open).
        let factory = ReadiumEpubNavigatorBridgeFactory()
        let b1 = factory.create()
        let b2 = factory.create()
        XCTAssertFalse(b1 === (b2 as AnyObject), "Factory must return distinct instances")
    }

    func testSnapshotLocatorJsonIsNilBeforeOpen() {
        let bridge = ReadiumEpubNavigatorBridge()
        XCTAssertNil(bridge.snapshotLocatorJson())
    }

    func testDisposeNavigatorClearsCachedLocatorAndIsIdempotent() {
        // disposeNavigator() must drop the cached locator (so a re-opened book doesn't
        // resume from the previous session's stale position) and must tolerate being
        // called more than once.
        let bridge = ReadiumEpubNavigatorBridge()
        bridge.simulateLocatorUpdate("""
        {"href":"/ch1.xhtml","type":"application/xhtml+xml","locations":{"progression":0.5}}
        """)
        XCTAssertNotNil(bridge.snapshotLocatorJson(), "precondition: a locator is cached")

        bridge.disposeNavigator()
        bridge.disposeNavigator()

        // disposeNavigator clears state on the main queue; drain it before asserting.
        let drained = expectation(description: "main queue drained")
        DispatchQueue.main.async { drained.fulfill() }
        wait(for: [drained], timeout: 2)

        XCTAssertNil(bridge.snapshotLocatorJson(),
                     "disposeNavigator must clear the cached locator JSON")
    }

    // MARK: - Scenario 03-C: Callback registration

    func testLocatorCallbackIsInvokedAfterRegistration() {
        let bridge = ReadiumEpubNavigatorBridge()
        var received: String?
        bridge.setLocatorCallback { json in received = json }
        // Simulate what Readium would emit via the delegate — internal helper for testing.
        bridge.simulateLocatorUpdate("""
        {"href":"/ch1.xhtml","type":"application/xhtml+xml","locations":{"progression":0.5}}
        """)
        XCTAssertNotNil(received)
        XCTAssertTrue(received?.contains("ch1.xhtml") == true)
    }

    func testPageLoadCallbackIsInvoked() {
        let bridge = ReadiumEpubNavigatorBridge()
        var loadCount = 0
        bridge.setPageLoadCallback { loadCount += 1 }
        // Drive through the real delegate path (locationDidChange → pageLoadCallback)
        // rather than the test-only simulatePageLoad() helper.
        bridge.simulateLocatorUpdate("""
        {"href":"/ch1.xhtml","type":"application/xhtml+xml","locations":{"progression":0.0}}
        """)
        XCTAssertEqual(loadCount, 1, "pageLoadCallback must fire via locationDidChange, not only via simulatePageLoad")
    }

    func testTapCallbackIsInvoked() {
        let bridge = ReadiumEpubNavigatorBridge()
        var tapped = false
        bridge.setTapCallback { tapped = true }
        bridge.simulateTap()
        XCTAssertTrue(tapped)
    }

    func testClearingCallbacksStopsFiring() {
        let bridge = ReadiumEpubNavigatorBridge()
        var count = 0
        bridge.setLocatorCallback { _ in count += 1 }
        bridge.setLocatorCallback(callback: nil)
        bridge.simulateLocatorUpdate("{}")
        XCTAssertEqual(count, 0)
    }

    // MARK: - Scenario 03-F: isReadable gate — verified via LibraryItem.isReadable in JVM tests
    // (EbookFormat is a sealed class; the gate logic lives in commonMain and is covered by
    //  JVM-level unit tests.  The iOS XCTest suite focuses on the native bridge layer.)

    // MARK: - Scenario 03-G: TOC bridge (no open publication)

    // getTocJson() must return a valid JSON empty array when no EPUB is open so callers can
    // safely parse the result without a nil-check.
    func testGetTocJsonReturnsEmptyArrayWhenNoPublicationIsOpen() {
        let bridge = ReadiumEpubNavigatorBridge()
        XCTAssertEqual(bridge.getTocJson(), "[]")
    }

    // MARK: - Scenario 03-H: Search bridge (no open publication)

    // startSearch() must invoke onDone immediately when there is no open publication so the
    // Kotlin callbackFlow close() is called and the Flow terminates cleanly.
    func testStartSearchCallsOnDoneImmediatelyWhenNoPublicationIsOpen() {
        let bridge = ReadiumEpubNavigatorBridge()
        let done = expectation(description: "onDone invoked")
        bridge.startSearch(query: "test", onBatch: nil, onDone: { done.fulfill() })
        wait(for: [done], timeout: 2)
    }

    // onBatch must never fire when there is no publication — callers should not receive
    // spurious empty batches that could confuse search-result accumulation.
    func testStartSearchDoesNotCallOnBatchWhenNoPublicationIsOpen() {
        let bridge = ReadiumEpubNavigatorBridge()
        var batchCalled = false
        let done = expectation(description: "onDone invoked")
        bridge.startSearch(
            query: "test",
            onBatch: { _ in batchCalled = true },
            onDone: { done.fulfill() }
        )
        wait(for: [done], timeout: 2)
        XCTAssertFalse(batchCalled, "onBatch must not fire when there is no open publication")
    }

    // A second startSearch() call must cancel any in-flight task so there is never more than
    // one concurrent search producing batches for the same query flow.
    func testSecondStartSearchCancelsPreviousNoPublicationCase() {
        let bridge = ReadiumEpubNavigatorBridge()
        var doneCount = 0
        let allDone = expectation(description: "both onDone calls received")
        allDone.expectedFulfillmentCount = 2

        bridge.startSearch(query: "first", onBatch: nil, onDone: {
            doneCount += 1
            allDone.fulfill()
        })
        bridge.startSearch(query: "second", onBatch: nil, onDone: {
            doneCount += 1
            allDone.fulfill()
        })
        wait(for: [allDone], timeout: 3)
        XCTAssertEqual(doneCount, 2)
    }

    // MARK: - Scenario 03-I: Orientation → scroll mode mapping (regression pin)

    // ReaderOrientation.Continuous must map to scrollMode=true (scroll on, not paginated).
    // Regression: previously `scrollMode = prefs.orientation == Vertical` which silently left
    // Continuous in paginated columns with no user signal.
    func testContinuousOrientationMapsToScrollMode() {
        XCTAssertTrue(EpubOrientationMapperKt.epubScrollMode(orientation: .continuous),
                      "Continuous mode must use Readium scroll (not paginated columns)")
    }

    func testVerticalOrientationMapsToScrollMode() {
        XCTAssertTrue(EpubOrientationMapperKt.epubScrollMode(orientation: .vertical),
                      "Vertical mode must use Readium scroll")
    }

    func testHorizontalOrientationMapsToPaginatedMode() {
        XCTAssertFalse(EpubOrientationMapperKt.epubScrollMode(orientation: .horizontal),
                       "Horizontal (paginated) mode must not use Readium scroll")
    }

    // MARK: - #1071 §17: external links and navigator errors were empty delegate stubs

    /// `navigator(_:presentExternalURL:)` was `{}`, so tapping an http(s) link inside a book did
    /// nothing at all. Android hands the URL to `Intent.ACTION_VIEW`; iOS must hand it to
    /// `UIApplication.open`, which the bridge reaches through its injectable `urlOpener`.
    /// Reverting the delegate body to `{}` leaves `opened` empty and fails this test.
    @MainActor
    func testExternalLinkTapIsHandedToTheUrlOpener() {
        let bridge = ReadiumEpubNavigatorBridge()
        var opened: [URL] = []
        bridge.urlOpener = { opened.append($0) }

        let url = URL(string: "https://example.org/reference")!
        bridge.navigator(DummyNavigator(), presentExternalURL: url)

        XCTAssertEqual(opened, [url], "An external link tap must be opened, not swallowed")
    }

    /// `navigator(_:presentError:)` was `{}`. It now forwards the error's description to the
    /// Kotlin error callback, where `ReadiumSwiftNavigator` logs it on RIFFLE_READER.
    /// Reverting the delegate body leaves `received` nil.
    @MainActor
    func testNavigatorErrorsReachTheKotlinErrorCallback() {
        let bridge = ReadiumEpubNavigatorBridge()
        var received: String?
        bridge.setErrorCallback { received = $0 }

        bridge.navigator(DummyNavigator(), presentError: .copyForbidden)

        XCTAssertNotNil(received, "A navigator error must not be swallowed")
        XCTAssertTrue(received?.contains("copyForbidden") == true,
                      "Expected the error description, got \(received ?? "nil")")
    }
}

/// Minimal `Navigator` for the two delegate methods above, both of which ignore the navigator
/// argument. `publication` traps because nothing in these paths reads it.
private final class DummyNavigator: Navigator {
    var publication: Publication { fatalError("DummyNavigator has no publication") }
    var currentLocation: Locator? { nil }
    func go(to locator: Locator, options: NavigatorGoOptions) async -> Bool { false }
    func go(to link: Link, options: NavigatorGoOptions) async -> Bool { false }
    func goForward(options: NavigatorGoOptions) async -> Bool { false }
    func goBackward(options: NavigatorGoOptions) async -> Bool { false }
}
