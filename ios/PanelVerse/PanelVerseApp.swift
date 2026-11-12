import SwiftUI

@main
@MainActor
struct PanelVerseApp: App {
    @UIApplicationDelegateAdaptor(PanelVerseAppDelegate.self) private var appDelegate
    @StateObject private var library = LibraryStore()
    @StateObject private var account = AccountStore()
    @StateObject private var downloads = ChapterDownloads()

    var body: some Scene {
        WindowGroup {
            Group {
                if account.ready {
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
                } else {
                    ZStack {
                        Ink.background.ignoresSafeArea()
                        Text("PANELVERSE").font(.largeTitle.weight(.black)).foregroundStyle(Ink.paper)
                        ProgressView()
                            .offset(y: 52)
                            .accessibilityLabel("Loading PanelVerse")
                    }
                }
            }
            .environmentObject(library)
            .environmentObject(account)
            .environmentObject(downloads)
            .tint(Ink.accent)
            .preferredColorScheme(.dark)
            .task { account.start() }
        }
    }
}

enum Ink {
    static let paper = Color(red: 0.95, green: 0.93, blue: 0.88)
    static let background = Color(red: 0.07, green: 0.07, blue: 0.07)
    static let panel = Color(white: 0.13)
    static let accent = Color(red: 0.85, green: 0.22, blue: 0.17)
}
