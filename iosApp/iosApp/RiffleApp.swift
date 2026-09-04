import SwiftUI
import Riffle

@main
struct RiffleApp: App {
    init() {
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
