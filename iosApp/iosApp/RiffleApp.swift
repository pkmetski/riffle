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
    let dbPath = (NSHomeDirectory() as NSString).appendingPathComponent("riffle.db")
    try? FileManager.default.removeItem(atPath: dbPath)
    if let bundleId = Bundle.main.bundleIdentifier {
        UserDefaults.standard.removePersistentDomain(forName: bundleId)
    }
    let query: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "com.riffle.app"
    ]
    SecItemDelete(query as CFDictionary)
}
