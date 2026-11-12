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
        let image = try readImage(pages[page], from: archive)
        let options = [
            kCGImageSourceCreateThumbnailFromImageAlways as String: true,
            kCGImageSourceCreateThumbnailWithTransform as String: true,
            kCGImageSourceThumbnailMaxPixelSize as String: 3072
        ] as CFDictionary
        guard let thumbnail = CGImageSourceCreateThumbnailAtIndex(image, 0, options) else { throw ImportError.invalid }
        return UIImage(cgImage: thumbnail)
    }

    static func remove(_ book: OfflineBook) throws {
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
        try storage().appendingPathComponent(book.id.uuidString + ".cbz")
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
                  components.allSatisfy({ !$0.isEmpty && $0 != "." && $0 != ".." && !$0.hasSuffix(".") }),
                  paths.insert(path.precomposedStringWithCanonicalMapping.lowercased()).inserted else {
                throw ImportError.invalid
            }
            let ext = (path as NSString).pathExtension.lowercased()
            if ["html", "htm", "js", "svg", "exe", "apk", "bat", "cmd", "sh", "ps1"].contains(ext) {
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
              let type = CGImageSourceGetType(source), imageTypes[ext] == (type as String),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [String: Any],
              let width = properties[kCGImagePropertyPixelWidth as String] as? Int,
              let height = properties[kCGImagePropertyPixelHeight as String] as? Int else { throw ImportError.invalid }
        guard (1...20_000).contains(width), (1...60_000).contains(height),
              width * height <= 128 * 1024 * 1024 else { throw ImportError.tooLarge }
        return source
    }
}
