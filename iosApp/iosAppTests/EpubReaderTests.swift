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
        bridge.simulatePageLoad()
        XCTAssertEqual(loadCount, 1)
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
}
