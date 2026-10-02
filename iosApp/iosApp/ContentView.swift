import SwiftUI
import Shared

/// Hosts the shared Compose Multiplatform UI. Every screen lives in `shared/`; nothing is iOS-specific here.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController { MainViewControllerKt.MainViewController() }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView().ignoresSafeArea(.keyboard)
            // M3: a tap on the Today widget (`myschool://today/<link>`). The shared UI follows it underneath the
            // biometric lock, so a locked app asks for Face ID first.
            .onOpenURL { url in TodayLinks.shared.open(key: url.lastPathComponent) }
    }
}
