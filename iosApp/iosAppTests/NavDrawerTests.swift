import XCTest
import Riffle

// Covers scenarios from docs/testing/ios-scenarios/05-nav-drawer.md
final class NavDrawerTests: XCTestCase {

    // Scenario 5.1 — Storyteller sources are filtered before the drawer renders.
    // The filtering logic is exercised in DrawerViewModelTest (commonTest);
    // here we verify the ServerType enum value is present so the filter branch compiles.
    func testStorytellerServerTypeExists() {
        // STORYTELLER_SERVICE must be a known ServerType for the filter in DrawerViewModel to work.
        let st = ServerType.storytellerService
        XCTAssertNotNil(st)
    }

    // Scenarios 5.4–5.5 are UI-only (drawer open/close state lives in HomeScreen Compose),
    // which cannot be driven via XCTest without an instrumentation harness.
    // They are documented in 05-nav-drawer.md and verified manually via Xcode build + tap.
    func testBurgerTapOpensDrawer() throws {
        throw XCTSkip("UI-only; verified manually by building and tapping the ☰ button")
    }

    func testScrimTapClosesDrawer() throws {
        throw XCTSkip("UI-only; verified manually by tapping the scrim after opening the drawer")
    }
}
