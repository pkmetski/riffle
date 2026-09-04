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

    func testBridgeViewControllerIsNonNil() {
        let bridge = ReadiumEpubNavigatorBridge()
        XCTAssertNotNil(bridge.viewController())
    }

    func testSnapshotLocatorJsonIsNilBeforeOpen() {
        let bridge = ReadiumEpubNavigatorBridge()
        XCTAssertNil(bridge.snapshotLocatorJson())
    }

    func testReleaseIsIdempotent() {
        // release() must not crash when called multiple times.
        let bridge = ReadiumEpubNavigatorBridge()
        bridge.release()
        bridge.release()
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
        bridge.setLocatorCallback(nil)
        bridge.simulateLocatorUpdate("{}")
        XCTAssertEqual(count, 0)
    }

    // MARK: - Scenario 03-F: isReadable gate — verified via LibraryItem.isReadable in JVM tests
    // (EbookFormat is a sealed class; the gate logic lives in commonMain and is covered by
    //  JVM-level unit tests.  The iOS XCTest suite focuses on the native bridge layer.)
}
