import SwiftUI
import XCTest

// Pins the scene-phase gate that guards `NowPlayingNavigator.requestOpen()` from firing on
// every app foreground. Only a genuine background → active transition (lock-screen media-
// notification tap) must trigger the navigator. Other transitions must not, because Control
// Centre swipes, incoming phone calls, and notification banners all produce an .inactive →
// .active transition that previously caused a spurious player push on every occurrence.
final class NowPlayingSceneGateTests: XCTestCase {

    func testBackgroundToActive_triggers() {
        XCTAssertTrue(shouldRequestNowPlayingOpen(from: .background, to: .active))
    }

    func testInactiveToActive_noTrigger() {
        XCTAssertFalse(
            shouldRequestNowPlayingOpen(from: .inactive, to: .active),
            "Control Centre / call end (.inactive → .active) must not open the player"
        )
    }

    func testActiveToActive_noTrigger() {
        XCTAssertFalse(shouldRequestNowPlayingOpen(from: .active, to: .active))
    }

    func testActiveToBackground_noTrigger() {
        XCTAssertFalse(shouldRequestNowPlayingOpen(from: .active, to: .background))
    }

    func testBackgroundToInactive_noTrigger() {
        XCTAssertFalse(shouldRequestNowPlayingOpen(from: .background, to: .inactive))
    }
}
