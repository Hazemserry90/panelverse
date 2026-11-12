import SwiftUI
import WebKit

struct DownloadChapter: Sendable {
    let address: URL
    let title: String
    let provider: String
    let isManga: Bool

    init?(series: Series, url: URL) {
        guard series.isChapter(url), var parts = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
        let segment = url.lastPathComponent
        let pattern = series.isManga ? #"^c[0-9]+(?:\.[0-9]+)?(?:[-_][a-z0-9._-]+)?$"# :
            #"^(?:(?:issue[-_])?[0-9]+(?:\.[0-9]+)?(?:[-_][a-z0-9._-]+)?|(?:annual|special|giant|preview|fcbd|one[-_]shot)(?:[-_][a-z0-9._-]+|[0-9]+)?)$"#
        guard segment.utf8.count <= 80,
              segment.range(of: pattern, options: [.regularExpression, .caseInsensitive]) != nil else { return nil }
        parts.host = series.provider
        parts.port = nil
        parts.query = nil
        parts.fragment = nil
        guard let address = parts.url else { return nil }
        self.address = address
        provider = series.provider
        isManga = series.isManga
        let entry = series.isManga ? String(segment.dropFirst()) :
            segment.replacingOccurrences(of: #"^issue[-_]"#, with: "", options: [.regularExpression, .caseInsensitive])
        title = "\(series.title) · \(series.isManga ? "Chapter" : "Issue") \(entry)"
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
    case pagesUnavailable, providerResponse, invalidPage

    var errorDescription: String? {
        switch self {
        case .pagesUnavailable: return "The provider’s page images aren’t available for download. Reload this chapter or issue and try again."
        case .providerResponse: return "The provider couldn’t send a page image. Check your connection and try again."
        case .invalidPage: return "A page isn’t a readable JPG, PNG, or WebP image."
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
        if (location.href !== expected) return null;
        const images = document.querySelectorAll(isManga ? '#imgs div[id^="page"] img' : '#reader-all img, #all img');
        if (!images.length || images.length > 1000) return null;
        const attrs = isManga ? ['data-src'] :
            ['data-background-image', 'data-cfsrc', 'data-lazy-src', 'data-src', 'data-original', 'src'];
        const pages = [];
        for (const image of images) {
            const raw = attrs.map(attr => image.getAttribute(attr)).find(value => value && value.trim());
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
final class ChapterDownloads: ObservableObject {
    @Published private(set) var active: DownloadChapter?
    @Published private(set) var percent = 0
    @Published private(set) var cancelling = false
    @Published private(set) var failure: String?
    private var task: Task<Void, Never>?

    func start(_ source: ReaderDownloadSource, library: LibraryStore, account: AccountStore) {
        guard let userID = account.currentUserID else {
            failure = "Sign in to download chapters and issues."
            return
        }
        let chapter = source.chapter
        guard active == nil, !library.books.contains(where: { $0.source == chapter.address }) else { return }
        failure = nil
        percent = 0
        active = chapter
        task = Task {
            defer {
                active = nil
                cancelling = false
                task = nil
            }
            do {
                let pages = try await source.pages()
                try Task.checkCancellation()
                guard account.currentUserID == userID else { return }
                let book = try await ComicArchive.download(chapter, pages: pages) { [weak self] percent in
                    self?.percent = percent
                }
                if Task.isCancelled || account.currentUserID != userID { try ComicArchive.remove(book) }
                else { library.add(book) }
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
        }
    }
}
