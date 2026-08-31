import SwiftUI
import Riffle

@main
struct RiffleApp: App {
    init() {
        KoinKt.startKoin()
    }
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
