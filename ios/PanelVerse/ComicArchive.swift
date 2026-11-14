import Foundation
import ImageIO
import UIKit
import ZIPFoundation

enum ComicArchive {
    private static let archiveLimit = 250 * 1024 * 1024
    private static let pageLimit = 24 * 1024 * 1024
    private static let expandedLimit: UInt64 = 512 * 1024 * 1024
    private static let imageTypes = ["jpg": "public.jpeg", "jpeg": "public.jpeg",
                                     "png": "public.png", "webp": "org.webmproject.webp"]
    private static let activeTypes: Set<String> = [
        "apk", "bat", "cmd", "com", "dex", "dll", "exe", "hta", "html", "htm",
        "jar", "js", "lnk", "msi", "ps1", "scr", "sh", "svg", "vbs"
    ]
    private static let readerSession = pageSession()

    enum ImportError: LocalizedError {
        case invalid, tooLarge

        var errorDescription: String? {
            switch self {
            case .invalid: return "This file isn’t a readable CBZ with JPG, PNG, or WebP pages."
            case .tooLarge: return "This CBZ exceeds the archive or image size limit."
            }
        }
    }

    static func importBook(_ source: URL) throws -> OfflineBook {
        let granted = source.startAccessingSecurityScopedResource()
        defer { if granted { source.stopAccessingSecurityScopedResource() } }
        guard source.pathExtension.lowercased() == "cbz" else { throw ImportError.invalid }
        let id = UUID()
        let folder = try storage()
        let temporary = folder.appendingPathComponent(id.uuidString + ".partial")
        let destination = folder.appendingPathComponent(id.uuidString + ".cbz")
        defer { try? FileManager.default.removeItem(at: temporary) }

        guard FileManager.default.createFile(atPath: temporary.path, contents: nil) else {
            throw CocoaError(.fileWriteUnknown)
        }
        let input = try FileHandle(forReadingFrom: source)
        defer { try? input.close() }
        let output = try FileHandle(forWritingTo: temporary)
        defer { try? output.close() }
        var bytes = 0
        while let chunk = try input.read(upToCount: 64 * 1024), !chunk.isEmpty {
            try Task.checkCancellation()
            bytes += chunk.count
            guard bytes <= archiveLimit else { throw ImportError.tooLarge }
            try output.write(contentsOf: chunk)
        }
        try output.synchronize()

        let archive = try Archive(url: temporary, accessMode: .read)
        let pages = try entries(in: archive)
        for page in pages {
            try Task.checkCancellation()
            let image = try readImage(page, from: archive)
            guard CGImageSourceGetStatus(image) == .statusComplete else { throw ImportError.invalid }
        }
        try Task.checkCancellation()
        try FileManager.default.moveItem(at: temporary, to: destination)
        return OfflineBook(id: id, title: String(source.deletingPathExtension().lastPathComponent.prefix(120)), pageCount: pages.count)
    }

    static func image(for book: OfflineBook, page: Int) throws -> UIImage {
        let archive = try Archive(url: bookURL(book), accessMode: .read)
        let pages = try entries(in: archive)
        guard pages.indices.contains(page) else { throw ImportError.invalid }
        return try thumbnail(readImage(pages[page], from: archive))
    }

    static func restoreDownloads(excluding knownIDs: Set<UUID>) -> [OfflineBook] {
        guard let folder = try? storage(),
              let files = try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil) else { return [] }
        return files.compactMap { directory -> OfflineBook? in
            guard directory.pathExtension == "book",
                  let id = UUID(uuidString: directory.deletingPathExtension().lastPathComponent),
                  !knownIDs.contains(id),
                  let data = try? Data(contentsOf: directory.appendingPathComponent("info.json")),
                  let book = try? JSONDecoder().decode(OfflineBook.self, from: data),
                  book.id == id, FileManager.default.fileExists(atPath: archiveURL(in: directory).path) else { return nil }
            return book
        }.sorted { a, b in a.title.localizedStandardCompare(b.title) == .orderedAscending }
    }

    static func onlineImage(_ url: URL, chapter: DownloadChapter) async throws -> UIImage {
        let data = try await remotePage(url, chapter: chapter, limit: pageLimit, session: readerSession)
        return try thumbnail(remoteImage(data).source)
    }

    private static func thumbnail(_ image: CGImageSource) throws -> UIImage {
        let options = [
            kCGImageSourceCreateThumbnailFromImageAlways as String: true,
            kCGImageSourceCreateThumbnailWithTransform as String: true,
            kCGImageSourceThumbnailMaxPixelSize as String: 3072
        ] as CFDictionary
        guard let thumbnail = CGImageSourceCreateThumbnailAtIndex(image, 0, options) else { throw ImportError.invalid }
        return UIImage(cgImage: thumbnail)
    }

    static func download(_ chapter: DownloadChapter, pages: [URL], finalEntry: Bool,
                         progress: @MainActor @Sendable (Int) -> Void) async throws -> OfflineBook {
        guard (1...1000).contains(pages.count), pages.allSatisfy(chapter.allowsImage) else {
            throw ChapterDownloadError.pagesUnavailable
        }
        let id = UUID()
        let folder = try storage()
        let staging = folder.appendingPathComponent(id.uuidString + ".partial", isDirectory: true)
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: staging) }
        let file = staging.appendingPathComponent("book.cbz")
        let archive = try Archive(url: file, accessMode: .create)
        let session = pageSession()
        defer { session.invalidateAndCancel() }
        var downloaded = 0
        try await withThrowingTaskGroup(of: (Int, Data).self) { group in
            var pending = pages.enumerated().makeIterator()
            var completed = 0
            for _ in 0..<min(3, pages.count) {
                try Task.checkCancellation()
                guard let (index, url) = pending.next() else { break }
                group.addTask { (index, try await remotePage(url, chapter: chapter, limit: pageLimit, session: session)) }
            }
            while let (index, data) = try await group.next() {
                try Task.checkCancellation()
                guard data.count <= archiveLimit - downloaded else { throw ImportError.tooLarge }
                downloaded += data.count
                if let (nextIndex, url) = pending.next() {
                    let limit = min(pageLimit, archiveLimit - downloaded)
                    guard limit > 0 else { throw ImportError.tooLarge }
                    group.addTask { (nextIndex, try await remotePage(url, chapter: chapter, limit: limit, session: session)) }
                }
                let ext = try remoteImage(data).fileExtension
                let name = String(format: "%04d", index + 1) + "." + ext
                try archive.addEntry(with: name, type: .file, uncompressedSize: Int64(data.count)) { offset, count in
                    try Task.checkCancellation()
                    let start = Int(offset)
                    guard start >= 0, start <= data.count, count >= 0, count <= data.count - start else {
                        throw ChapterDownloadError.invalidPage
                    }
                    return data.subdata(in: start..<(start + count))
                }
                let attributes = try FileManager.default.attributesOfItem(atPath: file.path)
                guard let size = attributes[.size] as? NSNumber,
                      size.int64Value <= Int64(archiveLimit) else { throw ImportError.tooLarge }
                let previous = completed * 99 / pages.count
                completed += 1
                if completed * 99 / pages.count > previous {
                    await progress(completed * 99 / pages.count)
                }
            }
        }
        try Task.checkCancellation()
        let verified = try Archive(url: file, accessMode: .read)
        guard try entries(in: verified).count == pages.count else { throw ImportError.invalid }
        let book = OfflineBook(id: id, title: chapter.title, pageCount: pages.count,
                               source: chapter.address, seriesID: chapter.seriesID, finalEntry: finalEntry)
        try JSONEncoder().encode(book).write(to: staging.appendingPathComponent("info.json"), options: .atomic)
        try Task.checkCancellation()
        try FileManager.default.moveItem(at: staging, to: downloadDirectory(id, in: folder))
        return book
    }

    private static func pageSession() -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.httpShouldSetCookies = false
        config.urlCredentialStorage = nil
        config.timeoutIntervalForRequest = 20
        config.timeoutIntervalForResource = 60
        return URLSession(configuration: config)
    }

    private static func remotePage(_ url: URL, chapter: DownloadChapter, limit: Int,
                                   session: URLSession) async throws -> Data {
        guard chapter.allowsImage(url) else { throw ChapterDownloadError.pagesUnavailable }
        var request = URLRequest(url: url)
        request.setValue("image/jpeg,image/png,image/webp,application/octet-stream", forHTTPHeaderField: "Accept")
        request.setValue(chapter.address.absoluteString, forHTTPHeaderField: "Referer")
        let delegate = PageTransferLimit(bytes: limit)
        let (file, response): (URL, URLResponse)
        do {
            (file, response) = try await session.download(for: request, delegate: delegate)
        } catch {
            if delegate.exceededLimit { throw ImportError.tooLarge }
            throw error
        }
        defer { try? FileManager.default.removeItem(at: file) }
        guard let http = response as? HTTPURLResponse, http.statusCode == 200,
              let finalURL = http.url, chapter.allowsImage(finalURL) else { throw ChapterDownloadError.providerResponse }
        let size = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard size > 0 else { throw ChapterDownloadError.invalidPage }
        guard size <= limit else { throw ImportError.tooLarge }
        try Task.checkCancellation()
        let data = try Data(contentsOf: file)
        guard data.count == size else { throw ChapterDownloadError.invalidPage }
        return data
    }

    private static func remoteImage(_ data: Data) throws -> (source: CGImageSource, fileExtension: String) {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let type = CGImageSourceGetType(source),
              let ext = ["jpg", "png", "webp"].first(where: { imageTypes[$0] == (type as String) }),
              CGImageSourceGetStatus(source) == .statusComplete else { throw ChapterDownloadError.invalidPage }
        try validateDimensions(source)
        return (source, ext)
    }

    static func remove(_ book: OfflineBook) throws {
        let directory = try downloadDirectory(book.id, in: storage())
        if FileManager.default.fileExists(atPath: directory.path) {
            try FileManager.default.removeItem(at: directory)
            return
        }
        let url = try bookURL(book)
        if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
    }

    private static func storage() throws -> URL {
        let root = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                               appropriateFor: nil, create: true)
        var directory = root.appendingPathComponent("OfflineComics", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try directory.setResourceValues(values)
        return directory
    }

    private static func bookURL(_ book: OfflineBook) throws -> URL {
        let folder = try storage()
        let archived = archiveURL(in: downloadDirectory(book.id, in: folder))
        if FileManager.default.fileExists(atPath: archived.path) { return archived }
        return folder.appendingPathComponent(book.id.uuidString + ".cbz")
    }

    private static func downloadDirectory(_ id: UUID, in folder: URL) -> URL {
        folder.appendingPathComponent(id.uuidString + ".book", isDirectory: true)
    }

    private static func archiveURL(in directory: URL) -> URL {
        directory.appendingPathComponent("book.cbz")
    }

    private static func entries(in archive: Archive) throws -> [Entry] {
        var pages: [Entry] = []
        var paths = Set<String>()
        var expanded: UInt64 = 0
        for (index, item) in archive.enumerated() {
            guard index < 2000 else { throw ImportError.tooLarge }
            let path = item.type == .directory && item.path.hasSuffix("/") ? String(item.path.dropLast()) : item.path
            let components = path.split(separator: "/", omittingEmptySubsequences: false)
            guard !path.isEmpty, path.utf8.count <= 1024, item.type != .symlink,
                  !path.contains("\\"), !path.contains(":"),
                  path.unicodeScalars.allSatisfy({ !CharacterSet.controlCharacters.contains($0) && $0.properties.generalCategory != .format }),
                  components.allSatisfy({ !$0.isEmpty && $0 != "." && $0 != ".." &&
                      !$0.hasSuffix(".") && $0.last?.isWhitespace == false }),
                  paths.insert(path.precomposedStringWithCanonicalMapping.lowercased()).inserted else {
                throw ImportError.invalid
            }
            let ext = (path as NSString).pathExtension.lowercased()
            if item.type == .file, activeTypes.contains(ext) {
                throw ImportError.invalid
            }
            guard item.type == .file, imageTypes[ext] != nil, components.first != "__MACOSX",
                  components.last?.hasPrefix(".") == false else { continue }
            guard pages.count < 1000, (1...UInt64(pageLimit)).contains(item.uncompressedSize),
                  item.uncompressedSize <= expandedLimit - expanded else { throw ImportError.tooLarge }
            expanded += item.uncompressedSize
            pages.append(item)
        }
        guard !pages.isEmpty else { throw ImportError.invalid }
        return pages.sorted { $0.path.localizedStandardCompare($1.path) == .orderedAscending }
    }

    private static func readImage(_ page: Entry, from archive: Archive) throws -> CGImageSource {
        var data = Data()
        let checksum = try archive.extract(page) { chunk in
            try Task.checkCancellation()
            guard chunk.count <= pageLimit - data.count else { throw ImportError.tooLarge }
            data.append(chunk)
        }
        let ext = (page.path as NSString).pathExtension.lowercased()
        guard UInt64(data.count) == page.uncompressedSize, checksum == page.checksum,
              let source = CGImageSourceCreateWithData(data as CFData, nil),
              let type = CGImageSourceGetType(source), imageTypes[ext] == (type as String) else { throw ImportError.invalid }
        try validateDimensions(source)
        return source
    }

    private static func validateDimensions(_ source: CGImageSource) throws {
        guard let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [String: Any],
              let width = properties[kCGImagePropertyPixelWidth as String] as? Int,
              let height = properties[kCGImagePropertyPixelHeight as String] as? Int else { throw ImportError.invalid }
        guard (1...20_000).contains(width), (1...60_000).contains(height),
              width * height <= 128 * 1024 * 1024 else { throw ImportError.tooLarge }
    }
}

private final class PageTransferLimit: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    let limit: Int64
    private let lock = NSLock()
    private var exceeded = false

    var exceededLimit: Bool {
        lock.lock()
        defer { lock.unlock() }
        return exceeded
    }

    init(bytes: Int) { limit = Int64(bytes) }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didFinishDownloadingTo location: URL) {}

    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        if totalBytesWritten > limit || totalBytesExpectedToWrite > limit {
            lock.lock()
            exceeded = true
            lock.unlock()
            downloadTask.cancel()
        }
    }
}
