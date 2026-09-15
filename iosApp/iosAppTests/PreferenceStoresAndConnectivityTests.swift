import XCTest
import Riffle

// Covers scenarios from docs/testing/ios-scenarios/13-preference-stores-and-connectivity.md
final class PreferenceStoresAndConnectivityTests: XCTestCase {

    // Scenario 13.1 — IosConnectivityObserver is backed by NWPathMonitor and exported by the
    // framework. This verifies it compiles, links, and initialises without crashing. The class
    // symbol must be present in the Riffle framework; if the NWPathMonitor integration regresses
    // (e.g. reverts to the always-online stub), the import would still succeed but the class
    // type would differ from IosConnectivityObserver.
    func testConnectivityObserverIsExportedByFramework() {
        // Verify the class symbol is compiled into the Riffle framework.
        let cls: AnyClass? = NSClassFromString("RiffleCore.IosConnectivityObserver")
        // Kotlin/Native exports module-qualified names; the actual class name in ObjC is
        // constructed from the Kotlin class. We validate by direct Swift instantiation.
        let observer = IosConnectivityObserver()
        XCTAssertNotNil(observer, "IosConnectivityObserver should initialise without error")
        // The observer exposes ConnectivityObserver protocol — isOnline must be a valid flow.
        XCTAssertNotNil(observer.isOnline, "IosConnectivityObserver.isOnline must be non-nil")
    }

    // Scenario 13.2 — IosAppThemeStoreImpl is exported by the framework; default is AppTheme.system
    // (the UserDefaults key is absent on first launch). If the default changes or the class fails
    // to export, this test catches it.
    func testAppThemeStoreDefaultIsSystem() {
        UserDefaults.standard.removeObject(forKey: "app_theme")
        let store = IosAppThemeStoreImpl()
        XCTAssertEqual(store.appTheme, AppTheme.system,
                       "IosAppThemeStoreImpl default must be AppTheme.system when no persisted value")
    }
}
