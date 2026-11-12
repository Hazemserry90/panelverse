import SwiftUI
import WebKit

@MainActor
struct OnlineReaderScreen: View {
    let series: Series
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var downloads: ChapterDownloads
    @State private var start: ReadingPosition?
    @State private var ready = false
    @State private var error = false
    @State private var loading = true
    @State private var reload = 0
    @State private var zoom: CGFloat = 1
    @State private var downloadSource: ReaderDownloadSource?

    var body: some View {
        NavigationStack {
            ZStack {
                Ink.background.ignoresSafeArea()
                if ready {
                    ProviderWebView(series: series, initial: start, zoom: zoom, loading: $loading, failed: $error,
                                    onDownloadSource: { downloadSource = $0 }) {
                        library.remember(series, address: $0, scroll: $1)
                    }
                    .id(reload)
                    .opacity(error ? 0 : 1)
                }
                if error {
                    VStack(spacing: 16) {
                        Text("This page couldn’t load.").font(.headline)
                        Button("Retry") {
                            error = false
                            loading = true
                            start = library.position(series)
                            reload += 1
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    .padding()
                } else if loading {
                    ProgressView().padding(16).background(Ink.background).allowsHitTesting(false)
                }
            }
            .safeAreaInset(edge: .bottom, spacing: 0) { DownloadStatus() }
            .navigationTitle(series.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .primaryAction) {
                    if let downloadSource {
                        let saved = library.books.contains { $0.source == downloadSource.chapter.address }
                        Button {
                            downloads.start(downloadSource, library: library)
                        } label: {
                            Label(saved ? "Available offline" : (series.isManga ? "Download chapter" : "Download issue"),
                                  systemImage: saved ? "checkmark.circle" : "arrow.down.circle")
                        }
                        .disabled(saved || downloads.active != nil)
                    }
                }
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button {
                            zoom = min(zoom * 1.25, 3)
                        } label: {
                            Label("Zoom in", systemImage: "plus.magnifyingglass")
                        }
                        Button {
                            zoom = max(zoom / 1.25, 1)
                        } label: {
                            Label("Zoom out", systemImage: "minus.magnifyingglass")
                        }
                        Button("Reset zoom") { zoom = 1 }
                    } label: {
                        Label("Reader options", systemImage: "ellipsis.circle")
                    }
                }
            }
        }
        .onAppear {
            if !ready {
                start = library.position(series)
                ready = true
            }
        }
    }
}

@MainActor
private struct ProviderWebView: UIViewRepresentable {
    let series: Series
    let initial: ReadingPosition?
    let zoom: CGFloat
    @Binding var loading: Bool
    @Binding var failed: Bool
    var onDownloadSource: (ReaderDownloadSource?) -> Void
    var save: (URL, Double) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.ignoresViewportScaleLimits = true
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.navigationDelegate = context.coordinator
        view.scrollView.delegate = context.coordinator
        view.isOpaque = false
        view.backgroundColor = .black
        let target = initial.flatMap { series.contains($0.address) ? $0.address : nil } ?? series.address
        context.coordinator.open(view, at: target)
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {
        context.coordinator.parent = self
        if view.pageZoom != zoom { view.pageZoom = zoom }
    }

    static func dismantleUIView(_ view: WKWebView, coordinator: Coordinator) {
        coordinator.finish(view)
    }

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate, UIScrollViewDelegate {
        var parent: ProviderWebView
        private weak var view: WKWebView?
        private var page: URL?
        private var restoration: Task<Void, Never>?
        private var saveTask: Task<Void, Never>?
        private var restored = false
        private var loadingPage = true

        init(_ parent: ProviderWebView) { self.parent = parent }

        func open(_ view: WKWebView, at address: URL) {
            self.view = view
            let rules = """
            [
              {"trigger":{"url-filter":"^https?://([^/]+[.])?(doubleclick[.]net|googlesyndication[.]com|adskeeper[.]com|adsterra[.]com|popads[.]net)/"},"action":{"type":"block"}},
              {"trigger":{"url-filter":".*"},"action":{"type":"css-display-none","selector":"ins.adsbygoogle,[data-ad-slot],.ad-overlay,#ad-overlay,.ad-popup,#ad-popup"}}
            ]
            """
            guard let store = WKContentRuleListStore.default() else {
                view.load(URLRequest(url: address))
                return
            }
            store.compileContentRuleList(forIdentifier: "PanelVerseAds", encodedContentRuleList: rules) { [weak view, weak self] list, _ in
                Task { @MainActor in
                    guard let view, let self, self.view === view else { return }
                    if let list { view.configuration.userContentController.add(list) }
                    view.load(URLRequest(url: address))
                }
            }
        }

        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard let frame = navigationAction.targetFrame, let url = navigationAction.request.url else {
                decisionHandler(.cancel)
                return
            }
            if frame.isMainFrame {
                decisionHandler(parent.series.contains(url) ? .allow : .cancel)
            } else {
                decisionHandler(parent.series.allows(url) ? .allow : .cancel)
            }
        }

        func webView(_ webView: WKWebView, decidePolicyFor navigationResponse: WKNavigationResponse,
                     decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
            if navigationResponse.isForMainFrame,
               let response = navigationResponse.response as? HTTPURLResponse, response.statusCode >= 400 {
                fail()
                decisionHandler(.cancel)
                return
            }
            if navigationResponse.isForMainFrame,
               let address = navigationResponse.response.url, !parent.series.contains(address) {
                fail()
                decisionHandler(.cancel)
                return
            }
            decisionHandler(.allow)
        }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            saveIfReady()
            saveTask?.cancel()
            restoration?.cancel()
            restoration = nil
            loadingPage = true
            parent.onDownloadSource(nil)
            parent.loading = true
            parent.failed = false
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            parent.loading = false
            loadingPage = false
            page = webView.url
            if let url = page, let chapter = DownloadChapter(series: parent.series, url: url) {
                parent.onDownloadSource(ReaderDownloadSource(chapter: chapter, visibleURL: url, view: webView))
            }
            if let url = page, parent.series.allows(url),
               let file = Bundle.main.url(forResource: "reader-page", withExtension: "js"),
               let script = try? String(contentsOf: file, encoding: .utf8),
               let data = try? JSONEncoder().encode(url.absoluteString),
               let encoded = String(data: data, encoding: .utf8) {
                webView.evaluateJavaScript("(\(script))('ads-on',\(encoded),null)", completionHandler: nil)
            }
            if !restored, let initial = parent.initial, initial.address == page, initial.scroll > 0 {
                restored = true
                restoration = Task { [weak self, weak webView] in
                    for _ in 0..<30 {
                        guard !Task.isCancelled, let self, let webView else { return }
                        let scrollView = webView.scrollView
                        let maximum = max(0, scrollView.contentSize.height - scrollView.bounds.height)
                        scrollView.setContentOffset(CGPoint(x: 0, y: min(CGFloat(initial.scroll), maximum)), animated: false)
                        if maximum >= CGFloat(initial.scroll) { break }
                        try? await Task.sleep(nanoseconds: 250_000_000)
                    }
                    guard !Task.isCancelled else { return }
                    self?.restoration = nil
                }
            } else {
                restored = true
                saveIfReady()
            }
        }

        func scrollViewWillBeginDragging(_ scrollView: UIScrollView) {
            restoration?.cancel()
            restoration = nil
        }

        func scrollViewDidScroll(_ scrollView: UIScrollView) {
            guard !loadingPage, restoration == nil else { return }
            saveTask?.cancel()
            saveTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: 350_000_000)
                if !Task.isCancelled { self?.saveIfReady() }
            }
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            if (error as NSError).code != NSURLErrorCancelled { fail() }
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            if (error as NSError).code != NSURLErrorCancelled { fail() }
        }

        func finish(_ webView: WKWebView) {
            saveIfReady()
            restoration?.cancel()
            saveTask?.cancel()
            view = nil
            webView.navigationDelegate = nil
            webView.scrollView.delegate = nil
            webView.stopLoading()
        }

        private func saveIfReady() {
            guard !loadingPage, !parent.failed, restoration == nil, let page, let view,
                  parent.series.isChapter(page) else { return }
            parent.save(page, Double(view.scrollView.contentOffset.y))
        }

        private func fail() {
            loadingPage = false
            parent.onDownloadSource(nil)
            parent.loading = false
            parent.failed = true
        }
    }
}
