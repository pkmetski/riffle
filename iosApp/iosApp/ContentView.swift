import SwiftUI
import UIKit
import Riffle

// Bridges IosStatusBarBridge.Listener (Kotlin interface) to UIApplication.setStatusBarHidden so
// the reader's immersive mode can hide the system status bar, matching Android's
// ImmersiveModeState. UIViewControllerBasedStatusBarAppearance=NO is required in Info.plist so
// that UIApplication (not Readium's child EPUBNavigatorViewController) controls the bar.
private class StatusBarListenerImpl: NSObject, IosStatusBarBridgeListener {
    func onHiddenChanged(hidden: Bool) {
        DispatchQueue.main.async {
            UIApplication.shared.setStatusBarHidden(hidden, with: .none)
        }
    }
}

struct ContentView: View {
    private let statusBarListener = StatusBarListenerImpl()

    var body: some View {
        ComposeView()
            .ignoresSafeArea(.all)
            .onAppear {
                IosStatusBarBridge.shared.setListener(listener: statusBarListener)
            }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
