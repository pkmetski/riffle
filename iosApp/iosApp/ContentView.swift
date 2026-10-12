import SwiftUI
import UIKit
import Riffle

// Bridges IosStatusBarBridge.Listener (Kotlin interface) to a SwiftUI @State update so the
// reader's immersive mode can hide the system status bar, matching Android's ImmersiveModeState.
private class StatusBarListenerImpl: NSObject, IosStatusBarBridgeListener {
    var onHiddenChanged: (Bool) -> Void = { _ in }

    func onHiddenChanged(hidden: Bool) {
        // IosStatusBarBridge calls from the Compose main thread on iOS; dispatching to main is
        // a safety measure in case the bridge is ever called from a background thread.
        DispatchQueue.main.async { [weak self] in self?.onHiddenChanged(hidden) }
    }
}

struct ContentView: View {
    @State private var statusBarHidden = false
    private let statusBarListener = StatusBarListenerImpl()

    var body: some View {
        ComposeView()
            .ignoresSafeArea(.all)
            .statusBarHidden(statusBarHidden)
            .onAppear {
                statusBarListener.onHiddenChanged = { hidden in statusBarHidden = hidden }
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
