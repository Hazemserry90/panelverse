import SwiftUI
import WebKit

struct DownloadChapter: Identifiable, Sendable {
    let address: URL
    let title: String
    let entryTitle: String
    let seriesID: String
    let provider: String
    let isManga: Bool
    let number: Double?
    var id: URL { address }

    init?(series: Series, url: URL, label: String? = nil) {
        guard series.isChapter(url), var parts = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
        guard let segment = Series.readerPath(url).last else { return nil }
        let pattern = series.isManga ? #"^c[0-9]+(?:\.[0-9]+)?(?:[-_][a-z0-9._-]+)?$"# :
            #"^(?:(?:issue[-_])?[0-9]+(?:\.[0-9]+)?(?:[-_][a-z0-9._-]+)?|(?:annual|special|giant|preview|fcbd|one[-_]shot)(?:[-_][a-z0-9._-]+|[0-9]+)?)$"#
        guard segment.utf8.count <= 80,
              segment.range(of: pattern, options: [.regularExpression, .caseInsensitive]) != nil else { return nil }
        parts.host = series.provider
        parts.port = nil
        parts.path = "/" + Series.readerPath(url).joined(separator: "/")
        parts.query = nil
        parts.fragment = nil
        guard let address = parts.url else { return nil }
        self.address = address
        seriesID = series.id
        provider = series.provider
        isManga = series.isManga
        let entry = series.isManga ? String(segment.dropFirst()) :
            segment.replacingOccurrences(of: #"^issue[-_]"#, with: "", options: [.regularExpression, .caseInsensitive])
        number = Double(entry.prefix { $0.isASCII && ($0.isNumber || $0 == ".") })
        let cleaned = label?.split(whereSeparator: \.isWhitespace).joined(separator: " ") ?? ""
        let heading = series.isManga ? cleaned :
            cleaned.replacingOccurrences(of: #"^Chapter\b"#, with: "Issue", options: [.regularExpression, .caseInsensitive])
        entryTitle = heading.isEmpty || heading == entry
            ? "\(series.isManga ? "Chapter" : "Issue") \(entry)" : String(heading.prefix(140))
        title = "\(series.title) · \(entryTitle)"
    }

    func allowsImage(_ url: URL) -> Bool {
        guard url.absoluteString.utf8.count <= 4096,
              let parts = URLComponents(url: url, resolvingAgainstBaseURL: false),
              parts.scheme?.lowercased() == "https", parts.user == nil, parts.password == nil,
              parts.port == nil || parts.port == 443, let host = parts.host?.lowercased() else { return false }
        return host == provider || host.hasSuffix("." + provider)
    }
}

enum ChapterDownloadError: LocalizedError {
    case listUnavailable, pagesUnavailable, providerResponse, invalidPage, providerHTTP(Int), interrupted

    var errorDescription: String? {
        switch self {
        case .listUnavailable: return "The provider’s list couldn’t load. Try again or open the web reader."
        case .pagesUnavailable: return "The provider’s page images aren’t available for download. Reload this chapter or issue and try again."
        case .providerResponse: return "The provider couldn’t send a page image. Check your connection and try again."
        case .invalidPage: return "A page isn’t a readable JPG, PNG, or WebP image."
        case .providerHTTP(let status): return "The reading source returned HTTP \(status). Try the web reader."
        case .interrupted: return "The download was interrupted. Start it again from the series."
        }
    }
}

@MainActor
final class ReaderDownloadSource {
    let chapter: DownloadChapter
    private let visibleURL: URL
    private weak var view: WKWebView?

    init(chapter: DownloadChapter, visibleURL: URL, view: WKWebView) {
        self.chapter = chapter
        self.visibleURL = visibleURL
        self.view = view
    }

    func pages() async throws -> [URL] {
        guard let view, view.url == visibleURL else { throw ChapterDownloadError.pagesUnavailable }
        let script = #"""
        if (location.href !== expected || document.readyState === 'loading') return null;
        let images = document.querySelectorAll(isManga ? '#imgs div[id^="page"] img' : '#reader-all img, #all img');
        const attrs = isManga ? ['data-src'] :
            ['data-background-image', 'data-cfsrc', 'data-lazy-src', 'data-src', 'data-original', 'src'];
        let sources;
        if (!isManga && !images.length) {
            const slug = location.pathname.split('/').filter(Boolean)[1];
            const prefix = '/uploads/manga/' + slug + '/chapters/';
            sources = Array.from(document.images, image =>
                attrs.map(attr => image.getAttribute(attr)).find(raw => {
                    try {
                        const url = new URL(raw || '', location.href);
                        return url.origin === location.origin && url.pathname.startsWith(prefix);
                    } catch (_) { return false; }
                })).filter(Boolean);
        } else {
            if (images.length > 1000) return null;
            sources = Array.from(images, image =>
                attrs.map(attr => image.getAttribute(attr)).find(value => value && value.trim()));
        }
        if (isManga && (!sources.length || sources.some(source => !source))) {
            for (const script of document.scripts) {
                const text = script.textContent;
                if (text.length > 2000000) continue;
                const names = new Set(Array.from(text.matchAll(/['"]data-src['"]\s*,\s*([\w$]+)/g), match => match[1]));
                if (!names.size) continue;
                const array = Array.from(text.matchAll(/\b(?:var|let|const)\s+([\w$]+)\s*=\s*\[([^\[\]]*)\]/g))
                    .find(match => names.has(match[1]));
                if (!array) continue;
                const values = Array.from(array[2].matchAll(/['"]([^'"\r\n]+)['"]/g), match => match[1].replace(/\\\//g, '/'));
                if (values.length && (!sources.length || values.length === sources.length)) {
                    sources = values;
                    break;
                }
            }
        }
        if (!sources.length || sources.length > 1000) return null;
        const pages = [];
        for (const raw of sources) {
            if (!raw || raw.length > 4096 || /[\u0000-\u0020\\]/.test(raw)) return null;
            try {
                const url = new URL(raw, location.href);
                if (url.protocol !== 'https:' || url.username || url.password || (url.port && url.port !== '443')) return null;
                pages.push(url.href);
            } catch (_) { return null; }
        }
        return pages;
        """#
        let value = try await view.callAsyncJavaScript(script, arguments: [
            "expected": visibleURL.absoluteString, "isManga": chapter.isManga
        ], in: nil, contentWorld: .defaultClient)
        guard view.url == visibleURL, let strings = value as? [String], (1...1000).contains(strings.count) else {
            throw ChapterDownloadError.pagesUnavailable
        }
        let urls = strings.compactMap { URL(string: $0) }.filter(chapter.allowsImage)
        guard urls.count == strings.count else { throw ChapterDownloadError.pagesUnavailable }
        return urls
    }
}

@MainActor
final class ProviderReaderLoader: NSObject, WKNavigationDelegate {
    private let series: Series
    private let address: URL
    private var committed = false
    private var loaded = false
    private var navigationError: Error?
    private static var pageLists: [URL: (pages: [URL], loadedAt: Date)] = [:]

    private init(series: Series, address: URL) {
        self.series = series
        self.address = address
    }

    static func chapters(for series: Series) async throws -> [DownloadChapter] {
        let loader = ProviderReaderLoader(series: series, address: series.chapterListAddress)
        return try await loader.extract(failure: .listUnavailable) { view, url in
            let script = #"""
            if (location.href !== expected || document.readyState === 'loading') return null;
            const root = location.pathname.replace(/\/+$/, '') + '/';
            const chapterLinks = isManga ? document.querySelectorAll('.chapter a[href]') : [];
            const links = chapterLinks.length ? chapterLinks : document.querySelectorAll('a[href]');
            const entries = [];
            for (const link of links) {
                if (link.protocol !== 'https:' || link.hostname.replace(/^www\./, '') !== location.hostname.replace(/^www\./, '') ||
                    !link.pathname.startsWith(root)) continue;
                const segment = link.pathname.slice(root.length).replace(/\/$/, '');
                if (!segment || segment.includes('/') || link.href.length > 4096) continue;
                entries.push({url: link.href, title: (link.querySelector('.text-brand-400') || link).textContent.trim().slice(0, 140)});
                if (entries.length >= 4000) break;
            }
            return entries;
            """#
            let value = try await view.callAsyncJavaScript(script, arguments: [
                "expected": url.absoluteString, "isManga": series.isManga
            ], in: nil, contentWorld: .defaultClient)
            guard let entries = value as? [[String: String]] else { return nil }
            var seen = Set<URL>()
            let chapters = entries.compactMap { entry -> DownloadChapter? in
                guard let raw = entry["url"], let link = URL(string: raw),
                      let chapter = DownloadChapter(series: series, url: link, label: entry["title"]),
                      seen.insert(chapter.address).inserted else { return nil }
                return chapter
            }
            return chapters.isEmpty ? nil : chapters
        }
    }

    static func pages(for series: Series, chapter: DownloadChapter) async throws -> [URL] {
        if let cached = pageLists[chapter.address], Date().timeIntervalSince(cached.loadedAt) < 120 {
            return cached.pages
        }
        let loader = ProviderReaderLoader(series: series, address: chapter.address)
        let pages: [URL] = try await loader.extract(failure: .pagesUnavailable) { view, url in
            try? await ReaderDownloadSource(chapter: chapter, visibleURL: url, view: view).pages()
        }
        if pageLists.count >= 12, let oldest = pageLists.min(by: { $0.value.loadedAt < $1.value.loadedAt }) {
            pageLists.removeValue(forKey: oldest.key)
        }
        pageLists[chapter.address] = (pages, Date())
        return pages
    }

    private func extract<Result>(failure: ChapterDownloadError,
                                 read: (WKWebView, URL) async throws -> Result?) async throws -> Result {
        guard accepts(address) else { throw failure }
        let configuration = WKWebViewConfiguration()
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        ReaderAdFilter.installScript(in: configuration.userContentController)
        if let rules = await ReaderAdFilter.compiledRules() {
            configuration.userContentController.add(rules)
        }
        try Task.checkCancellation()
        let view = WKWebView(frame: CGRect(x: 0, y: 0, width: 390, height: 844), configuration: configuration)
        view.navigationDelegate = self
        defer {
            view.navigationDelegate = nil
            view.stopLoading()
        }
        view.load(URLRequest(url: address, timeoutInterval: 18))
        var attemptsAfterLoad = 0
        for _ in 0..<60 {
            try Task.checkCancellation()
            if let navigationError { throw navigationError }
            if committed, let url = view.url, accepts(url) {
                if let result = try? await read(view, url), view.url == url { return result }
                if loaded { attemptsAfterLoad += 1 }
                if attemptsAfterLoad >= 9 { break }
            }
            try await Task.sleep(nanoseconds: 300_000_000)
        }
        throw failure
    }

    private func accepts(_ url: URL) -> Bool {
        series.contains(url) && Series.readerPath(url) == Series.readerPath(address)
    }

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let frame = action.targetFrame, let url = action.request.url else {
            decisionHandler(.cancel)
            return
        }
        let allowed = frame.isMainFrame ? accepts(url) : (series.allows(url) || ReaderAdFilter.allowsVerificationFrame(url))
        decisionHandler(allowed ? .allow : .cancel)
    }

    func webView(_ webView: WKWebView, decidePolicyFor response: WKNavigationResponse,
                 decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        if response.isForMainFrame {
            guard let http = response.response as? HTTPURLResponse, let url = http.url, accepts(url) else {
                navigationError = ChapterDownloadError.providerResponse
                decisionHandler(.cancel)
                return
            }
            // The visible web reader can present the provider’s verification screen.
            guard (200..<300).contains(http.statusCode) else {
                navigationError = ChapterDownloadError.providerHTTP(http.statusCode)
                decisionHandler(.cancel)
                return
            }
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) { committed = true }
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) { loaded = true }
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        if navigationError == nil { navigationError = error }
    }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        if navigationError == nil { navigationError = error }
    }
}

@MainActor
final class ChapterDownloads: ObservableObject {
    @Published private(set) var active: DownloadChapter?
    @Published private(set) var percent = 0
    @Published private(set) var cancelling = false
    @Published private(set) var failure: String?
    @Published private(set) var completed: OfflineBook?
    private var task: Task<Void, Never>?
    private static let interruptedKey = "panelverse.download.inProgress"

    init() {
        if UserDefaults.standard.bool(forKey: Self.interruptedKey) {
            failure = ChapterDownloadError.interrupted.localizedDescription
            UserDefaults.standard.removeObject(forKey: Self.interruptedKey)
        }
    }

    func start(_ source: ReaderDownloadSource, library: LibraryStore, account: AccountStore) {
        start(source.chapter, library: library, account: account, pages: source.pages)
    }

    func start(_ chapter: DownloadChapter, library: LibraryStore, account: AccountStore,
               pages: @escaping @MainActor () async throws -> [URL]) {
        guard let userID = account.currentUserID else {
            failure = "Sign in to download chapters and issues."
            return
        }
        guard active == nil, !library.books.contains(where: { $0.source == chapter.address }) else { return }
        failure = nil
        completed = nil
        percent = 0
        active = chapter
        UserDefaults.standard.set(true, forKey: Self.interruptedKey)
        task = Task {
            defer {
                UserDefaults.standard.removeObject(forKey: Self.interruptedKey)
                active = nil
                cancelling = false
                task = nil
            }
            do {
                let pageURLs = try await pages()
                try Task.checkCancellation()
                guard account.currentUserID == userID else { return }
                let book = try await ComicArchive.download(chapter, pages: pageURLs) { [weak self] percent in
                    self?.percent = percent
                }
                if Task.isCancelled || account.currentUserID != userID {
                    try ComicArchive.remove(book)
                } else {
                    library.add(book)
                    percent = 100
                    completed = book
                }
            } catch let error as ChapterDownloadError {
                if !Task.isCancelled { failure = error.localizedDescription }
            } catch let error as ComicArchive.ImportError {
                if !Task.isCancelled { failure = error.localizedDescription }
            } catch {
                if !Task.isCancelled { failure = "The download didn’t finish. Try again from the reader." }
            }
        }
    }

    func cancel() {
        guard let task else { return }
        cancelling = true
        task.cancel()
    }

    func dismissFailure() { failure = nil }
    func dismissCompletion() { completed = nil }
}

@MainActor
struct DownloadStatus: View {
    @EnvironmentObject private var downloads: ChapterDownloads

    var body: some View {
        if let active = downloads.active {
            VStack(alignment: .leading, spacing: 6) {
                Text(active.title).font(.caption).lineLimit(2)
                HStack {
                    ProgressView(value: Double(downloads.percent), total: 100)
                        .accessibilityLabel("Download progress")
                    Text("\(downloads.percent)%").font(.caption.monospacedDigit())
                    Button(downloads.cancelling ? "Cancelling…" : "Cancel") { downloads.cancel() }
                        .disabled(downloads.cancelling)
                }
            }
            .padding(12)
            .background(Ink.panel)
        } else if let message = downloads.failure {
            HStack {
                Text(message).font(.caption)
                Spacer()
                Button("Dismiss") { downloads.dismissFailure() }
            }
            .padding(12)
            .background(Ink.panel)
        } else if let book = downloads.completed {
            HStack {
                Label("Saved to Offline: \(book.title)", systemImage: "checkmark.circle")
                    .font(.caption)
                Spacer()
                Button("Dismiss") { downloads.dismissCompletion() }
            }
            .padding(12)
            .background(Ink.panel)
        }
    }
}
