import SwiftUI
import Security
import Riffle

@main
struct RiffleApp: App {
    @Environment(\.scenePhase) private var scenePhase

    init() {
        if CommandLine.arguments.contains("--RIFFLE_RESET_FOR_TESTS") {
            wipeAppState()
        }
        KoinKt.startKoin(
            navigatorBridgeFactory: ReadiumEpubNavigatorBridgeFactory(),
            audioPlayerBridgeFactory: IosAudioPlayerBridgeFactoryImpl(),
            pdfNavigatorBridgeFactory: PdfKitNavigatorBridgeFactoryImpl(),
            publicationInspector: ReadiumPublicationInspector(),
            readaloudBridgeFactory: IosReadaloudBridgeFactoryImpl(),
            elidedPdfBridge: ElidedPdfBridge()
        )
        // UI-test harness: add a stub source through the production authenticate → commit path so
        // tests start on the library home without driving the add-source screens (no-op otherwise).
        IosTestSourceSeederKt.seedTestSourceFromLaunchArguments()
    }
    var body: some Scene {
        WindowGroup {
            ContentView()
                .onOpenURL { url in openIncomingDocument(url) }
        }
        // When the app returns to the foreground from a genuine background state (e.g. a lock-screen
        // media-notification tap), open the active player. Guarded to background→active only so that
        // Control Centre pulls, phone calls, and notification banners (.inactive→.active) do not
        // trigger a spurious nav push. A second Kotlin-side guard in LibraryHost de-duplicates
        // against the stack top so a player already on screen is never pushed a second time.
        .onChange(of: scenePhase) { oldPhase, newPhase in
            if shouldRequestNowPlayingOpen(from: oldPhase, to: newPhase) {
                KoinKt.getNowPlayingNavigator().requestOpen()
            }
        }
    }
}

private func wipeAppState() {
    // Room KMP (BundledSQLiteDriver) stores the database at NSHomeDirectory()/riffle.db, not
    // in Library/Application Support/databases/ (SQLDelight's old location). Delete the main
    // database file and its WAL/SHM sidecars so Room re-creates the schema on the next Koin start.
    let homeDir = NSHomeDirectory()
    let fileManager = FileManager.default
    for name in ["riffle.db", "riffle.db-wal", "riffle.db-shm"] {
        try? fileManager.removeItem(atPath: "\(homeDir)/\(name)")
    }
    if let bundleId = Bundle.main.bundleIdentifier {
        UserDefaults.standard.removePersistentDomain(forName: bundleId)
    }
    // SecItemDelete blocks until the Keychain daemon responds. On a cold CI runner the daemon
    // can take 20-30 s to start. Fire-and-forget on a background thread so the main thread
    // is never blocked: the DB and UserDefaults wipes above are sufficient for tests that need
    // a clean state; any old Keychain tokens left briefly in place don't affect visible UI.
    DispatchQueue.global(qos: .userInitiated).async {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.riffle.app"
        ]
        SecItemDelete(query as CFDictionary)
    }
}
