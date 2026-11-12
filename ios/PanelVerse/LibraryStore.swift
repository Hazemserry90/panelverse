import Foundation
import SwiftUI

struct Series: Decodable, Hashable, Identifiable {
    let id: String
    let title: String
    let category: String
    let cover: String
    let address: URL

    var isManga: Bool { category == "Manga" }
    var provider: String { address.host?.replacingOccurrences(of: "www.", with: "") ?? "" }

    func allows(_ url: URL) -> Bool {
        guard let parts = URLComponents(url: url, resolvingAgainstBaseURL: false),
              parts.scheme?.lowercased() == "https", parts.user == nil, parts.password == nil,
              parts.port == nil || parts.port == 443, !provider.isEmpty else { return false }
        return parts.host?.lowercased() == provider || parts.host?.lowercased() == "www." + provider
    }

    func contains(_ url: URL) -> Bool {
        guard allows(url) else { return false }
        let root = address.pathComponents.prefix(3)
        guard url.pathComponents.starts(with: root), url.pathComponents.count <= 4 else { return false }
        if id == "bleachtybw", url.pathComponents.count == 4 {
            guard let chapter = Double(url.lastPathComponent.dropFirst()) else { return false }
            return (480...686).contains(chapter)
        }
        return true
    }

    func isChapter(_ url: URL) -> Bool {
        contains(url) && url.pathComponents.count == 4
    }

    static let catalog: [Series] = {
        guard let file = Bundle.main.url(forResource: "catalog", withExtension: "json"),
              let data = try? Data(contentsOf: file) else { return [] }
        return (try? JSONDecoder().decode([Series].self, from: data)) ?? []
    }()
}

enum ReadingList: String, Codable, CaseIterable, Identifiable {
    case reading = "Reading"
    case want = "Want to Read"
    case finished = "Finished"

    var id: Self { self }
}

struct ReadingPosition: Codable {
    let address: URL
    let scroll: Double
    let updatedAt: Date
}

struct OfflineBook: Codable, Identifiable, Hashable {
    let id: UUID
    let title: String
    let pageCount: Int
}

@MainActor
final class LibraryStore: ObservableObject {
    private struct Record: Codable {
        var favorites: Set<String> = []
        var lists: [String: ReadingList] = [:]
        var positions: [String: ReadingPosition] = [:]
        var books: [OfflineBook] = []
        var offlinePositions: [UUID: Int] = [:]
    }

    @Published private var record: Record
    private static let key = "panelverse.library.v1"

    init() {
        let stored = UserDefaults.standard.data(forKey: Self.key)
        record = stored.flatMap { try? JSONDecoder().decode(Record.self, from: $0) } ?? Record()
    }

    var books: [OfflineBook] { record.books }
    func isFavorite(_ series: Series) -> Bool { record.favorites.contains(series.id) }
    func list(_ series: Series) -> ReadingList? { record.lists[series.id] }
    func position(_ series: Series) -> ReadingPosition? { record.positions[series.id] }

    var lastRead: Series? {
        Series.catalog.filter { record.positions[$0.id] != nil }.max {
            (record.positions[$0.id]?.updatedAt ?? .distantPast) <
            (record.positions[$1.id]?.updatedAt ?? .distantPast)
        }
    }

    func toggleFavorite(_ series: Series) {
        if isFavorite(series) { record.favorites.remove(series.id) }
        else { record.favorites.insert(series.id) }
        persist()
    }

    func move(_ series: Series, to list: ReadingList?) {
        record.lists[series.id] = list
        persist()
    }

    func remember(_ series: Series, address: URL, scroll: Double) {
        guard series.isChapter(address), scroll.isFinite else { return }
        record.positions[series.id] = ReadingPosition(address: address, scroll: max(0, scroll), updatedAt: .now)
        if record.lists[series.id] != .finished { record.lists[series.id] = .reading }
        persist()
    }

    func add(_ book: OfflineBook) {
        record.books.insert(book, at: 0)
        persist()
    }

    func remove(_ book: OfflineBook) throws {
        try ComicArchive.remove(book)
        record.books.removeAll { $0.id == book.id }
        record.offlinePositions.removeValue(forKey: book.id)
        persist()
    }

    func page(_ book: OfflineBook) -> Int {
        min(max(record.offlinePositions[book.id] ?? 0, 0), max(book.pageCount - 1, 0))
    }

    func rememberPage(_ index: Int, of book: OfflineBook) {
        guard (0..<book.pageCount).contains(index) else { return }
        record.offlinePositions[book.id] = index
        persist()
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(record) else { return }
        UserDefaults.standard.set(data, forKey: Self.key)
    }
}
