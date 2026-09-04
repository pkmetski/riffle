import SwiftUI
import Riffle

@main
struct RiffleApp: App {
    init() {
        KoinKt.startKoin(navigatorBridgeFactory: ReadiumEpubNavigatorBridgeFactory())
    }
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
