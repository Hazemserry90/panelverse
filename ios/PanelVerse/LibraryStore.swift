import Foundation
import SwiftUI
import FirebaseDatabase

struct Series: Decodable, Hashable, Identifiable {
    let id: String
    let title: String
    let category: String
    let cover: String?
    let address: URL

    var isManga: Bool { category == "Manga" }
    var provider: String { address.host?.replacingOccurrences(of: "www.", with: "") ?? "" }
    static func hasUnsafeCharacters(_ value: String) -> Bool {
        value.unicodeScalars.contains {
            CharacterSet.controlCharacters.contains($0) || CharacterSet.whitespacesAndNewlines.contains($0) ||
            $0.properties.generalCategory == .format || $0.value == 92
        }
    }

    static func readerPath(_ url: URL) -> [String] {
        guard let path = URLComponents(url: url, resolvingAgainstBaseURL: false)?.percentEncodedPath else { return [] }
        var components: [String] = []
        for segment in path.split(separator: "/") {
            guard let decoded = String(segment).removingPercentEncoding,
                  decoded != ".", decoded != "..", !decoded.contains("/"), !decoded.contains("\\"),
                  !hasUnsafeCharacters(decoded) else { return [] }
            components.append(decoded)
        }
        return components
    }

    var chapterListAddress: URL {
        var components = URLComponents(url: address, resolvingAgainstBaseURL: false)
        components?.path = "/" + Self.readerPath(address).prefix(2).joined(separator: "/")
        components?.query = nil
        components?.fragment = nil
        return components?.url ?? address
    }

    func allows(_ url: URL) -> Bool {
        guard url.absoluteString.utf8.count <= 4096, !Self.hasUnsafeCharacters(url.absoluteString),
              let parts = URLComponents(url: url, resolvingAgainstBaseURL: false),
              parts.scheme?.lowercased() == "https", parts.user == nil, parts.password == nil,
              parts.port == nil || parts.port == 443,
              ["mangakatana.com", "readcomicsonline.ru"].contains(provider),
              ![parts.query, parts.fragment].compactMap({ $0 }).contains(where: {
                  Self.hasUnsafeCharacters($0.replacingOccurrences(of: " ", with: ""))
              }) else { return false }
        return parts.host?.lowercased() == provider || parts.host?.lowercased() == "www." + provider
    }

    func contains(_ url: URL) -> Bool {
        guard allows(url) else { return false }
        let root = Self.readerPath(address).prefix(2)
        let path = Self.readerPath(url)
        guard root.count == 2, path.starts(with: root) else { return false }
        if path.count == 4 {
            return provider == "readcomicsonline.ru" &&
                path[3].range(of: #"^[1-9][0-9]{0,3}$"#, options: .regularExpression) != nil &&
                canonicalChapterSegment(path[2])
        }
        guard path.count <= 3 else { return false }
        if id == "bleachtybw", path.count == 3 {
            guard let segment = path.last, let chapter = Double(segment.dropFirst()) else { return false }
            return (480...686).contains(chapter)
        }
        return path.count == 2 || canonicalChapterSegment(path[2])
    }

    func isChapter(_ url: URL) -> Bool {
        contains(url) && Self.readerPath(url).count >= 3
    }

    private func canonicalChapterSegment(_ segment: String) -> Bool {
        let pattern = isManga ? #"^c[0-9]+(?:\.[0-9]+)?(?:[-_][a-z0-9._-]+)?$"# :
            #"^(?:(?:issue[-_])?[0-9]+(?:\.[0-9]+)?(?:[-_][a-z0-9._-]+)?|(?:annual|special|giant|preview|fcbd|one[-_]shot)(?:[-_][a-z0-9._-]+|[0-9]+)?)$"#
        return segment.utf8.count <= 80 &&
            segment.range(of: pattern, options: [.regularExpression, .caseInsensitive]) != nil
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
    var databaseValue: String {
        switch self {
        case .reading: return "reading"
        case .want: return "want"
        case .finished: return "finished"
        }
    }
}

struct ReadingPosition: Codable {
    let address: URL
    let scroll: Double
    let updatedAt: Date
    var pageIndex: Int? = nil
    var anchor: ReaderPageAnchor? = nil
    var totalPages: Int? = nil
}

struct ReaderPageAnchor: Codable {
    let key: String
    let offset: Int

    var isValid: Bool { key.hasPrefix("/") && key.utf8.count <= 4096 && (0...10_000).contains(offset) }
}

enum ReaderDirection: String, CaseIterable {
    case rightToLeft = "Right to left"
    case leftToRight = "Left to right"
    case vertical = "Vertical scroll"
}

struct OfflineBook: Codable, Identifiable, Hashable, Sendable {
    let id: UUID
    let title: String
    let pageCount: Int
    var source: URL? = nil
    var seriesID: String? = nil
    var finalEntry: Bool? = nil
}

@MainActor
final class LibraryStore: ObservableObject {
    private struct Record: Codable {
        var favorites: Set<String> = []
        var lists: [String: ReadingList] = [:]
        var positions: [String: ReadingPosition] = [:]
        var books: [OfflineBook] = []
        var offlinePositions: [UUID: Int] = [:]
        var hiddenFromReading: Set<String>? = nil
    }

    @Published private var record: Record
    @Published private(set) var syncError = false
    @Published private(set) var username = ""
    @Published private var accountFavorites: Set<String> = []
    @Published private var accountLists: [String: ReadingList] = [:]
    private var accountReference: DatabaseReference?
    private var observer: DatabaseHandle?
    private static let key = "panelverse.library.v1"

    init() {
        let stored = UserDefaults.standard.data(forKey: Self.key)
        record = stored.flatMap { try? JSONDecoder().decode(Record.self, from: $0) } ?? Record()
        let knownIDs = Set(record.books.map(\.id))
        let recovered = ComicArchive.restoreDownloads(excluding: knownIDs)
        if !recovered.isEmpty {
            record.books.insert(contentsOf: recovered, at: 0)
            persist()
        }
    }

    var books: [OfflineBook] { record.books }
    func isFavorite(_ series: Series) -> Bool {
        (accountReference == nil ? record.favorites : accountFavorites).contains(series.id)
    }
    func list(_ series: Series) -> ReadingList? {
        let explicit = accountReference == nil ? record.lists[series.id] : accountLists[series.id]
        if let explicit { return explicit }
        guard record.hiddenFromReading?.contains(series.id) != true, record.positions[series.id] != nil else { return nil }
        return .reading
    }
    func position(_ series: Series) -> ReadingPosition? { record.positions[series.id] }

    var lastRead: Series? { history.first }

    var history: [Series] {
        Series.catalog.filter { record.positions[$0.id] != nil }.sorted {
            (record.positions[$0.id]?.updatedAt ?? .distantPast) >
            (record.positions[$1.id]?.updatedAt ?? .distantPast)
        }
    }

    func clearHistory() {
        record.positions.removeAll()
        persist()
    }

    func connectAccount(_ uid: String?) {
        if let observer { accountReference?.removeObserver(withHandle: observer) }
        observer = nil
        accountReference = nil
        accountFavorites = []
        accountLists = [:]
        syncError = false
        username = ""
        guard let uid else { return }
        let ref = Database.database(url: "https://anime-app-4f99d-default-rtdb.europe-west1.firebasedatabase.app")
            .reference().child("users").child(uid)
        accountReference = ref
        observer = ref.observe(.value, with: { [weak self] snapshot in
            let favorites = (snapshot.childSnapshot(forPath: "favorites").value as? [String: Bool]) ?? [:]
            let lists = (snapshot.childSnapshot(forPath: "savedLists").value as? [String: String]) ?? [:]
            let username = snapshot.childSnapshot(forPath: "username").value as? String ?? ""
            Task { @MainActor in
                guard let self, self.accountReference === ref else { return }
                self.accountFavorites = Set(favorites.filter { $0.value }.keys)
                self.username = username
                self.accountLists = lists.compactMapValues { value in
                    ReadingList.allCases.first { $0.databaseValue == value }
                }
                self.syncError = false
            }
        }, withCancel: { [weak self] _ in
            Task { @MainActor in
                if self?.accountReference === ref { self?.syncError = true }
            }
        })
    }

    func saveUsername(_ name: String) async throws {
        guard let accountReference else { throw CocoaError(.userCancelled) }
        _ = try await accountReference.child("username").setValue(name)
    }

    func toggleFavorite(_ series: Series) {
        if let ref = accountReference {
            let value: Bool? = isFavorite(series) ? nil : true
            write(value, to: ref.child("favorites").child(series.id))
            return
        }
        if isFavorite(series) { record.favorites.remove(series.id) }
        else { record.favorites.insert(series.id) }
        persist()
    }

    func move(_ series: Series, to list: ReadingList?) {
        if list == nil {
            var hidden = record.hiddenFromReading ?? []
            hidden.insert(series.id)
            record.hiddenFromReading = hidden
        } else {
            record.hiddenFromReading?.remove(series.id)
        }
        if let ref = accountReference {
            persist()
            write(list?.databaseValue, to: ref.child("savedLists").child(series.id))
            return
        }
        record.lists[series.id] = list
        persist()
    }

    func remember(_ series: Series, address: URL, scroll: Double, pageIndex: Int? = nil,
                  anchor: ReaderPageAnchor? = nil, totalPages: Int? = nil, finished: Bool = false) {
        guard series.isChapter(address), scroll.isFinite else { return }
        record.hiddenFromReading?.remove(series.id)
        record.positions[series.id] = ReadingPosition(address: address, scroll: max(0, scroll), updatedAt: .now,
                                                      pageIndex: pageIndex, anchor: anchor, totalPages: totalPages)
        if accountReference != nil {
            if finished && list(series) != .finished { move(series, to: .finished) }
            else if list(series) == .want { move(series, to: .reading) }
        } else if finished { record.lists[series.id] = .finished }
        else if record.lists[series.id] != .finished { record.lists[series.id] = .reading }
        persist()
    }

    private func write(_ value: Any?, to reference: DatabaseReference) {
        let root = accountReference
        Task { [weak self] in
            guard let self, accountReference === root else { return }
            do {
                _ = try await reference.setValue(value)
            } catch {
                if accountReference === root { syncError = true }
            }
        }
    }

    func direction(for series: Series) -> ReaderDirection? {
        UserDefaults.standard.string(forKey: "panelverse.direction.\(series.id)").flatMap(ReaderDirection.init(rawValue:))
    }

    func setDirection(_ direction: ReaderDirection, for series: Series) {
        UserDefaults.standard.set(direction.rawValue, forKey: "panelverse.direction.\(series.id)")
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
        rememberedPage(book) ?? 0
    }

    func rememberedPage(_ book: OfflineBook) -> Int? {
        record.offlinePositions[book.id].map { min(max($0, 0), max(book.pageCount - 1, 0)) }
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
