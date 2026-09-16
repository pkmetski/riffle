import SwiftUI
import Security
import Riffle

@main
struct RiffleApp: App {
    init() {
        if CommandLine.arguments.contains("--RIFFLE_RESET_FOR_TESTS") {
            wipeAppState()
        }
        KoinKt.startKoin(
            navigatorBridgeFactory: ReadiumEpubNavigatorBridgeFactory(),
            audioPlayerBridgeFactory: IosAudioPlayerBridgeFactoryImpl(),
            pdfNavigatorBridgeFactory: PdfKitNavigatorBridgeFactoryImpl()
        )
    }
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

private func wipeAppState() {
    // SQLDelight's NativeSqliteDriver stores the DB in Library/Application Support/databases/,
    // not directly under NSHomeDirectory(). Delete the whole databases directory so WAL/SHM
    // files are also cleared and Room re-creates the schema on the next Koin start.
    if let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first {
        let dbDir = appSupport.appendingPathComponent("databases")
        try? FileManager.default.removeItem(at: dbDir)
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
