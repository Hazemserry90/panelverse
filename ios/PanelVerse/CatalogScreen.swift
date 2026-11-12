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
    @State private var showReader = false

    var body: some View {
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
                Text(series.provider).foregroundStyle(.secondary)
                Button {
                    showReader = true
                } label: {
                    Text(library.position(series) == nil ? "Start reading" : "Resume reading")
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: 36)
                }
                .buttonStyle(.borderedProminent)
                HStack {
                    Button {
                        library.toggleFavorite(series)
                    } label: {
                        Label(library.isFavorite(series) ? "Favorited" : "Favorite",
                              systemImage: library.isFavorite(series) ? "star.fill" : "star")
                    }
                    Spacer()
                    ShareLink(item: series.address) { Label("Share", systemImage: "square.and.arrow.up") }
                }
                .buttonStyle(.bordered)
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
                        .background(Ink.panel)
                }
            }
            .padding(16)
        }
        .background(Ink.background)
        .navigationBarTitleDisplayMode(.inline)
        .fullScreenCover(isPresented: $showReader) {
            OnlineReaderScreen(series: series)
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
            }
            .padding(.horizontal, 12)
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
                        .fixedSize(horizontal: true, vertical: false)
                        .padding(.horizontal, 8)
                        .frame(minHeight: 44)
                        .foregroundStyle(shelf == name ? Ink.background : Ink.paper)
                        .background(shelf == name ? Ink.paper : Ink.panel)
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(shelf == name ? .isSelected : [])
            }
        }
    }
}
