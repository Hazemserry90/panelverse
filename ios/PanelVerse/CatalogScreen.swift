import SwiftUI
import UIKit

@MainActor
struct CatalogScreen: View {
    var discover = false
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var account: AccountStore
    @State private var search = ""
    @State private var category = "All"
    @State private var showAccount = false

    private var results: [Series] {
        Series.catalog.filter { series in
            (category == "All" || series.category == category) &&
            (search.isEmpty || series.title.localizedCaseInsensitiveContains(search))
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if !discover && search.isEmpty, let recent = library.lastRead {
                    NavigationLink {
                        SeriesScreen(series: recent)
                    } label: {
                        HStack(spacing: 14) {
                            Cover(series: recent).frame(width: 64, height: 92).clipped()
                            VStack(alignment: .leading, spacing: 6) {
                                Text("LAST OPENED").font(.caption2.bold()).foregroundStyle(.secondary)
                                Text(recent.title).font(.headline)
                                Text("Resume reading").font(.subheadline.bold()).foregroundStyle(Ink.paper)
                            }
                            Spacer()
                            Image(systemName: "chevron.right").accessibilityHidden(true)
                        }
                        .padding(12)
                        .background(Ink.panel)
                        .overlay { Rectangle().strokeBorder(.white.opacity(0.16)) }
                    }
                    .buttonStyle(.plain)
                }
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(["All", "Manga", "Marvel", "DC", "Invincible", "Comics"], id: \.self) { option in
                            Button {
                                category = option
                            } label: {
                                Text(option)
                                    .font(.subheadline.weight(.semibold))
                                    .padding(.horizontal, 12)
                                    .frame(minHeight: 44)
                                    .foregroundStyle(category == option ? Ink.background : Ink.paper)
                                    .background(category == option ? Ink.paper : Ink.panel)
                            }
                            .buttonStyle(.plain)
                            .accessibilityAddTraits(category == option ? .isSelected : [])
                        }
                    }
                }
                if results.isEmpty {
                    Text("No titles match your search.").foregroundStyle(.secondary)
                } else {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 100, maximum: 180), spacing: 10)], alignment: .leading, spacing: 14) {
                        ForEach(results) { series in
                            NavigationLink {
                                SeriesScreen(series: series)
                            } label: {
                                VStack(alignment: .leading, spacing: 6) {
                                    Cover(series: series).aspectRatio(2 / 3, contentMode: .fill).clipped()
                                    Text(series.title)
                                        .font(.subheadline.weight(.semibold))
                                        .lineLimit(3)
                                        .fixedSize(horizontal: false, vertical: true)
                                        .frame(maxWidth: .infinity, minHeight: 54, alignment: .topLeading)
                                        .padding(.horizontal, 6)
                                }
                                .padding(.bottom, 4)
                                .background(Ink.panel)
                                .overlay { Rectangle().strokeBorder(.white.opacity(0.12)) }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            .padding(16)
        }
        .background(Ink.background)
        .navigationTitle(discover ? "Discover" : "PANELVERSE")
        .searchable(text: $search, prompt: "Search manga & comics")
        .toolbar {
            if !discover {
                ToolbarItem(placement: .primaryAction) {
                    Button { showAccount = true } label: {
                        Label(account.email == nil ? "Sign in" : "Account", systemImage: "person.crop.circle")
                    }
                }
            }
        }
        .sheet(isPresented: $showAccount) { AccountScreen() }
    }
}

struct Cover: View {
    let series: Series

    var body: some View {
        GeometryReader { geometry in
            if let cover = series.cover,
               let url = Bundle.main.url(forResource: cover, withExtension: "webp", subdirectory: "drawable-nodpi"),
               let image = UIImage(contentsOfFile: url.path) {
                Image(uiImage: image)
                    .resizable().scaledToFill()
                    .frame(width: geometry.size.width, height: geometry.size.height)
                    .clipped()
            } else {
                Rectangle().fill(Ink.paper).overlay {
                    Rectangle().strokeBorder(Ink.background, lineWidth: 1).padding(5)
                    Image(systemName: "book.closed").font(.title).foregroundStyle(Ink.background)
                }
            }
        }
        .accessibilityHidden(true)
    }
}

@MainActor
struct SeriesScreen: View {
    let series: Series
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var account: AccountStore
    @EnvironmentObject private var downloads: ChapterDownloads
    @State private var reader: ReaderDestination?
    @State private var showAccount = false
    @State private var chapters: [DownloadChapter] = []
    @State private var loadingChapters = true
    @State private var chapterListFailed = false
    @State private var retryList = 0

    private enum ReaderDestination: Identifiable {
        case native(DownloadChapter)
        case offline(OfflineBook)
        case web(URL)

        var id: String {
            switch self {
            case .native(let chapter): return "native:\(chapter.address)"
            case .offline(let book): return "offline:\(book.id)"
            case .web(let address): return "web:\(address)"
            }
        }
    }

    private var displayedChapters: [DownloadChapter] {
        let listed = Set(chapters.map(\.address))
        let offline = library.books.compactMap { book -> DownloadChapter? in
            guard let url = book.source, let entry = DownloadChapter(series: series, url: url),
                  !listed.contains(entry.address) else { return nil }
            return entry
        }
        return (chapters + offline).sorted {
            let first = $0.number ?? .infinity
            let second = $1.number ?? .infinity
            if first != second { return first < second }
            return $0.entryTitle.localizedStandardCompare($1.entryTitle) == .orderedAscending
        }
    }

    var body: some View {
        let entries = displayedChapters
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Cover(series: series)
                    .frame(width: 164, height: 246)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
                    .background(Ink.panel)
                Text(series.category.uppercased())
                    .font(.caption.bold())
                    .padding(.horizontal, 8).padding(.vertical, 4)
                    .background(Ink.paper).foregroundStyle(Ink.background)
                Text(series.title).font(.largeTitle.weight(.black))
                Text("Reading source · \(series.provider)").foregroundStyle(.secondary)
                Button {
                    startReading()
                } label: {
                    Text(library.position(series) == nil ? "Start reading" : "Resume reading")
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: 36)
                }
                .buttonStyle(.borderedProminent)
                .disabled(loadingChapters && entries.isEmpty && library.position(series) == nil && !series.isChapter(series.address))
                HStack {
                    Button {
                        library.toggleFavorite(series)
                    } label: {
                        Label(library.isFavorite(series) ? "Favorited" : "Favorite",
                              systemImage: library.isFavorite(series) ? "star.fill" : "star")
                            .frame(maxWidth: .infinity, minHeight: 36)
                    }
                    ShareLink(item: series.address) {
                        Label("Share", systemImage: "square.and.arrow.up")
                            .frame(maxWidth: .infinity, minHeight: 36)
                    }
                }
                .buttonStyle(.bordered)
                .tint(Ink.paper)
                Menu {
                    ForEach(ReadingList.allCases) { list in
                        Button(list.rawValue) { library.move(series, to: list) }
                    }
                    if library.list(series) != nil {
                        Button("Remove from reading list", role: .destructive) { library.move(series, to: nil) }
                    }
                } label: {
                    Label(library.list(series)?.rawValue ?? "Want to Read", systemImage: "bookmark")
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .foregroundStyle(Ink.paper)
                        .background(Ink.panel, in: RoundedRectangle(cornerRadius: 12))
                }
                chapterList(entries)
            }
            .padding(16)
        }
        .background(Ink.background)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: retryList) { await loadChapters() }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if downloads.failure != nil { DownloadStatus() }
        }
        .fullScreenCover(item: $reader) { destination in
            switch destination {
            case .native(let chapter): NativeReaderScreen(series: series, chapter: chapter)
            case .offline(let book): NativeReaderScreen(book: book, series: series)
            case .web(let address): OnlineReaderScreen(series: series, initialAddress: address)
            }
        }
        .sheet(isPresented: $showAccount) { AccountScreen() }
    }

    private func chapterList(_ entries: [DownloadChapter]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Divider()
            Text(series.isManga ? "Chapters" : "Issues")
                .font(.headline)
                .accessibilityAddTraits(.isHeader)
            Button("Open in web reader") { reader = .web(series.chapterListAddress) }
                .buttonStyle(.bordered)
                .tint(Ink.paper)
            if loadingChapters {
                ProgressView().accessibilityLabel(series.isManga ? "Loading chapters" : "Loading issues")
            } else if chapterListFailed {
                Text(ChapterDownloadError.listUnavailable.localizedDescription)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Button("Retry") { retryList += 1 }.buttonStyle(.bordered)
            }
            if !entries.isEmpty {
                if account.userID == nil {
                    Text("Sign in to download.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                LazyVStack(spacing: 6) {
                    ForEach(entries) { chapter in
                        chapterRow(chapter)
                    }
                }
            }
        }
        .padding(.top, 8)
    }

    private func chapterRow(_ chapter: DownloadChapter) -> some View {
        let book = library.books.first { $0.source == chapter.address }
        let active = downloads.active?.address == chapter.address
        let label = book != nil ? "Open offline" :
            (active ? (downloads.cancelling ? "Cancelling…" : "Cancel \(downloads.percent)%") :
                (account.userID == nil ? "Sign in" : "Download"))
        return HStack(spacing: 8) {
            Button {
                open(chapter)
            } label: {
                Text(chapter.entryTitle)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            }
            .buttonStyle(.plain)
            Button(label) {
                if let book { reader = .offline(book) }
                else if active { downloads.cancel() }
                else if account.currentUserID == nil { showAccount = true }
                else {
                    downloads.start(chapter, library: library, account: account) {
                        try await ProviderReaderLoader.pages(for: series, chapter: chapter)
                    }
                }
            }
            .buttonStyle(ReaderPillButtonStyle())
            .disabled(active ? downloads.cancelling : (book == nil && account.userID != nil && downloads.active != nil))
            .accessibilityLabel("\(label): \(chapter.entryTitle)")
        }
    }

    private func loadChapters() async {
        loadingChapters = true
        chapterListFailed = false
        do {
            let entries = try await ProviderReaderLoader.chapters(for: series)
            try Task.checkCancellation()
            chapters = entries
            loadingChapters = false
        } catch {
            guard !Task.isCancelled else { return }
            chapterListFailed = true
            loadingChapters = false
        }
    }

    private func open(_ chapter: DownloadChapter) {
        if let book = library.books.first(where: { $0.source == chapter.address }) {
            reader = .offline(book)
        } else {
            reader = .native(chapter)
        }
    }

    private func startReading() {
        if let position = library.position(series), let chapter = DownloadChapter(series: series, url: position.address) {
            if position.pageIndex == nil, position.scroll > 0,
               !library.books.contains(where: { $0.source == chapter.address }) {
                reader = .web(position.address)
            } else {
                open(chapters.first { $0.address == chapter.address } ?? chapter)
            }
        } else if let first = displayedChapters.first ?? DownloadChapter(series: series, url: series.address) {
            open(first)
        } else {
            reader = .web(series.chapterListAddress)
        }
    }
}

@MainActor
struct LibraryScreen: View {
    @EnvironmentObject private var library: LibraryStore
    @State private var shelf = "Reading"

    private var titles: [Series] {
        let filtered = Series.catalog.filter { series in
            shelf == "Favorites" ? library.isFavorite(series) : library.list(series)?.rawValue == shelf
        }
        if shelf == "Reading" {
            return filtered.sorted {
                (library.position($0)?.updatedAt ?? .distantPast) > (library.position($1)?.updatedAt ?? .distantPast)
            }
        }
        return filtered.sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
    }

    var body: some View {
        VStack {
            ViewThatFits(in: .horizontal) {
                shelfButtons
                ScrollView(.horizontal, showsIndicators: false) {
                    shelfButtons
                }
                .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 12)
            .padding(.top, 4)
            if titles.isEmpty {
                Spacer()
                Text("Nothing in this list yet.").foregroundStyle(.secondary)
                Spacer()
            } else {
                List(titles) { series in
                    NavigationLink {
                        SeriesScreen(series: series)
                    } label: {
                        HStack(spacing: 12) {
                            Cover(series: series).frame(width: 52, height: 76)
                            VStack(alignment: .leading, spacing: 5) {
                                Text(series.title).font(.headline)
                                Text(series.category).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                    .contextMenu {
                        ForEach(ReadingList.allCases) { list in
                            Button(list.rawValue) { library.move(series, to: list) }
                        }
                    }
                }
                .scrollContentBackground(.hidden)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Ink.background)
        .navigationTitle("Library")
    }

    private var shelfButtons: some View {
        HStack(spacing: 4) {
            ForEach(ReadingList.allCases.map(\.rawValue) + ["Favorites"], id: \.self) { name in
                Button {
                    shelf = name
                } label: {
                    Text(name)
                        .font(.subheadline.weight(.semibold))
                        .padding(.horizontal, 8)
                        .frame(minHeight: 44)
                        .fixedSize()
                        .foregroundStyle(shelf == name ? Ink.background : Ink.paper)
                        .background(shelf == name ? Ink.paper : Ink.panel, ignoresSafeAreaEdges: [])
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(shelf == name ? .isSelected : [])
            }
        }
        .fixedSize()
    }
}
