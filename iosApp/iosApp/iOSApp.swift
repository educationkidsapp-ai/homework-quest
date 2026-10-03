import SwiftUI

@main
struct iOSApp: App {
    init() { Bridges.install() }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .ignoresSafeArea(.all)
        }
    }
}
