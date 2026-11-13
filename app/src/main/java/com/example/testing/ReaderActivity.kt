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
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream

class ReaderActivity : AppCompatActivity() {

    companion object {
        private const val READER_SETTINGS = "reader_settings"
        private const val BLOCK_ADS = "block_ads"
        private const val RESTORE_RETRY_MS = 250L
        private const val MAX_ANCHOR_ATTEMPTS = 48
        private const val MAX_OFFSET_ATTEMPTS = 16
        private const val SAVE_DELAY_MS = 350L

        fun open(
            context: Context, series: MangaItem, chapter: String? = null, browse: Boolean = false,
            downloadTitle: String? = null, lastIssue: Boolean = false
        ) {
            context.startActivity(Intent(context, ReaderActivity::class.java).apply {
                putExtra("url", chapter ?: series.url)
                putExtra("title", series.title)
                putExtra("series_id", series.id)
                putExtra("explicit_chapter", chapter != null)
                putExtra("browse_issues", browse)
                putExtra("download_title", downloadTitle)
                putExtra("last_issue", lastIssue)
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
    private var markedReading = false
    private var automaticDownloadStarted = false
    private var preparingDownload: AlertDialog? = null
    private var restoreProgress: ReadingHistoryManager.Progress? = null
    private var restoreAttempts = 0
    private var offsetAttempts = 0
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
            showBlockedNavigation(url)
            finish()
            return
        }
        provider = initialProvider
        seriesId = intent.getStringExtra("series_id")
        val entry = HomeFragment.catalogue.firstOrNull { it.id == seriesId }
        restoreProgress = seriesId?.let { ReadingHistoryManager.getProgress(this, it) }
            ?.takeIf { saved ->
                !intent.getBooleanExtra("browse_issues", false) && providerFor(saved.url) == provider &&
                    (entry?.id != "bleachtybw" || ProviderChapters.chapterUrl(entry, saved.url) != null) &&
                    (!intent.getBooleanExtra("explicit_chapter", false) ||
                        (entry != null && ProviderChapters.chapterUrl(entry, saved.url)?.toString() == url))
            }
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
        webView.settings.setSupportZoom(true)
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false
        // SECURITY: never expose the local file system / content providers to web content.
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) webView.settings.safeBrowsingEnabled = true
        webView.settings.setSupportMultipleWindows(true)
        webView.settings.javaScriptCanOpenWindowsAutomatically = false
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)

        // ── WebViewClient: keep navigation inside the WebView ──────────────
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.isForMainFrame) {
                    if (providerFor(request.url.toString()) == initialProvider) return null
                    return WebResourceResponse(
                        "text/plain", "UTF-8", 403, "Blocked reader link", emptyMap(),
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                if (!blockAds) return null
                val url = request.url
                if (url.scheme != "https" && url.scheme != "http") return null
                val blocked = if (initialProvider == "mangakatana.com") {
                    ReaderAdHosts.blocksMangaKatanaResource(url.host, url.path, request.requestHeaders)
                } else {
                    ReaderAdHosts.blocksComicResource(url.host, url.path, request.requestHeaders)
                }
                if (!blocked) return null
                return WebResourceResponse(
                    "text/plain", "UTF-8", 204, "No Content", emptyMap(),
                    ByteArrayInputStream(ByteArray(0))
                )
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) {
                    if (!blockAds) return false
                    val url = request.url
                    if (url.scheme != "http" && url.scheme != "https") return url.scheme != "about"
                    val headers = mapOf("Sec-Fetch-Dest" to "iframe")
                    return if (initialProvider == "mangakatana.com") {
                        ReaderAdHosts.blocksMangaKatanaResource(url.host, url.path, headers)
                    } else {
                        ReaderAdHosts.blocksComicResource(url.host, url.path, headers)
                    }
                }
                if (providerFor(request.url.toString()) == provider) {
                    saveProgress()
                    return false
                }
                if (request.hasGesture()) showBlockedNavigation(request.url.toString())
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                preparingDownload?.dismiss()
                if (providerFor(url) != provider) {
                    view.stopLoading()
                    showError()
                    showBlockedNavigation(url)
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
                if (entry != null && initialProvider == "readcomicsonline.ru" && !failed && loadedUrl == url) {
                    val path = Uri.parse(url).pathSegments
                    if (path == Uri.parse(entry.url).pathSegments) {
                        runPageScript("comic-issues", url) { result ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                ComicWebData.rememberIssues(applicationContext, entry, result)
                            }
                        }
                    }
                    val label = intent.getStringExtra("download_title")
                    if (!automaticDownloadStarted && label != null &&
                        ProviderChapters.chapterUrl(entry, url)?.toString() == intent.getStringExtra("url")) {
                        automaticDownloadStarted = true
                        collectComicDownload(entry, label, intent.getBooleanExtra("last_issue", false))
                    }
                }
            }

            private fun rememberLoadedPage(view: WebView, url: String) {
                if (failed || url != currentUrl || providerFor(url) != provider) return
                if (loadedUrl == url) return
                loadedUrl = url
                title?.let { ReadingHistoryManager.addToHistory(this@ReaderActivity, it, seriesId) }
                if (!markedReading && entry != null && ProviderChapters.chapterUrl(entry, url) != null) {
                    markedReading = true
                    SavedLists.markReading(entry.id)
                }
                val saved = restoreProgress
                applyAdFilter()
                if (saved == null || saved.url != url || (saved.scrollY == 0 && saved.anchor == null)) {
                    cancelRestore()
                    saveProgress()
                    return
                }

                restoreAttempts = 0
                offsetAttempts = 0
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
            val zoomIn = popup.menu.add(R.string.pv_reader_zoom_in)
            val zoomOut = popup.menu.add(R.string.pv_reader_zoom_out)
            val download = if (entry != null && initialProvider == "readcomicsonline.ru") {
                popup.menu.add(if (FirebaseAuth.getInstance().currentUser == null) {
                    R.string.pv_download_sign_in
                } else {
                    R.string.pv_download_issue
                })
            } else null
            val blocker = popup.menu.add(R.string.pv_ink_block_ads).apply {
                isCheckable = true
                isChecked = blockAds
            }
            popup.setOnMenuItemClickListener { item ->
                when (item) {
                    browser -> openCurrentPageInBrowser()
                    zoomIn -> webView.zoomIn()
                    zoomOut -> webView.zoomOut()
                    download -> if (entry != null) collectComicDownload(entry)
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

    private fun collectComicDownload(series: MangaItem, label: String? = null, lastIssue: Boolean = false) {
        val owner = FirebaseAuth.getInstance().currentUser?.uid
        if (owner == null) {
            Toast.makeText(this, R.string.pv_download_sign_in, Toast.LENGTH_SHORT).show()
            return
        }
        val url = loadedUrl
        val canonical = ProviderChapters.chapterUrl(series, url)
        if (url == null || canonical == null || failed) {
            Toast.makeText(this, R.string.pv_open_issue_to_download, Toast.LENGTH_SHORT).show()
            return
        }
        if (preparingDownload != null) return
        val title = label ?: getString(R.string.pv_issue_number, canonical.pathSegments.last().removePrefix("issue-"))
        val chapter = ProviderChapters.Chapter(title, canonical.toString())
        val book = OfflineLibrary(this).chapterBook(series, chapter.url)
        if (book.isFile) {
            Toast.makeText(this, R.string.pv_download_complete, Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = AlertDialog.Builder(this)
            .setMessage(R.string.pv_download_preparing)
            .setNegativeButton(R.string.pv_download_cancel) { _, _ ->
                Toast.makeText(this, R.string.pv_download_cancelled, Toast.LENGTH_SHORT).show()
            }
            .show()
        preparingDownload = dialog
        dialog.setOnDismissListener { if (preparingDownload === dialog) preparingDownload = null }
        collectComicPages(series, chapter, url, book, lastIssue, dialog, owner, attempts = 12)
    }

    private fun collectComicPages(
        series: MangaItem, chapter: ProviderChapters.Chapter, url: String,
        book: java.io.File, lastIssue: Boolean, dialog: AlertDialog, owner: String, attempts: Int
    ) {
        if (isFinishing || isDestroyed || loadedUrl != url || preparingDownload !== dialog || !dialog.isShowing) return
        if (FirebaseAuth.getInstance().currentUser?.uid != owner) {
            dialog.dismiss()
            Toast.makeText(this, R.string.pv_download_sign_in, Toast.LENGTH_SHORT).show()
            return
        }
        runPageScript("comic-pages", url) { result ->
            lifecycleScope.launch {
                if (preparingDownload !== dialog || !dialog.isShowing) return@launch
                val stored = withContext(Dispatchers.IO) { ComicWebData.rememberPages(applicationContext, book, result) }
                if (isFinishing || loadedUrl != url || preparingDownload !== dialog || !dialog.isShowing) return@launch
                when {
                    stored -> {
                        dialog.dismiss()
                        val work = ChapterDownloadWorker.enqueue(this@ReaderActivity, series, chapter, lastIssue, owner)
                        if (work == null) {
                            Toast.makeText(this@ReaderActivity, R.string.pv_download_sign_in, Toast.LENGTH_SHORT).show()
                        } else {
                            showChapterDownloadProgress(this@ReaderActivity, work, book)
                        }
                    }
                    attempts > 1 -> webView.postDelayed({
                        collectComicPages(series, chapter, url, book, lastIssue, dialog, owner, attempts - 1)
                    }, 500)
                    else -> {
                        dialog.dismiss()
                        Toast.makeText(this@ReaderActivity, R.string.pv_issue_pages_not_ready, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun openCurrentPageInBrowser() {
        val url = currentUrl
        val link = ReaderLinks.inspect(url) as? ReaderLinks.Approved
        if (link?.provider != provider) {
            showBlockedNavigation(url)
            return
        }
        val uri = Uri.parse(url)
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.pv_reader_browser_confirm, link.host))
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
        if (anchor != null && restoreAttempts < MAX_ANCHOR_ATTEMPTS) {
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
            if (anchor == null) {
                cancelRestore()
            } else {
                Toast.makeText(this, R.string.pv_resume_approximate, Toast.LENGTH_LONG).show()
            }
        } else if (++offsetAttempts < MAX_OFFSET_ATTEMPTS) {
            webView.postDelayed(restoreScroll, RESTORE_RETRY_MS)
        } else {
            Toast.makeText(this, R.string.pv_resume_unavailable, Toast.LENGTH_LONG).show()
        }
        // Leave the saved bookmark intact after a fallback until the reader scrolls manually.
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

    private fun showBlockedNavigation(url: String?) {
        val message = if (ReaderLinks.inspect(url) == ReaderLinks.Blocked.UNSAFE_FORMAT) {
            R.string.pv_reader_unsafe_url
        } else {
            R.string.pv_reader_unsupported_provider
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun providerFor(url: String?): String? =
        (ReaderLinks.inspect(url) as? ReaderLinks.Approved)?.provider

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
        preparingDownload?.dismiss()
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
