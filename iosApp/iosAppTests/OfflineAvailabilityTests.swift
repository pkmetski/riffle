import XCTest

// IosLibraryItemOfflineAvailabilityImpl was replaced by LibraryItemOfflineAvailabilityImpl
// in commonMain (Mutex+Clock, repository-based). The equivalent scenarios are covered by
// LibraryItemOfflineAvailabilityCommonTest in core:domain commonTest, which runs on iOS via
// iosSimulatorArm64Test.
final class OfflineAvailabilityTests: XCTestCase {}
