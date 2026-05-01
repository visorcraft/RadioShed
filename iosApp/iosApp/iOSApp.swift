import SwiftUI
import ComposeApp

@main
struct iOSApp: App {

    init() {
        // Initialize Koin DI graph (registers DB, repositories, settings).
        // Must run before any composable / repository is used.
        KoinIosKt.doInitKoin()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
