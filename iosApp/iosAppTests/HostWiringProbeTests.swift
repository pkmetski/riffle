import XCTest
import Riffle

final class HostWiringProbeTests: XCTestCase {

    func testLibraryItemsActionsHaveNoUnexpectedNoOps() {
        let violations = IosHostWiringProbeEntryKt.iosLibraryItemsActionsViolations()
        XCTAssert(violations.isEmpty, "Unexpected no-op callbacks in iOS LibraryItemsActions: \(violations)")
    }

    func testLibraryItemDetailActionsHaveNoUnexpectedNoOps() {
        let violations = IosHostWiringProbeEntryKt.iosLibraryItemDetailActionsViolations()
        XCTAssert(violations.isEmpty, "Unexpected no-op callbacks in iOS LibraryItemDetailActions: \(violations)")
    }

    func testDownloadsDetailActionsNoOpsAreAllowlisted() {
        let violations = IosHostWiringProbeEntryKt.iosDownloadsDetailActionsViolations()
        XCTAssert(violations.isEmpty, "Unexpected no-op callbacks in iOS Downloads LibraryItemDetailActions: \(violations)")
    }

    func testDownloadsActionsHaveNoUnexpectedNoOps() {
        let violations = IosHostWiringProbeEntryKt.iosDownloadsActionsViolations()
        XCTAssert(violations.isEmpty, "Unexpected no-op callbacks in iOS DownloadsActions: \(violations)")
    }

    func testSettingsActionsHaveNoUnexpectedNoOps() {
        let violations = IosHostWiringProbeEntryKt.iosSettingsActionsViolations()
        XCTAssert(violations.isEmpty, "Unexpected no-op callbacks in iOS SettingsActions: \(violations)")
    }
}
