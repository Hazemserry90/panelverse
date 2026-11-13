import SwiftUI
import UIKit
import UserNotifications

@main
@MainActor
struct PanelVerseApp: App {
    @UIApplicationDelegateAdaptor(PanelVerseAppDelegate.self) private var appDelegate
    @StateObject private var library = LibraryStore()
    @StateObject private var account = AccountStore()
    @StateObject private var downloads = ChapterDownloads()
    @AppStorage("panelverse.darkMode") private var darkMode = true

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
                        NavigationStack { PreferencesScreen() }
                            .tabItem { Label("Settings", systemImage: "gearshape") }
                    }
                } else {
                    ZStack {
                        Ink.background.ignoresSafeArea()
                        Text("PANELVERSE").font(.largeTitle.weight(.black)).foregroundStyle(Ink.text)
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
            .preferredColorScheme(darkMode ? .dark : .light)
            .task { account.start() }
            .onChange(of: account.userID) { uid in
                downloads.cancel()
                library.connectAccount(uid)
            }
        }
    }
}

@MainActor
private struct PreferencesScreen: View {
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var account: AccountStore
    @AppStorage("panelverse.darkMode") private var darkMode = true
    @AppStorage("panelverse.readingReminders") private var reminders = false
    @State private var showAccount = false
    @State private var confirmClear = false
    @State private var message = ""

    var body: some View {
        Form {
            Section("Account") {
                Button { showAccount = true } label: {
                    LabeledContent("Account", value: account.email ?? "Sign in")
                }
                if library.syncError { Text("Library changes couldn’t sync.").foregroundStyle(.secondary) }
            }
            Section("Reading history") {
                NavigationLink("Reading history") { ReadingHistoryScreen() }
                Button("Clear reading history", role: .destructive) { confirmClear = true }
                    .disabled(library.history.isEmpty)
            }
            Section("Appearance") { Toggle("Dark mode", isOn: $darkMode) }
            Section("Notifications") {
                Toggle("Weekly reading reminder", isOn: Binding(get: { reminders }, set: setReminders))
                if !message.isEmpty { Text(message).foregroundStyle(.secondary) }
            }
            if let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String {
                LabeledContent("Version", value: version)
            }
        }
        .navigationTitle("Settings")
        .sheet(isPresented: $showAccount) { AccountScreen() }
        .confirmationDialog("Clear reading history?", isPresented: $confirmClear, titleVisibility: .visible) {
            Button("Clear reading history", role: .destructive) { library.clearHistory() }
        }
    }

    private func setReminders(_ enabled: Bool) {
        let center = UNUserNotificationCenter.current()
        let identifier = "panelverse.weekly-reading"
        reminders = enabled
        message = ""
        if !enabled {
            center.removePendingNotificationRequests(withIdentifiers: [identifier])
            return
        }
        Task {
            do {
                let allowed = try await center.requestAuthorization(options: [.alert, .sound])
                guard reminders else { return }
                guard allowed else {
                    reminders = false
                    message = "Allow PanelVerse notifications in iPhone Settings to get reminders."
                    return
                }
                let content = UNMutableNotificationContent()
                content.title = "Continue reading"
                content.body = "Pick up where you left off in PanelVerse."
                let trigger = UNCalendarNotificationTrigger(dateMatching: DateComponents(hour: 18, weekday: 1), repeats: true)
                try await center.add(UNNotificationRequest(identifier: identifier, content: content, trigger: trigger))
                if !reminders { center.removePendingNotificationRequests(withIdentifiers: [identifier]) }
            } catch {
                if reminders {
                    reminders = false
                    message = "The reminder couldn’t be scheduled. Try again."
                }
            }
        }
    }
}

@MainActor
private struct ReadingHistoryScreen: View {
    @EnvironmentObject private var library: LibraryStore
    @State private var search = ""

    var body: some View {
        List(library.history.filter { search.isEmpty || $0.title.localizedCaseInsensitiveContains(search) }) { series in
            NavigationLink(series.title) { SeriesScreen(series: series) }
        }
        .navigationTitle("Reading history")
        .searchable(text: $search, prompt: "Search reading history")
    }
}

enum Ink {
    static let paper = Color(red: 0.95, green: 0.93, blue: 0.88)
    static let background = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark ? UIColor(white: 0.07, alpha: 1) : UIColor(white: 0.95, alpha: 1)
    })
    static let panel = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark ? UIColor(white: 0.13, alpha: 1) : UIColor(white: 0.87, alpha: 1)
    })
    static let ink = Color(white: 0.07)
    static let text = Color.primary
    static let readerBackground = Color(white: 0.07)
    static let accent = Color(red: 0.85, green: 0.22, blue: 0.17)
}
