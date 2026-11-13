package com.example.testing

import android.content.Context
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

internal object ComicIssueLookup {
    suspend fun read(context: Context, series: MangaItem): ProviderChapters.Listing? {
        val root = ProviderChapters.seriesRoot(series)
        if (root.host !in setOf("readcomicsonline.ru", "www.readcomicsonline.ru")) return null
        val script = withContext(Dispatchers.IO) {
            context.assets.open("reader-page.js").bufferedReader().use { it.readText() }
        }
        val webView = WebView(context)
        try {
            val result = withTimeoutOrNull(9_000) {
                suspendCancellableCoroutine<String?> { continuation ->
                    fun finish(result: String?) {
                        if (continuation.isActive) continuation.resume(result)
                    }
                    fun isSeriesPage(url: Uri): Boolean =
                        (ReaderLinks.inspect(url.toString()) as? ReaderLinks.Approved)?.provider == "readcomicsonline.ru" &&
                            url.pathSegments == root.pathSegments

                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
                        javaScriptCanOpenWindowsAutomatically = false
                    }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val blocked = request.isForMainFrame && !isSeriesPage(request.url)
                            if (blocked) finish(null)
                            return blocked
                        }

                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val url = request.url
                            val blocked = if (request.isForMainFrame) !isSeriesPage(url)
                            else url.scheme != "https" || ReaderAdHosts.blocksComicResource(url.host, url.path, request.requestHeaders)
                            return if (blocked) WebResourceResponse("text/plain", "UTF-8",
                                ByteArrayInputStream(ByteArray(0))) else null
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) finish(null)
                        }

                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                            if (request.isForMainFrame) finish(null)
                        }

                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            finish(null)
                            return true
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            if (!continuation.isActive || view.url != url || !isSeriesPage(Uri.parse(url))) return
                            view.evaluateJavascript("($script)(\"comic-issues\",${JSONObject.quote(url)},null)") {
                                finish(it)
                            }
                        }
                    }
                    webView.loadUrl(root.toString())
                }
            }
            return result?.let { withContext(Dispatchers.IO) { ComicWebData.rememberIssues(context, series, it) } }
        } finally {
            webView.destroy()
        }
    }
}
