import SwiftUI
import UIKit
import UniformTypeIdentifiers

@MainActor
struct OfflineScreen: View {
    @EnvironmentObject private var library: LibraryStore
    @State private var picker = false
    @State private var importing = false
    @State private var error: String?

    var body: some View {
        Group {
            if library.books.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "books.vertical").font(.largeTitle)
                    Text("Download a chapter or issue from the reader, or import a CBZ from Files.")
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.secondary)
                    Button("Import CBZ") { picker = true }.buttonStyle(.borderedProminent)
                }
                .padding(16)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List(library.books) { book in
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
                .scrollContentBackground(.hidden)
            }
        }
        .background(Ink.background)
        .safeAreaInset(edge: .top, spacing: 0) { DownloadStatus() }
        .navigationTitle("Offline")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                if importing { ProgressView() }
                else if !library.books.isEmpty { Button("Import CBZ") { picker = true } }
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
    @EnvironmentObject private var library: LibraryStore
    @State private var page: Int?
    @State private var image: UIImage?
    @State private var failed = false
    @State private var retry = 0

    var body: some View {
        VStack(spacing: 8) {
            ZStack {
                Ink.background
                if let image {
                    ZoomablePage(image: image)
                        .id(page)
                        .accessibilityLabel("Page \((page ?? 0) + 1)")
                } else if failed {
                    Button("Page couldn’t load. Retry") { retry += 1 }
                } else {
                    ProgressView()
                }
            }
            HStack {
                Button("Previous") { if let page { self.page = page - 1 } }
                    .disabled((page ?? 0) <= 0)
                Spacer()
                Text("\((page ?? 0) + 1) / \(book.pageCount)").font(.subheadline.monospacedDigit())
                Spacer()
                Button("Next") { if let page { self.page = page + 1 } }
                    .disabled(page == nil || (page ?? 0) + 1 >= book.pageCount)
            }
            .buttonStyle(.bordered)
            .padding([.horizontal, .bottom], 12)
        }
        .background(Ink.background)
        .navigationTitle(book.title)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { if page == nil { page = library.page(book) } }
        .task(id: "\(page ?? -1):\(retry)") { await loadPage() }
    }

    private func loadPage() async {
        guard let page else { return }
        image = nil
        failed = false
        do {
            let task = Task.detached(priority: .userInitiated) { try ComicArchive.image(for: book, page: page) }
            let decoded = try await withTaskCancellationHandler(operation: { try await task.value },
                                                                onCancel: { task.cancel() })
            guard !Task.isCancelled, self.page == page else { return }
            image = decoded
            library.rememberPage(page, of: book)
        } catch {
            if !Task.isCancelled { failed = true }
        }
    }
}

private struct ZoomablePage: UIViewRepresentable {
    let image: UIImage

    func makeUIView(context: Context) -> ZoomScrollView { ZoomScrollView() }
    func updateUIView(_ view: ZoomScrollView, context: Context) { view.imageView.image = image }

    final class ZoomScrollView: UIScrollView, UIScrollViewDelegate {
        let imageView = UIImageView()
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
