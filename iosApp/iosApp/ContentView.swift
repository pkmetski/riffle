import SwiftUI
import Riffle

// Bridges IosStatusBarBridge.Listener to SwiftUI's .statusBarHidden() modifier so the reader's
// immersive mode hides the system status bar. UIViewControllerBasedStatusBarAppearance=NO is set
// in Info.plist so this app-level override wins over Readium's child EPUBNavigatorViewController.
private class StatusBarState: NSObject, ObservableObject, IosStatusBarBridgeListener {
    @Published var isHidden = false

    func onHiddenChanged(hidden: Bool) {
        DispatchQueue.main.async {
            self.isHidden = hidden
        }
    }
}

struct ContentView: View {
    @StateObject private var statusBarState = StatusBarState()

    var body: some View {
        ComposeView()
            .ignoresSafeArea(.all)
            .statusBarHidden(statusBarState.isHidden)
            .onAppear {
                IosStatusBarBridge.shared.setListener(listener: statusBarState)
            }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
