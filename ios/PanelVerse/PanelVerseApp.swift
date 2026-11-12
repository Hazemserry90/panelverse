import SwiftUI

@main
@MainActor
struct PanelVerseApp: App {
    @StateObject private var library = LibraryStore()

    var body: some Scene {
        WindowGroup {
            TabView {
                NavigationStack { CatalogScreen() }
                    .tabItem { Label("Home", systemImage: "house") }
                NavigationStack { CatalogScreen(discover: true) }
                    .tabItem { Label("Discover", systemImage: "safari") }
                NavigationStack { LibraryScreen() }
                    .tabItem { Label("Library", systemImage: "books.vertical") }
                NavigationStack { OfflineScreen() }
                    .tabItem { Label("Offline", systemImage: "arrow.down.circle") }
            }
            .environmentObject(library)
            .tint(Ink.accent)
            .preferredColorScheme(.dark)
        }
    }
}

enum Ink {
    static let paper = Color(red: 0.95, green: 0.93, blue: 0.88)
    static let background = Color(red: 0.07, green: 0.07, blue: 0.07)
    static let panel = Color(white: 0.13)
    static let accent = Color(red: 0.85, green: 0.22, blue: 0.17)
}
