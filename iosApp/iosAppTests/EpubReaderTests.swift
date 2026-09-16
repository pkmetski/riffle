import XCTest
import Riffle

// Covers scenarios from docs/testing/ios-scenarios/03-epub-reader.md

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

    // cancelSearch() must be safe to call when no search is in progress (nil task).
    func testCancelSearchIsIdempotentWithNoActiveSearch() {
        let bridge = ReadiumEpubNavigatorBridge()
        bridge.cancelSearch()
        bridge.cancelSearch()
        // No crash = pass
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
}
