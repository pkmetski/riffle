import XCTest
import Riffle

// Covers scenarios from docs/testing/ios-scenarios/04-audiobook-player.md

final class AudiobookPlayerTests: XCTestCase {

    // MARK: - Scenario 04-A / 04-E: Bridge lifecycle

    func testBridgeFactoryCreatesDistinctInstances() {
        let factory = IosAudioPlayerBridgeFactoryImpl()
        let bridge1 = factory.create()
        let bridge2 = factory.create()
        XCTAssertFalse(bridge1 === (bridge2 as AnyObject), "Factory must return distinct instances")
    }

    func testDisposeIsIdempotent() {
        let bridge = IosAudioPlayerBridgeImpl()
        bridge.dispose()
        bridge.dispose()
    }

    func testCurrentPositionIsZeroBeforePrepare() {
        let bridge = IosAudioPlayerBridgeImpl()
        XCTAssertEqual(bridge.currentPositionSec(), 0.0, accuracy: 0.001)
    }

    func testIsPlayingFalseBeforePrepare() {
        let bridge = IosAudioPlayerBridgeImpl()
        XCTAssertFalse(bridge.isPlaying())
    }

    // MARK: - Scenario 04-B: Callbacks

    func testPositionCallbackIsInvokedOnSimulate() {
        let bridge = IosAudioPlayerBridgeImpl()
        var received: Double?
        bridge.setPositionCallback { pos in received = pos }
        bridge.simulatePositionUpdate(42.5)
        XCTAssertEqual(received, 42.5, accuracy: 0.001)
    }

    func testPlayingCallbackIsInvokedOnSimulate() {
        let bridge = IosAudioPlayerBridgeImpl()
        var received: Bool?
        bridge.setPlayingCallback { playing in received = playing }
        bridge.simulatePlayingChanged(true)
        XCTAssertTrue(received == true)
    }

    func testClearingCallbackPreventsInvocation() {
        let bridge = IosAudioPlayerBridgeImpl()
        var count = 0
        bridge.setPositionCallback { _ in count += 1 }
        bridge.simulatePositionUpdate(1.0)
        bridge.setPositionCallback(nil)
        bridge.simulatePositionUpdate(2.0)
        XCTAssertEqual(count, 1, "Callback cleared — second simulate must not fire it")
    }

    // MARK: - Scenario 04-E: Now Playing (smoke test — no crash)

    func testSetNowPlayingInfoDoesNotCrash() {
        let bridge = IosAudioPlayerBridgeImpl()
        bridge.setNowPlayingInfo(
            title: "Test Book",
            author: "Test Author",
            durationSec: 3600,
            positionSec: 120,
            coverUrl: nil
        )
    }

    // MARK: - Factory smoke test

    func testFactoryProducesWorkingBridge() {
        let factory = IosAudioPlayerBridgeFactoryImpl()
        guard let bridge = factory.create() as? IosAudioPlayerBridgeImpl else {
            XCTFail("Factory must return IosAudioPlayerBridgeImpl")
            return
        }
        XCTAssertFalse(bridge.isPlaying())
        XCTAssertEqual(bridge.currentPositionSec(), 0.0, accuracy: 0.001)
        bridge.dispose()
    }
}
