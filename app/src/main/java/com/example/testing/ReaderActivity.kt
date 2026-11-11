package com.example.testing

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

class ReaderActivity : AppCompatActivity() {

    companion object {
        private const val READER_SETTINGS = "reader_settings"
        private const val BLOCK_ADS = "block_ads"
        private const val RESTORE_RETRY_MS = 250L
        private const val MAX_RESTORE_ATTEMPTS = 32
        private const val SAVE_DELAY_MS = 350L

        fun open(context: Context, series: MangaItem) {
            context.startActivity(Intent(context, ReaderActivity::class.java).apply {
                putExtra("url", series.url)
                putExtra("title", series.title)
                putExtra("series_id", series.id)
            })
        }
    }

    private lateinit var webView: WebView
    private lateinit var webProgress: ProgressBar
    private lateinit var readerTitle: TextView
    private lateinit var backButton: ImageButton
    private lateinit var readerOptionsButton: ImageButton

    private var currentUrl = ""
    private var provider = ""

    // Resource interception runs off the UI thread.
    @Volatile private var blockAds = true
    private var loadedUrl: String? = null
    private var failed = false
    private lateinit var errorPanel: View
    private var seriesId: String? = null
    private var restoreProgress: ReadingHistoryManager.Progress? = null
    private var restoreAttempts = 0
    private var lastSaved: ReadingHistoryManager.Progress? = null
    private var saveGeneration = 0L
    private val readerPageScript by lazy {
        assets.open("reader-page.js").bufferedReader().use { it.readText() }.trim()
    }
    private var restoreGeneration = 0L
    private val restoreScroll = Runnable { restoreScrollPosition() }
    private val saveScroll = Runnable { saveProgress() }

    @SuppressLint("ClickableViewAccessibility") // Observes gestures and leaves WebView touch handling intact.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false)
        }
        setContentView(R.layout.activity_reader)
        val settings = getSharedPreferences(READER_SETTINGS, MODE_PRIVATE)
        blockAds = settings.getBoolean(BLOCK_ADS, true)

        // Retrieve intent extras
        val url   = intent.getStringExtra("url")
        val title = intent.getStringExtra("title")

        val initialProvider = providerFor(url)
        if (url == null || initialProvider == null) {
            Toast.makeText(this, R.string.pv_reader_unsupported_provider, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        provider = initialProvider
        seriesId = intent.getStringExtra("series_id")
        restoreProgress = seriesId?.let { ReadingHistoryManager.getProgress(this, it) }
            ?.takeIf { providerFor(it.url) == provider }
        lastSaved = restoreProgress
        currentUrl = restoreProgress?.url ?: url

        // Bind views
        webView           = findViewById(R.id.readerWebView)
        webProgress       = findViewById(R.id.webProgress)
        errorPanel = findViewById(R.id.readerError)
        readerTitle       = findViewById(R.id.readerTitle)
        backButton        = findViewById(R.id.backButton)
        readerOptionsButton = findViewById(R.id.readerOptionsButton)

        webView.setOnScrollChangeListener { view, _, _, _, _ ->
            if (loadedUrl != null && restoreProgress == null) {
                view.removeCallbacks(saveScroll)
                view.postDelayed(saveScroll, SAVE_DELAY_MS)
            }
        }
        webView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_MOVE && loadedUrl != null) cancelRestore()
            false
        }

        // Set title
        readerTitle.text = if (!title.isNullOrBlank()) title else "Reading..."

        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            WebView.setWebContentsDebuggingEnabled(false)
        }

        // ── WebView settings ───────────────────────────────────────────────
        webView.settings.javaScriptEnabled  = true
        webView.settings.domStorageEnabled  = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort    = true
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false
        // SECURITY: never expose the local file system / content providers to web content.
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        webView.settings.setSupportMultipleWindows(true)
        webView.settings.javaScriptCanOpenWindowsAutomatically = false
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)

        // ── WebViewClient: keep navigation inside the WebView ──────────────
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!blockAds || request.isForMainFrame) return null
                val url = request.url
                if (url.scheme != "https" && url.scheme != "http") return null
                if (!ReaderAdHosts.blocks(url.host)) return null
                return WebResourceResponse(
                    "text/plain", "UTF-8", 204, "No Content", emptyMap(),
                    ByteArrayInputStream(ByteArray(0))
                )
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                if (providerFor(request.url.toString()) == provider) {
                    saveProgress()
                    return false
                }
                if (request.hasGesture()) showBlockedNavigation()
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (providerFor(url) != provider) {
                    view.stopLoading()
                    showError()
                    showBlockedNavigation()
                    return
                }
                view.removeCallbacks(restoreScroll)
                view.removeCallbacks(saveScroll)
                restoreGeneration++
                currentUrl = url
                loadedUrl = null
                failed = false
                errorPanel.visibility = View.GONE
                view.visibility = View.VISIBLE
                webProgress.visibility = View.VISIBLE
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && request.url.toString() == currentUrl) showError()
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && request.url.toString() == currentUrl) showError()
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                rememberLoadedPage(view, url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                rememberLoadedPage(view, url)
                if (!failed && loadedUrl == url) applyAdFilter()
            }

            private fun rememberLoadedPage(view: WebView, url: String) {
                if (failed || url != currentUrl || providerFor(url) != provider) return
                if (loadedUrl == url) return
                loadedUrl = url
                title?.let { ReadingHistoryManager.addToHistory(this@ReaderActivity, it) }
                val saved = restoreProgress
                applyAdFilter()
                if (saved == null || saved.url != url || (saved.scrollY == 0 && saved.anchor == null)) {
                    cancelRestore()
                    saveProgress()
                    return
                }

                restoreAttempts = 0
                val generation = restoreGeneration
                view.postVisualStateCallback(generation, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        if (requestId == restoreGeneration) restoreScrollPosition()
                    }
                })
            }
        }

        // ── WebChromeClient: drive the progress bar ────────────────────────
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                webProgress.progress = newProgress
                webProgress.visibility = if (newProgress == 100 || view.visibility != View.VISIBLE) View.GONE else View.VISIBLE
            }
        }

        // ── Top-bar buttons ────────────────────────────────────────────────
        // FIX: the X button closes the reader. WebView history navigation is still
        // available via the system back gesture/hardware key (OnBackPressedDispatcher).
        backButton.setOnClickListener {
            finish()
        }

        readerOptionsButton.setOnClickListener { anchor ->
            val popup = PopupMenu(this, anchor)
            val browser = popup.menu.add(R.string.pv_open_in_browser)
            val blocker = popup.menu.add(R.string.pv_ink_block_ads).apply {
                isCheckable = true
                isChecked = blockAds
            }
            popup.setOnMenuItemClickListener { item ->
                when (item) {
                    browser -> openCurrentPageInBrowser()
                    blocker -> {
                        blockAds = !blockAds
                        settings.edit().putBoolean(BLOCK_ADS, blockAds).apply()
                        applyAdFilter()
                        Toast.makeText(this, R.string.pv_ink_ad_setting_saved, Toast.LENGTH_SHORT).show()
                    }
                    else -> return@setOnMenuItemClickListener false
                }
                true
            }
            popup.show()
        }

        val toolbar = findViewById<View>(R.id.readerToolbar)
        val controls = findViewById<View>(R.id.readerShowToolbar)
        findViewById<View>(R.id.readerHideToolbar).setOnClickListener {
            toolbar.visibility = View.GONE
            controls.visibility = View.VISIBLE
        }
        controls.setOnClickListener {
            toolbar.visibility = View.VISIBLE
            controls.visibility = View.GONE
        }
        findViewById<View>(R.id.readerRetry).setOnClickListener { webView.loadUrl(currentUrl) }
        findViewById<View>(R.id.readerSeriesPage).setOnClickListener {
            cancelRestore()
            webView.loadUrl(url)
        }

        // ── Load the URL ───────────────────────────────────────────────────
        webView.loadUrl(currentUrl)

        // FIX 9: use the modern OnBackPressedDispatcher instead of the deprecated
        // Activity.onBackPressed() override.
        onBackPressedDispatcher.addCallback(this) {
            saveProgress()
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    private fun openCurrentPageInBrowser() {
        val url = currentUrl
        if (providerFor(url) != provider) return
        val uri = Uri.parse(url)
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.pv_reader_browser_confirm, uri.host))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.pv_open_in_browser) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(this, R.string.pv_reader_no_browser, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showError() {
        failed = true
        webView.removeCallbacks(restoreScroll)
        webView.removeCallbacks(saveScroll)
        restoreGeneration++
        loadedUrl = null
        webView.visibility = View.INVISIBLE
        webProgress.visibility = View.GONE
        errorPanel.visibility = View.VISIBLE
    }

    private fun restoreScrollPosition() {
        val target = restoreProgress ?: return
        if (loadedUrl != target.url || webView.url != target.url) return

        val anchor = target.anchor
        if (anchor != null && restoreAttempts < MAX_RESTORE_ATTEMPTS) {
            val anchorJson = JSONObject().put("key", anchor.key).put("offset", anchor.offset)
            val generation = restoreGeneration
            runPageScript("restore", target.url, anchorJson.toString()) { result ->
                if (generation != restoreGeneration || restoreProgress !== target) return@runPageScript
                if (result == "true") {
                    cancelRestore()
                    persistProgress(target.copy(scrollY = webView.scrollY))
                } else {
                    restoreAttempts++
                    webView.postDelayed(restoreScroll, RESTORE_RETRY_MS)
                }
            }
            return
        }

        // Keep the pixel offset as a fallback for older bookmarks and non-image readers.
        webView.scrollTo(0, target.scrollY)
        if (webView.scrollY >= target.scrollY - 1) {
            if (anchor == null) cancelRestore()
        } else if (++restoreAttempts < MAX_RESTORE_ATTEMPTS) {
            webView.postDelayed(restoreScroll, RESTORE_RETRY_MS)
        }
    }

    private fun applyAdFilter() {
        val url = webView.url ?: return
        if (providerFor(url) != provider) return
        runPageScript(if (blockAds) "ads-on" else "ads-off", url)
    }

    private fun runPageScript(
        operation: String,
        url: String,
        anchor: String = "null",
        result: ((String) -> Unit)? = null
    ) {
        val call = "($readerPageScript)(" + JSONObject.quote(operation) + "," + JSONObject.quote(url) + "," + anchor + ")"
        webView.evaluateJavascript(call) { value -> result?.invoke(value) }
    }

    private fun cancelRestore() {
        restoreProgress = null
        webView.removeCallbacks(restoreScroll)
    }

    private fun saveProgress() {
        val committedUrl = loadedUrl ?: return
        val url = webView.url?.takeIf { providerFor(it) == provider } ?: committedUrl
        if (restoreProgress?.url == url) return

        val position = webView.scrollY
        val previous = lastSaved
        if (previous?.url != url || previous.scrollY != position) {
            persistProgress(ReadingHistoryManager.Progress(url, position))
        }
        val request = ++saveGeneration
        val page = restoreGeneration
        runPageScript("capture", url) { value ->
            if (request != saveGeneration || page != restoreGeneration || webView.url != url ||
                webView.scrollY != position || restoreProgress != null) return@runPageScript
            val json = try { JSONObject(value) } catch (_: org.json.JSONException) { return@runPageScript }
            val key = json.optString("key").takeIf { it.startsWith("/") && it.length <= 1024 }
                ?: return@runPageScript
            val anchor = ReadingHistoryManager.PageAnchor(key, json.optInt("offset").coerceIn(0, 10000))
            persistProgress(ReadingHistoryManager.Progress(url, position, anchor))
        }
    }

    private fun persistProgress(progress: ReadingHistoryManager.Progress) {
        val id = seriesId ?: return
        if (lastSaved == progress) return
        ReadingHistoryManager.saveProgress(this, id, progress)
        lastSaved = progress
    }

    private fun showBlockedNavigation() {
        Toast.makeText(this, R.string.pv_reader_unsupported_provider, Toast.LENGTH_SHORT).show()
    }

    private fun providerFor(url: String?): String? {
        if (url.isNullOrBlank() || url.length > 4096) return null
        val parsed = try { URI(url) } catch (_: URISyntaxException) { return null }
        if (!parsed.scheme.equals("https", ignoreCase = true) || parsed.rawUserInfo != null ||
            (parsed.port != -1 && parsed.port != 443)) return null
        return when (parsed.host?.lowercase(Locale.ROOT)) {
            "mangakatana.com", "www.mangakatana.com" -> "mangakatana.com"
            "readcomicsonline.ru", "www.readcomicsonline.ru" -> "readcomicsonline.ru"
            else -> null
        }
    }

    override fun onPause() {
        super.onPause()
        if (::webView.isInitialized) {
            saveProgress()
            webView.onPause()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.removeCallbacks(restoreScroll)
            webView.removeCallbacks(saveScroll)
            restoreGeneration++
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }
}
