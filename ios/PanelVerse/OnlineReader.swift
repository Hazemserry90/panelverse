import OSLog
import SwiftUI
import UIKit
import WebKit

@MainActor
struct OnlineReaderScreen: View {
    let series: Series
    var initialAddress: URL?
    var automaticDownload: DownloadChapter?
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var downloads: ChapterDownloads
    @EnvironmentObject private var account: AccountStore
    @State private var start: ReadingPosition?
    @State private var ready = false
    @State private var error = false
    @State private var errorMessage = "This page couldn’t load."
    @State private var verificationAddress: URL?
    @State private var loading = true
    @State private var reload = 0
    @State private var zoom: CGFloat = 1
    @State private var downloadSource: ReaderDownloadSource?
    @State private var showAccount = false
    @State private var attemptedAutomaticDownload = false
    @State private var restoreNotice = false
    @AppStorage("panelverse.blockReaderAds") private var blockAds = true

    private var entryAddress: URL { initialAddress ?? series.address }
    private var savedPosition: ReadingPosition? {
        guard let position = library.position(series) else { return nil }
        if let initialAddress {
            guard let requested = DownloadChapter(series: series, url: initialAddress),
                  let saved = DownloadChapter(series: series, url: position.address),
                  requested.address == saved.address, automaticDownload == nil else { return nil }
        }
        return position
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Ink.background.ignoresSafeArea()
                if ready {
                    ProviderWebView(series: series, entryAddress: entryAddress, initial: start, zoom: zoom, blockAds: blockAds,
                                    loading: $loading, failed: $error,
                                    onDownloadSource: updateDownloadSource,
                                    onError: { errorMessage = $0 },
                                    onVerification: { verificationAddress = $0 },
                                    onApproximateRestore: { restoreNotice = true }) {
                        if automaticDownload == nil {
                            library.remember(series, address: $0, scroll: $1, anchor: $2)
                        }
                    }
                    .id(reload)
                    .opacity(error ? 0 : 1)
                }
                if error {
                    VStack(spacing: 16) {
                        Text(errorMessage).font(.headline).multilineTextAlignment(.center)
                        Button("Retry") {
                            error = false
                            loading = true
                            verificationAddress = nil
                            start = savedPosition
                            reload += 1
                        }
                        .buttonStyle(.borderedProminent)
                        if series.provider == "readcomicsonline.ru" {
                            ProviderBrowserLink(series: series, address: start?.address ?? entryAddress)
                        }
                    }
                    .padding()
                } else if loading && verificationAddress == nil {
                    ProgressView().padding(16).background(Ink.background).allowsHitTesting(false)
                }
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                VStack(spacing: 0) {
                    DownloadStatus()
                    if restoreNotice {
                        HStack {
                            Text("Restored an approximate reading position.").font(.subheadline)
                            Spacer()
                            Button("Dismiss") { restoreNotice = false }.font(.subheadline)
                        }
                        .padding(10)
                        .background(Ink.panel)
                    }
                    if let address = verificationAddress, !error {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("If verification stays on this screen, open the comic in your browser.")
                                .font(.subheadline)
                            ProviderBrowserLink(series: series, address: address)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(12)
                        .background(Ink.panel)
                    } else if !loading && !error {
                        downloadControls
                    }
                }
            }
            .navigationTitle(series.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button {
                            zoom = min(zoom * 1.25, 3)
                        } label: {
                            Label("Zoom in", systemImage: "plus.magnifyingglass")
                        }
                        Button {
                            zoom = max(zoom / 1.25, 1)
                        } label: {
                            Label("Zoom out", systemImage: "minus.magnifyingglass")
                        }
                        Button("Reset zoom") { zoom = 1 }
                        Toggle("Block ads", isOn: $blockAds)
                    } label: {
                        Label("Reader options", systemImage: "ellipsis.circle")
                    }
                }
            }
        }
        .onAppear {
            if !ready {
                start = savedPosition
                ready = true
            }
        }
        .sheet(isPresented: $showAccount) { AccountScreen() }
    }

    private func updateDownloadSource(_ source: ReaderDownloadSource?) {
        downloadSource = source
        guard let source, let automaticDownload, source.chapter.address == automaticDownload.address,
              !attemptedAutomaticDownload, account.currentUserID != nil else { return }
        attemptedAutomaticDownload = true
        downloads.start(automaticDownload, library: library, account: account, pages: source.readyPages)
    }

    @ViewBuilder private var downloadControls: some View {
        if let source = downloadSource {
            let saved = library.books.contains { $0.source == source.chapter.address }
            let guest = account.userID == nil
            let label = saved ? "Available offline" :
                (guest ? "Sign in to download" : (series.isManga ? "Download chapter" : "Download issue"))
            Button {
                if account.currentUserID == nil { showAccount = true }
                else { downloads.start(source, library: library, account: account) }
            } label: {
                Label(label, systemImage: saved ? "checkmark.circle" : (guest ? "lock" : "arrow.down.circle"))
                    .frame(maxWidth: .infinity, minHeight: 36)
            }
            .buttonStyle(.bordered)
            .disabled(saved || (!guest && downloads.active != nil))
            .padding(8)
            .background(Ink.background)
        }
    }
}

struct ProviderBrowserLink: View {
    let series: Series
    let address: URL
    @Environment(\.openURL) private var openURL
    @State private var confirm = false

    var body: some View {
        if let destination {
            Button { confirm = true } label: {
                Label("Open in browser", systemImage: "safari")
            }
            .buttonStyle(.bordered)
            .confirmationDialog("Open \(series.provider) in your browser?", isPresented: $confirm, titleVisibility: .visible) {
                Button("Open in browser") { openURL(destination) }
            }
        }
    }

    private var destination: URL? {
        guard series.contains(address),
              var parts = URLComponents(url: address, resolvingAgainstBaseURL: false) else { return nil }
        parts.query = nil
        parts.fragment = nil
        return parts.url
    }
}

@MainActor
private struct ProviderWebView: UIViewRepresentable {
    let series: Series
    let entryAddress: URL
    let initial: ReadingPosition?
    let zoom: CGFloat
    let blockAds: Bool
    @Binding var loading: Bool
    @Binding var failed: Bool
    var onDownloadSource: (ReaderDownloadSource?) -> Void
    var onError: (String) -> Void
    var onVerification: (URL?) -> Void
    var onApproximateRestore: () -> Void
    var save: (URL, Double, ReaderPageAnchor?) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.ignoresViewportScaleLimits = true
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.navigationDelegate = context.coordinator
        view.scrollView.delegate = context.coordinator
        view.isOpaque = false
        view.backgroundColor = .black
        let target = initial.flatMap { series.contains($0.address) ? $0.address : nil } ?? entryAddress
        context.coordinator.open(view, at: target)
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {
        context.coordinator.parent = self
        context.coordinator.updateAdFilter(in: view, enabled: blockAds)
        if view.pageZoom != zoom { view.pageZoom = zoom }
    }

    static func dismantleUIView(_ view: WKWebView, coordinator: Coordinator) {
        coordinator.finish(view)
    }

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate, UIScrollViewDelegate {
        var parent: ProviderWebView
        private weak var view: WKWebView?
        private var page: URL?
        private var restoration: Task<Void, Never>?
        private var saveTask: Task<Void, Never>?
        private var issueCapture: Task<Void, Never>?
        private var restored = false
        private var automaticRestoreFailed = false
        private var loadingPage = true
        private var isVerificationPage = false
        private var appliedAdFilter: Bool?
        private var adRules: WKContentRuleList?

        init(_ parent: ProviderWebView) { self.parent = parent }

        func open(_ view: WKWebView, at address: URL) {
            self.view = view
            Task { [weak view, weak self] in
                let rules = await ReaderAdFilter.compiledRules()
                guard let view, let self, self.view === view else { return }
                self.adRules = rules
                if self.parent.blockAds {
                    ReaderAdFilter.installScript(in: view.configuration.userContentController)
                    if let rules { view.configuration.userContentController.add(rules) }
                }
                self.appliedAdFilter = self.parent.blockAds
                view.load(URLRequest(url: address))
            }
        }

        func updateAdFilter(in view: WKWebView, enabled: Bool) {
            guard let appliedAdFilter, appliedAdFilter != enabled else { return }
            self.appliedAdFilter = enabled
            let controller = view.configuration.userContentController
            controller.removeAllUserScripts()
            if enabled {
                ReaderAdFilter.installScript(in: controller)
                if let adRules { controller.add(adRules) }
            } else if let adRules {
                controller.remove(adRules)
            }
            if let url = view.url, parent.series.contains(url) {
                Task { try? await ReaderAdFilter.pageScript(enabled ? "ads-on" : "ads-off", in: view, at: url) }
            }
        }

        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard let frame = navigationAction.targetFrame, let url = navigationAction.request.url else {
                decisionHandler(.cancel)
                return
            }
            if frame.isMainFrame {
                decisionHandler(parent.series.contains(url) ? .allow : .cancel)
            } else {
                decisionHandler(parent.series.allows(url) || ReaderAdFilter.allowsVerificationFrame(url) ? .allow : .cancel)
            }
        }

        func webView(_ webView: WKWebView, decidePolicyFor navigationResponse: WKNavigationResponse,
                     decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
            if navigationResponse.isForMainFrame,
               let address = navigationResponse.response.url, !parent.series.contains(address) {
                fail()
                decisionHandler(.cancel)
                return
            }
            if navigationResponse.isForMainFrame, let response = navigationResponse.response as? HTTPURLResponse {
                if ReaderAdFilter.isVerificationResponse(response, provider: parent.series.provider),
                   let address = response.url {
                    isVerificationPage = true
                    parent.onVerification(address)
                } else if response.statusCode >= 400 {
                    fail("The reading source returned HTTP \(response.statusCode).")
                    decisionHandler(.cancel)
                    return
                } else {
                    isVerificationPage = false
                    parent.onVerification(nil)
                }
            }
            decisionHandler(.allow)
        }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            saveIfReady()
            saveTask?.cancel()
            issueCapture?.cancel()
            restoration?.cancel()
            restoration = nil
            automaticRestoreFailed = false
            loadingPage = true
            parent.onDownloadSource(nil)
            parent.loading = true
            parent.failed = false
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            parent.loading = false
            guard !isVerificationPage else { return }
            loadingPage = false
            page = webView.url
            if let url = page, let chapter = DownloadChapter(series: parent.series, url: url) {
                parent.onDownloadSource(ReaderDownloadSource(chapter: chapter, visibleURL: url, view: webView))
            } else if let url = page, !parent.series.isManga {
                let series = parent.series
                issueCapture = Task { [weak webView] in
                    guard let webView else { return }
                    for _ in 0..<6 {
                        guard !Task.isCancelled, webView.url == url else { return }
                        if (try? await ProviderReaderLoader.readChapters(for: series, from: webView, at: url)) != nil { return }
                        try? await Task.sleep(nanoseconds: 400_000_000)
                    }
                }
            }
            if !restored, let initial = parent.initial, initial.address == page,
               initial.scroll > 0 || initial.anchor != nil {
                restored = true
                restoration = Task { [weak self, weak webView] in
                    var exact = false
                    for _ in 0..<32 {
                        guard !Task.isCancelled, self != nil, let webView else { return }
                        let scrollView = webView.scrollView
                        let maximum = max(0, scrollView.contentSize.height - scrollView.bounds.height)
                        if let anchor = initial.anchor, anchor.isValid {
                            exact = (try? await ReaderAdFilter.pageScript("restore", in: webView, at: initial.address,
                                                                         anchor: ["key": anchor.key, "offset": anchor.offset])) as? Bool == true
                            guard !Task.isCancelled, webView.url == initial.address else { return }
                            if exact { break }
                        }
                        if initial.anchor == nil && maximum >= CGFloat(initial.scroll) {
                            exact = true
                            break
                        }
                        try? await Task.sleep(nanoseconds: 250_000_000)
                    }
                    guard !Task.isCancelled, let webView, webView.url == initial.address else { return }
                    if !exact || initial.anchor == nil {
                        let scrollView = webView.scrollView
                        let maximum = max(0, scrollView.contentSize.height - scrollView.bounds.height)
                        scrollView.setContentOffset(CGPoint(x: 0, y: min(CGFloat(initial.scroll), maximum)), animated: false)
                    }
                    self?.automaticRestoreFailed = !exact
                    if exact { self?.lastCaptured = (initial.address, Double(webView.scrollView.contentOffset.y), initial.anchor) }
                    if !exact {
                        self?.parent.onApproximateRestore()
                        UIAccessibility.post(notification: .announcement, argument: "Restored an approximate reading position.")
                    }
                    self?.restoration = nil
                }
            } else {
                restored = true
                saveIfReady()
            }
        }

        func scrollViewWillBeginDragging(_ scrollView: UIScrollView) {
            automaticRestoreFailed = false
            restoration?.cancel()
            restoration = nil
        }

        func scrollViewDidScroll(_ scrollView: UIScrollView) {
            guard !loadingPage, restoration == nil else { return }
            saveTask?.cancel()
            saveTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: 350_000_000)
                guard !Task.isCancelled, let self, let view, let page, view.url == page,
                      !automaticRestoreFailed, parent.series.isChapter(page) else { return }
                let value = try? await ReaderAdFilter.pageScript("capture", in: view, at: page)
                guard !Task.isCancelled, view.url == page else { return }
                let result = value as? [String: Any]
                let anchor: ReaderPageAnchor?
                if let key = result?["key"] as? String, let offset = result?["offset"] as? Int {
                    let captured = ReaderPageAnchor(key: key, offset: offset)
                    anchor = captured.isValid ? captured : nil
                } else { anchor = nil }
                saveIfReady(anchor: anchor)
            }
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            if (error as NSError).code != NSURLErrorCancelled { fail() }
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            if (error as NSError).code != NSURLErrorCancelled { fail() }
        }

        func finish(_ webView: WKWebView) {
            saveIfReady()
            restoration?.cancel()
            saveTask?.cancel()
            issueCapture?.cancel()
            view = nil
            webView.navigationDelegate = nil
            webView.scrollView.delegate = nil
            webView.stopLoading()
        }

        private var lastCaptured: (address: URL, scroll: Double, anchor: ReaderPageAnchor?)?

        private func saveIfReady(anchor: ReaderPageAnchor? = nil) {
            guard !loadingPage, !isVerificationPage, !parent.failed, restoration == nil, let page, let view,
                  !automaticRestoreFailed, parent.series.isChapter(page) else { return }
            let scroll = Double(view.scrollView.contentOffset.y)
            let previousAnchor = lastCaptured.flatMap { $0.address == page && abs($0.scroll - scroll) < 4 ? $0.anchor : nil }
            let position = anchor ?? previousAnchor
            lastCaptured = (page, scroll, position)
            parent.save(page, scroll, position)
        }

        private func fail(_ message: String = "This page couldn’t load.") {
            loadingPage = false
            parent.onDownloadSource(nil)
            parent.loading = false
            parent.onError(message)
            parent.failed = true
        }
    }
}

enum ReaderAdFilter {
    private static let script: String? = {
        guard let file = Bundle.main.url(forResource: "reader-page", withExtension: "js") else { return nil }
        return try? String(contentsOf: file, encoding: .utf8)
    }()

    @MainActor static func pageScript(_ operation: String, in view: WKWebView, at url: URL,
                                      anchor: [String: Any] = [:]) async throws -> Any? {
        guard let script else { return nil }
        return try await view.callAsyncJavaScript("return (\(script))(operation, expected, anchor);",
                                                 arguments: ["operation": operation, "expected": url.absoluteString, "anchor": anchor],
                                                 in: nil, contentWorld: .defaultClient)
    }
    @MainActor private static var ruleTask: Task<WKContentRuleList?, Never>?
    private static let adHosts = [
        "2mdn.net", "adnxs.com", "adsrvr.org", "doubleclick.net", "googlesyndication.com",
        "googleadservices.com", "pubmatic.com", "adsterra.com", "adskeeper.com", "inspiredot.net",
        "weddinginseychelles.com", "chemicalandequipmentlimited.com",
        "mgid.com", "clickadu.com", "exoclick.com", "hilltopads.net", "popads.net",
        "popcash.net", "propellerads.com", "trafficjunky.net"
    ]
    private static let verification: [String: String] = [
        "challenges.cloudflare.com": "/", "www.google.com": "/recaptcha/",
        "www.recaptcha.net": "/recaptcha/", "www.gstatic.com": "/recaptcha/",
        "hcaptcha.com": "/", "assets.hcaptcha.com": "/", "newassets.hcaptcha.com": "/"
    ]

    private static let rules: String = {
        let thirdParty: [String: Any] = [
            "url-filter": "^https?://", "load-type": ["third-party"], "resource-type": ["script", "document"]
        ]
        var list: [[String: Any]] = [["trigger": thirdParty, "action": ["type": "block"]]]
        let providerAssets = ["mangakatana.com", "readcomicsonline.ru"].map { host in
            "^https://([a-z0-9-]+[.])*" + hostPattern(host) + "/"
        }
        let verificationAssets = verification.sorted { $0.key < $1.key }.map { host, path in
            "^https://" + hostPattern(host) + path
        }
        let scriptLibraries = [
            "^https://code[.]jquery[.]com/jquery-",
            "^https://ajax[.]googleapis[.]com/ajax/libs/jquery/",
            "^https://cdnjs[.]cloudflare[.]com/ajax/libs/jquery/",
            "^https://cdnjs[.]cloudflare[.]com/ajax/libs/bootstrap/"
        ]
        for filter in providerAssets + verificationAssets + scriptLibraries {
            list.append(rule(filter, action: "ignore-previous-rules"))
        }
        // WebKit rejects alternation in URL filters, so each ad host gets its own rule.
        for host in adHosts {
            list.append(rule("^https?://([a-z0-9-]+[.])*" + hostPattern(host) + "[/:]", action: "block"))
        }
        list.append([
            "trigger": ["url-filter": ".*"],
            "action": ["type": "css-display-none",
                       "selector": "[data-panelverse-hidden-ad],ins.adsbygoogle,[data-ad-slot],[data-ad-client],.ad-banner,.ad-overlay,.ad-popup"]
        ])
        return (try? JSONSerialization.data(withJSONObject: list)).flatMap { String(data: $0, encoding: .utf8) } ?? "[]"
    }()

    @MainActor static func compiledRules() async -> WKContentRuleList? {
        if let ruleTask { return await ruleTask.value }
        let task = Task { @MainActor () -> WKContentRuleList? in
            guard let store = WKContentRuleListStore.default() else { return nil }
            return await withCheckedContinuation { continuation in
                store.compileContentRuleList(forIdentifier: "PanelVerseReaderAdsV3",
                                             encodedContentRuleList: rules) { list, error in
                    if let error {
                        let code = (error as NSError).code
                        Logger(subsystem: "com.hazemserry.panelverse", category: "Reader")
                            .error("WebKit ad rules could not compile (\(code, privacy: .public))")
                    }
                    continuation.resume(returning: list)
                }
            }
        }
        ruleTask = task
        return await task.value
    }

    static func allowsVerificationFrame(_ url: URL) -> Bool {
        guard let parts = URLComponents(url: url, resolvingAgainstBaseURL: false),
              parts.scheme?.lowercased() == "https", parts.user == nil, parts.password == nil,
              parts.port == nil || parts.port == 443, let host = parts.host?.lowercased(),
              let prefix = verification[host] else { return false }
        return parts.path.hasPrefix(prefix)
    }

    static func isVerificationResponse(_ response: HTTPURLResponse, provider: String) -> Bool {
        provider == "readcomicsonline.ru" &&
            response.value(forHTTPHeaderField: "cf-mitigated")?.lowercased() == "challenge"
    }

    @MainActor static func installScript(in controller: WKUserContentController) {
        guard let script else { return }
        let source = "(\(script))('ads-on', location.href, null);"
        controller.addUserScript(WKUserScript(source: source, injectionTime: .atDocumentStart,
                                              forMainFrameOnly: true, in: .defaultClient))
    }

    private static func hostPattern(_ host: String) -> String {
        host.replacingOccurrences(of: ".", with: "[.]")
    }

    private static func rule(_ filter: String, action: String) -> [String: Any] {
        ["trigger": ["url-filter": filter], "action": ["type": action]]
    }
}
