import XCTest
import Riffle

/// Swift counterpart for `PageEdgeTapTest` (app/src/test).
///
/// The zone-detection logic (left/right 20%, vertical guard 15%) lives in
/// ReadiumSwiftNavigator on the Kotlin side and is exercised there by
/// ReadiumSwiftNavigatorEdgeTapTest.  These tests pin the bridge's coordinate
/// relay: simulateTapAt must forward tapX/tapY/viewWidth/viewHeight to the tap
/// callback unchanged, because the Kotlin side reads those exact values to
/// decide which zone was tapped.  A silent clamp or coordinate swap here
/// would produce wrong navigation without any Kotlin test failing.
final class PageEdgeTapTests: XCTestCase {

    func testLeftEdgeCoordinatesReachTapCallback() {
        let bridge = ReadiumEpubNavigatorBridge()
        var receivedX: Double = -1
        var receivedY: Double = -1
        var receivedWidth: Double = -1
        var receivedHeight: Double = -1
        bridge.setTapCallback { tapX, tapY, tapW, tapH in
            receivedX = tapX; receivedY = tapY; receivedWidth = tapW; receivedHeight = tapH
        }
        // tapX/width = 50/360 = 0.139 — inside left edge zone (< 0.20)
        bridge.simulateTapAt(tapX: 50, tapY: 400, viewWidth: 360, viewHeight: 800)
        XCTAssertEqual(receivedX, 50)
        XCTAssertEqual(receivedY, 400)
        XCTAssertEqual(receivedWidth, 360)
        XCTAssertEqual(receivedHeight, 800)
    }

    func testRightEdgeCoordinatesReachTapCallback() {
        let bridge = ReadiumEpubNavigatorBridge()
        var receivedX: Double = -1
        bridge.setTapCallback { tapX, _, _, _ in receivedX = tapX }
        // tapX/width = 310/360 = 0.861 — inside right edge zone (> 0.80)
        bridge.simulateTapAt(tapX: 310, tapY: 400, viewWidth: 360, viewHeight: 800)
        XCTAssertEqual(receivedX, 310)
    }

    func testCenterCoordinatesReachTapCallback() {
        let bridge = ReadiumEpubNavigatorBridge()
        var receivedX: Double = -1
        bridge.setTapCallback { tapX, _, _, _ in receivedX = tapX }
        // tapX/width = 180/360 = 0.50 — center, not in any edge zone
        bridge.simulateTapAt(tapX: 180, tapY: 400, viewWidth: 360, viewHeight: 800)
        XCTAssertEqual(receivedX, 180)
    }

    func testTopBandCoordinatesReachTapCallback() {
        let bridge = ReadiumEpubNavigatorBridge()
        var receivedY: Double = -1
        bridge.setTapCallback { _, tapY, _, _ in receivedY = tapY }
        // tapY/height = 60/800 = 0.075 — inside top guard band (< 0.15), excluded from edge nav
        bridge.simulateTapAt(tapX: 50, tapY: 60, viewWidth: 360, viewHeight: 800)
        XCTAssertEqual(receivedY, 60)
    }

    func testBottomBandCoordinatesReachTapCallback() {
        let bridge = ReadiumEpubNavigatorBridge()
        var receivedY: Double = -1
        bridge.setTapCallback { _, tapY, _, _ in receivedY = tapY }
        // tapY/height = 740/800 = 0.925 — inside bottom guard band (> 0.85), excluded from edge nav
        bridge.simulateTapAt(tapX: 310, tapY: 740, viewWidth: 360, viewHeight: 800)
        XCTAssertEqual(receivedY, 740)
    }

    func testNilCallbackDoesNotCrash() {
        let bridge = ReadiumEpubNavigatorBridge()
        // No callback registered — simulateTapAt must be a no-op, not a crash
        bridge.simulateTapAt(tapX: 50, tapY: 400, viewWidth: 360, viewHeight: 800)
    }
}
