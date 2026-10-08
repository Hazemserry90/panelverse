package com.example.testing

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ReaderActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var webProgress: ProgressBar
    private lateinit var readerTitle: TextView
    private lateinit var backButton: ImageButton
    private lateinit var openBrowserButton: ImageButton

    private var currentUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)

        // Retrieve intent extras
        val url   = intent.getStringExtra("url")
        val title = intent.getStringExtra("title")

        // Validate URL early
        if (url.isNullOrBlank()) {
            Toast.makeText(this, "Invalid URL", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        currentUrl = url

        // SECURITY: only allow web (http/https) URLs into the WebView.
        val initialScheme = Uri.parse(url).scheme?.lowercase()
        if (initialScheme != "http" && initialScheme != "https") {
            Toast.makeText(this, "Unsupported link", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Bind views
        webView           = findViewById(R.id.readerWebView)
        webProgress       = findViewById(R.id.webProgress)
        readerTitle       = findViewById(R.id.readerTitle)
        backButton        = findViewById(R.id.backButton)
        openBrowserButton = findViewById(R.id.openBrowserButton)

        // Set title
        readerTitle.text = if (!title.isNullOrBlank()) title else "Reading..."

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

        // ── WebViewClient: keep navigation inside the WebView ──────────────
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // SECURITY: only http/https stay in the WebView; block every other scheme.
                val scheme = request.url.scheme?.lowercase()
                return if (scheme == "http" || scheme == "https") {
                    currentUrl = request.url.toString()
                    false
                } else {
                    true
                }
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                val scheme = Uri.parse(url).scheme?.lowercase()
                return if (scheme == "http" || scheme == "https") {
                    currentUrl = url
                    false
                } else {
                    true
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                currentUrl = url
                webProgress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                currentUrl = url
            }
        }

        // ── WebChromeClient: drive the progress bar ────────────────────────
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                webProgress.progress = newProgress
                webProgress.visibility = if (newProgress == 100) View.GONE else View.VISIBLE
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                super.onReceivedTitle(view, title)
                if (!title.isNullOrEmpty()) readerTitle.text = title
            }
        }

        // ── Top-bar buttons ────────────────────────────────────────────────
        backButton.setOnClickListener {
            if (webView.canGoBack()) webView.goBack() else finish()
        }

        openBrowserButton.setOnClickListener {
            val urlToOpen = webView.url?.takeIf { it.isNotBlank() } ?: currentUrl
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(urlToOpen)))
            } catch (e: Exception) {
                Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show()
            }
        }

        // ── Load the URL ───────────────────────────────────────────────────
        webView.loadUrl(currentUrl)
    }

    // ── Physical back button ───────────────────────────────────────────────
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
