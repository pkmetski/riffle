import XCTest
@testable import iosApp

final class IosVerticalReaderPullGestureTests: XCTestCase {

    func testPullCallbacksRegisterAndFire() {
        let bridge = ReadiumEpubNavigatorBridge()
        var moveFired = false
        var resetFired = false

        bridge.setPullMoveCallback { _, _ in moveFired = true }
        bridge.setPullResetCallback { resetFired = true }
        bridge.setPullGestureEnabled(enabled: true)

        bridge.pullMoveCallback?(5.0, 1000)
        bridge.pullResetCallback?()

        XCTAssertTrue(moveFired, "pullMoveCallback should fire when invoked")
        XCTAssertTrue(resetFired, "pullResetCallback should fire when invoked")
    }

    func testClearingCallbacksPreventsSubsequentFiring() {
        let bridge = ReadiumEpubNavigatorBridge()
        var moveFired = false
        var resetFired = false

        bridge.setPullMoveCallback { _, _ in moveFired = true }
        bridge.setPullResetCallback { resetFired = true }
        bridge.setPullGestureEnabled(enabled: true)

        bridge.setPullMoveCallback(callback: nil)
        bridge.setPullResetCallback(callback: nil)

        bridge.pullMoveCallback?(5.0, 1000)
        bridge.pullResetCallback?()

        XCTAssertFalse(moveFired, "cleared pullMoveCallback should not fire")
        XCTAssertFalse(resetFired, "cleared pullResetCallback should not fire")
    }

    func testPullGestureDisabledByDefault() {
        let bridge = ReadiumEpubNavigatorBridge()
        XCTAssertFalse(bridge.pullGestureEnabled, "pullGestureEnabled should be false by default")
    }

    func testEnablingAndDisablingPullGesture() {
        let bridge = ReadiumEpubNavigatorBridge()

        bridge.setPullGestureEnabled(enabled: true)
        XCTAssertTrue(bridge.pullGestureEnabled)

        bridge.setPullGestureEnabled(enabled: false)
        XCTAssertFalse(bridge.pullGestureEnabled)
    }
}
