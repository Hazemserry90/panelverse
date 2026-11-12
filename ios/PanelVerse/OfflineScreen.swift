import SwiftUI
import UIKit
import UniformTypeIdentifiers

@MainActor
struct OfflineScreen: View {
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var downloads: ChapterDownloads
    @State private var picker = false
    @State private var importing = false
    @State private var error: String?

    var body: some View {
        List {
            if downloads.active != nil || downloads.failure != nil || downloads.completed != nil {
                DownloadStatus()
            }
            ForEach(library.books) { book in
                NavigationLink {
                    OfflineReader(book: book)
                } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(book.title).font(.headline)
                        Text("Page \(library.page(book) + 1) of \(book.pageCount)")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
                .swipeActions {
                    Button("Remove", role: .destructive) {
                        do { try library.remove(book) }
                        catch { self.error = error.localizedDescription }
                    }
                }
            }
        }
        .scrollContentBackground(.hidden)
        .overlay {
            if library.books.isEmpty && downloads.active == nil && downloads.failure == nil && downloads.completed == nil {
                VStack(spacing: 12) {
                    Image(systemName: "books.vertical").font(.largeTitle)
                    Text("Download a chapter or issue from a series, or import a CBZ from Files.")
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.secondary)
                    Button("Import CBZ") { picker = true }.buttonStyle(.borderedProminent)
                }
                .padding(16)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .background(Ink.background)
        .navigationTitle("Offline")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                if importing { ProgressView() }
                else { Button("Import CBZ") { picker = true } }
            }
        }
        .disabled(importing)
        .fileImporter(isPresented: $picker, allowedContentTypes: [.data]) { result in
            importResult(result)
        }
        .alert("Offline library", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("OK", role: .cancel) { error = nil }
        } message: {
            Text(error ?? "")
        }
    }

    private func importResult(_ result: Result<URL, Error>) {
        switch result {
        case .failure(let failure):
            error = failure.localizedDescription
        case .success(let file):
            importing = true
            Task { @MainActor in
                defer { importing = false }
                do {
                    let book = try await Task.detached(priority: .userInitiated) {
                        try ComicArchive.importBook(file)
                    }.value
                    library.add(book)
                } catch {
                    self.error = error.localizedDescription
                }
            }
        }
    }
}

@MainActor
struct OfflineReader: View {
    let book: OfflineBook

    var body: some View { NativeReaderScreen(book: book) }
}

@MainActor
struct NativeReaderScreen: View {
    let book: OfflineBook?
    let series: Series?
    let chapter: DownloadChapter?
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var account: AccountStore
    @EnvironmentObject private var downloads: ChapterDownloads
    @State private var urls: [URL] = []
    @State private var page: Int?
    @State private var image: UIImage?
    @State private var pageCache = ReaderPageCache()
    @State private var chapterFailed = false
    @State private var chapterFailureMessage = ""
    @State private var pageFailed = false
    @State private var chapterRetry = 0
    @State private var pageRetry = 0
    @State private var directionOverride: Bool?
    @State private var showWeb = false
    @State private var showAccount = false

    init(series: Series, chapter: DownloadChapter) {
        book = nil
        self.series = series
        self.chapter = chapter
    }

    init(book: OfflineBook, series: Series? = nil) {
        self.book = book
        let matches = book.source.map { url in Series.catalog.filter { $0.isChapter(url) } } ?? []
        let match = series ?? matches.first { $0.id == book.seriesID } ?? (matches.count == 1 ? matches.first : nil)
        self.series = match
        chapter = match.flatMap { series in book.source.flatMap { DownloadChapter(series: series, url: $0) } }
    }

    private var pageCount: Int { book?.pageCount ?? urls.count }
    private var title: String { book?.title ?? chapter?.title ?? "Reader" }
    private var rightToLeft: Bool {
        directionOverride ?? series?.isManga ?? (book?.source?.pathComponents.dropFirst().first == "manga")
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Button("Back") { dismiss() }.buttonStyle(ReaderPillButtonStyle())
                Text(title)
                    .font(.subheadline)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityAddTraits(.isHeader)
                readerOptions
            }
            .padding(8)

            ZStack {
                Ink.background
                if let image, let page {
                    ZoomablePage(image: image) { direction in
                        let forward = (direction == .left) != rightToLeft
                        movePage(by: forward ? 1 : -1)
                    }
                        .id(page)
                        .accessibilityLabel("Page \(page + 1) of \(pageCount)")
                } else if chapterFailed || pageFailed {
                    VStack(spacing: 12) {
                        Text(chapterFailed ? chapterFailureMessage : "Page \((page ?? 0) + 1) couldn’t load.")
                            .multilineTextAlignment(.center)
                        Button("Retry") {
                            if chapterFailed { chapterRetry += 1 }
                            else { pageRetry += 1 }
                        }
                        .buttonStyle(ReaderPillButtonStyle())
                        if book == nil, series != nil {
                            Button("Open in web reader") { showWeb = true }
                                .buttonStyle(.bordered)
                        }
                    }
                    .padding()
                } else {
                    ProgressView().accessibilityLabel("Loading page")
                }
            }
            if pageCount > 0 { pageControls }
            if downloads.active != nil || downloads.failure != nil { DownloadStatus() }
        }
        .background(Ink.background)
        .toolbar(.hidden, for: .navigationBar, .tabBar)
        .task(id: chapterRetry) { await loadChapter() }
        .task(id: "\(page ?? -1):\(pageRetry)") { await loadPage() }
        .onDisappear { pageCache.keepOnly([]) }
        .sheet(isPresented: $showAccount) { AccountScreen() }
        .fullScreenCover(isPresented: $showWeb) {
            if let series { OnlineReaderScreen(series: series, initialAddress: chapter?.address) }
        }
    }

    private var readerOptions: some View {
        Menu {
            Button("Right to left") { directionOverride = true }
            Button("Left to right") { directionOverride = false }
            if let chapter, book == nil {
                Button("Open in web reader") { showWeb = true }
                if !urls.isEmpty && !library.books.contains(where: { $0.source == chapter.address }) {
                    Button(account.userID == nil ? "Sign in to download" :
                           (chapter.isManga ? "Download chapter" : "Download issue")) {
                        if account.currentUserID == nil { showAccount = true }
                        else {
                            let pages = urls
                            downloads.start(chapter, library: library, account: account) { pages }
                        }
                    }
                    .disabled(account.userID != nil && downloads.active != nil)
                }
            }
        } label: {
            Text("Reader options")
                .font(.subheadline.weight(.semibold))
                .lineLimit(1)
                .padding(.horizontal, 12)
                .frame(minHeight: 44)
                .foregroundStyle(Ink.paper)
                .background(Ink.accent, in: Capsule())
        }
    }

    private var pageControls: some View {
        VStack(spacing: 6) {
            HStack(spacing: 8) {
                if rightToLeft { nextButton } else { previousButton }
                Slider(value: Binding(get: { Double(page ?? 0) }, set: { page = Int($0) }),
                       in: 0...Double(max(1, pageCount - 1)), step: 1)
                    .environment(\.layoutDirection, rightToLeft ? .rightToLeft : .leftToRight)
                    .accessibilityLabel("Page")
                    .accessibilityValue("\((page ?? 0) + 1) of \(pageCount)")
                    .disabled(page == nil || pageCount < 2)
                if rightToLeft { previousButton } else { nextButton }
            }
            Text("\((page ?? 0) + 1) / \(pageCount)").font(.subheadline.monospacedDigit())
            ProgressView(value: Double((page ?? 0) + 1), total: Double(pageCount))
                .accessibilityHidden(true)
        }
        .padding(.horizontal, 8)
        .padding(.bottom, 6)
    }

    private var previousButton: some View {
        Button("Previous") { movePage(by: -1) }
            .buttonStyle(ReaderPillButtonStyle())
            .disabled((page ?? 0) <= 0)
    }

    private var nextButton: some View {
        Button("Next") { movePage(by: 1) }
            .buttonStyle(ReaderPillButtonStyle())
            .disabled(page == nil || (page ?? 0) + 1 >= pageCount)
    }

    private func movePage(by delta: Int) {
        guard let page, (0..<pageCount).contains(page + delta) else { return }
        self.page = page + delta
    }

    private func loadChapter() async {
        if let book {
            if page == nil {
                page = library.rememberedPage(book) ?? min(resumePage, max(0, book.pageCount - 1))
            }
            return
        }
        guard urls.isEmpty, let series, let chapter else { return }
        chapterFailed = false
        do {
            let pages = try await ProviderReaderLoader.pages(for: series, chapter: chapter)
            try Task.checkCancellation()
            urls = pages
            page = min(resumePage, pages.count - 1)
        } catch {
            if !Task.isCancelled {
                chapterFailureMessage = (error as? ChapterDownloadError)?.localizedDescription ??
                    "The provider’s pages couldn’t load."
                chapterFailed = true
            }
        }
    }

    private var resumePage: Int {
        guard let series, let chapter, let position = library.position(series),
              Series.readerPath(position.address) == Series.readerPath(chapter.address) else { return 0 }
        return max(0, position.pageIndex ?? 0)
    }

    private func loadPage() async {
        guard let page else { return }
        image = nil
        pageFailed = false
        do {
            let decoded: UIImage
            if let book {
                let task = Task.detached(priority: .userInitiated) { try ComicArchive.image(for: book, page: page) }
                decoded = try await withTaskCancellationHandler(operation: { try await task.value },
                                                                onCancel: { task.cancel() })
            } else if let chapter, urls.indices.contains(page) {
                let url = urls[page]
                let nearby = Array(urls[page..<min(page + 3, urls.count)])
                pageCache.keepOnly(nearby)
                decoded = try await pageCache.load(url, chapter: chapter)
            } else { return }
            guard !Task.isCancelled, self.page == page else { return }
            image = decoded
            if let book { library.rememberPage(page, of: book) }
            if let series, let chapter {
                library.remember(series, address: chapter.address, scroll: 0, pageIndex: page)
            }
            if book == nil, let chapter {
                for url in urls.dropFirst(page + 1).prefix(2) {
                    pageCache.prefetch(url, chapter: chapter)
                }
            }
        } catch {
            if !Task.isCancelled { pageFailed = true }
        }
    }
}

@MainActor
private final class ReaderPageCache {
    private let cache = NSCache<NSURL, UIImage>()
    private var requests: [URL: Task<UIImage, Error>] = [:]

    init() {
        cache.countLimit = 4
        cache.totalCostLimit = 48 * 1024 * 1024
    }

    func load(_ url: URL, chapter: DownloadChapter) async throws -> UIImage {
        if let image = cache.object(forKey: url as NSURL) { return image }
        return try await request(url, chapter: chapter, priority: .userInitiated).value
    }

    func prefetch(_ url: URL, chapter: DownloadChapter) {
        guard cache.object(forKey: url as NSURL) == nil else { return }
        _ = request(url, chapter: chapter, priority: .utility)
    }

    func keepOnly(_ urls: [URL]) {
        for url in Array(requests.keys) where !urls.contains(url) {
            requests.removeValue(forKey: url)?.cancel()
        }
    }

    private func request(_ url: URL, chapter: DownloadChapter, priority: TaskPriority) -> Task<UIImage, Error> {
        if let existing = requests[url] { return existing }
        let task = Task(priority: priority) { [weak self] in
            defer { if !Task.isCancelled { self?.requests.removeValue(forKey: url) } }
            let image = try await ComicArchive.onlineImage(url, chapter: chapter)
            try Task.checkCancellation()
            if let bitmap = image.cgImage {
                self?.cache.setObject(image, forKey: url as NSURL, cost: bitmap.bytesPerRow * bitmap.height)
            }
            return image
        }
        requests[url] = task
        return task
    }
}

struct ReaderPillButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .lineLimit(1)
            .padding(.horizontal, 14)
            .frame(minHeight: 44)
            .foregroundStyle(isEnabled ? Ink.paper : Color.secondary)
            .background(isEnabled ? Ink.accent : Ink.panel, in: Capsule())
            .opacity(configuration.isPressed ? 0.78 : 1)
    }
}

private struct ZoomablePage: UIViewRepresentable {
    let image: UIImage
    let onSwipe: (UISwipeGestureRecognizer.Direction) -> Void

    func makeUIView(context: Context) -> ZoomScrollView { ZoomScrollView() }
    func updateUIView(_ view: ZoomScrollView, context: Context) {
        view.imageView.image = image
        view.onSwipe = onSwipe
    }

    final class ZoomScrollView: UIScrollView, UIScrollViewDelegate {
        let imageView = UIImageView()
        var onSwipe: ((UISwipeGestureRecognizer.Direction) -> Void)?
        private var previousSize = CGSize.zero

        init() {
            super.init(frame: .zero)
            minimumZoomScale = 1
            maximumZoomScale = 4
            showsHorizontalScrollIndicator = false
            showsVerticalScrollIndicator = false
            delegate = self
            imageView.contentMode = .scaleAspectFit
            addSubview(imageView)
            let tap = UITapGestureRecognizer(target: self, action: #selector(toggleZoom(_:)))
            tap.numberOfTapsRequired = 2
            addGestureRecognizer(tap)
            for direction in [UISwipeGestureRecognizer.Direction.left, .right] {
                let swipe = UISwipeGestureRecognizer(target: self, action: #selector(turnPage(_:)))
                swipe.direction = direction
                addGestureRecognizer(swipe)
            }
        }

        required init?(coder: NSCoder) { return nil }

        override func layoutSubviews() {
            super.layoutSubviews()
            if previousSize != bounds.size {
                previousSize = bounds.size
                setZoomScale(1, animated: false)
                imageView.frame = CGRect(origin: .zero, size: bounds.size)
                contentSize = bounds.size
            }
        }

        func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }

        @objc private func turnPage(_ swipe: UISwipeGestureRecognizer) {
            if zoomScale <= minimumZoomScale { onSwipe?(swipe.direction) }
        }

        @objc private func toggleZoom(_ tap: UITapGestureRecognizer) {
            if zoomScale > 1 {
                setZoomScale(1, animated: true)
            } else {
                let point = tap.location(in: imageView)
                let size = CGSize(width: bounds.width / 2, height: bounds.height / 2)
                zoom(to: CGRect(x: point.x - size.width / 2, y: point.y - size.height / 2,
                                width: size.width, height: size.height), animated: true)
            }
        }
    }
}
