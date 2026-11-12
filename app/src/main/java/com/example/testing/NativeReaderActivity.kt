package com.example.testing

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

class NativeReaderActivity : AppCompatActivity() {
    companion object {
        internal fun open(
            context: Context, series: MangaItem, chapter: ProviderChapters.Chapter,
            last: Boolean = false, fromSeries: Boolean = false, webFallback: Boolean = false
        ) {
            context.startActivity(Intent(context, NativeReaderActivity::class.java)
                .putExtra("series", series.id)
                .putExtra("chapter", chapter.url)
                .putExtra("label", chapter.title)
                .putExtra("last", last)
                .putExtra("from_series", fromSeries)
                .putExtra("web_fallback", webFallback))
        }
    }

    private enum class Mode { RTL, LTR, VERTICAL }
    private lateinit var series: MangaItem
    private lateinit var chapter: String
    private lateinit var library: OfflineLibrary
    private lateinit var recycler: RecyclerView
    private lateinit var layout: LinearLayoutManager
    private lateinit var seek: SeekBar
    private var snap: PagerSnapHelper? = null
    private lateinit var mode: Mode
    private var archive: File? = null
    private var pages = emptyList<String>()
    private var currentPage = 0
    private val readyPages = mutableSetOf<Int>()
    private var barsVisible = true
    private var hasRecordedHistory = false
    private var completed = false
    private var load: Job? = null
    private var preload: Job? = null
    private var preloadFrom = -1
    private val pageCache = object : LruCache<Int, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.allocationByteCount
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val entry = HomeFragment.catalogue.firstOrNull { it.id == intent.getStringExtra("series") }
        val address = entry?.let { ProviderChapters.chapterUrl(it, intent.getStringExtra("chapter")) }
        if (entry == null || address == null) {
            finish()
            return
        }
        series = entry
        chapter = address.toString()
        library = OfflineLibrary(this)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        setContentView(R.layout.activity_native_reader)
        recycler = findViewById(R.id.nativePages)
        seek = findViewById(R.id.nativeSeek)
        mode = Mode.entries.firstOrNull { it.name == modePreferences().getString(series.id, null) }
            ?: if (series.category == "Manga") Mode.RTL else Mode.LTR
        findViewById<TextView>(R.id.nativeTitle).text =
            getString(R.string.pv_native_title, series.title, intent.getStringExtra("label").orEmpty())
        findViewById<View>(R.id.nativeClose).setOnClickListener { finish() }
        findViewById<View>(R.id.nativeOptions).setOnClickListener(::showOptions)
        findViewById<View>(R.id.nativePrevious).setOnClickListener { goTo(currentPage - 1) }
        findViewById<View>(R.id.nativeNext).setOnClickListener { goTo(currentPage + 1) }
        findViewById<View>(R.id.nativeRetry).setOnClickListener { loadChapter(savedInstanceState?.getInt("page")) }
        findViewById<View>(R.id.nativeWeb).setOnClickListener { ReaderActivity.open(this, series, chapter) }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) goTo(value)
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(view: RecyclerView, state: Int) {
                if (state != RecyclerView.SCROLL_STATE_IDLE || pages.isEmpty()) return
                val visible = snap?.findSnapView(layout)?.let(layout::getPosition)
                    ?: layout.findFirstVisibleItemPosition()
                if (visible in pages.indices) showPosition(visible)
            }
        })
        loadChapter(savedInstanceState?.getInt("page"))
    }

    private fun loadChapter(initial: Int?) {
        load?.cancel()
        preload?.cancel()
        preloadFrom = -1
        pageCache.evictAll()
        findViewById<View>(R.id.nativeError).visibility = View.VISIBLE
        findViewById<TextView>(R.id.nativeMessage).setText(
            if (series.category == "Manga") R.string.pv_native_loading else R.string.pv_native_issue_loading)
        findViewById<View>(R.id.nativeRetry).visibility = View.GONE
        findViewById<View>(R.id.nativeWeb).visibility = View.GONE
        findViewById<View>(R.id.nativeBottom).visibility = View.GONE
        load = lifecycleScope.launch {
            try {
                val (book, sources) = runInterruptible(Dispatchers.IO) {
                    val file = library.chapterBook(series, chapter)
                    if (file.isFile) {
                        file to ZipFile(file).use { zip -> OfflineArchive.pages(zip).map { it.name } }
                    } else {
                        null to ProviderChapters.pages(series, chapter)
                    }
                }
                if (sources.isEmpty()) throw IOException("No chapter images")
                archive = book
                pages = sources
                val saved = ReadingHistoryManager.getProgress(this@NativeReaderActivity, series.id)
                    ?.takeIf { ProviderChapters.chapterUrl(series, it.url)?.toString() == chapter }
                val anchor = saved?.anchor?.key
                val anchored = if (book == null && anchor != null) {
                    sources.indexOfFirst { Uri.parse(it).path == anchor }.takeIf { it >= 0 }
                } else null
                currentPage = (initial ?: anchored ?: book?.let(library::rememberedPage) ?: saved?.page ?: 0)
                    .coerceIn(sources.indices)
                findViewById<View>(R.id.nativeError).visibility = View.GONE
                findViewById<View>(R.id.nativeBottom).visibility = View.VISIBLE
                seek.max = pages.lastIndex
                findViewById<ProgressBar>(R.id.nativeProgress).max = pages.size
                configureLayout()
                recycler.adapter = PagesAdapter()
                goTo(currentPage)
                setBars(false)
            } catch (_: IOException) {
                if (intent.getBooleanExtra("web_fallback", false) && !library.chapterBook(series, chapter).isFile) {
                    ReaderActivity.open(this@NativeReaderActivity, series, chapter)
                    finish()
                    return@launch
                }
                findViewById<TextView>(R.id.nativeMessage).setText(
                    if (series.category == "Manga") R.string.pv_native_chapter_unavailable
                    else R.string.pv_native_issue_unavailable)
                findViewById<View>(R.id.nativeRetry).visibility = View.VISIBLE
                findViewById<View>(R.id.nativeWeb).visibility = View.VISIBLE
            }
        }
    }

    private fun configureLayout() {
        snap?.attachToRecyclerView(null)
        val vertical = mode == Mode.VERTICAL
        layout = LinearLayoutManager(this, if (vertical) RecyclerView.VERTICAL else RecyclerView.HORIZONTAL, mode == Mode.RTL)
        recycler.layoutDirection = View.LAYOUT_DIRECTION_LTR
        recycler.layoutManager = layout
        snap = if (vertical) null else PagerSnapHelper().also { it.attachToRecyclerView(recycler) }
        findViewById<View>(R.id.nativeControls).layoutDirection =
            if (mode == Mode.RTL) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
    }

    private fun goTo(index: Int) {
        if (index !in pages.indices) return
        layout.scrollToPositionWithOffset(index, 0)
        showPosition(index)
    }

    private fun showPosition(index: Int) {
        currentPage = index
        seek.progress = index
        findViewById<ProgressBar>(R.id.nativeProgress).progress = index + 1
        findViewById<TextView>(R.id.nativeCounter).text = getString(R.string.pv_native_page_count, index + 1, pages.size)
        findViewById<View>(R.id.nativePrevious).isEnabled = index > 0
        findViewById<View>(R.id.nativeNext).isEnabled = index < pages.lastIndex
        rememberPosition()
    }

    private fun rememberPosition() {
        if (currentPage !in readyPages) return
        val book = archive
        if (!hasRecordedHistory) {
            ReadingHistoryManager.addToHistory(this, series.title, series.id)
            SavedLists.markReading(series.id)
            hasRecordedHistory = true
        }
        if (book != null) library.remember(book, currentPage)
        val key = if (book == null) Uri.parse(pages[currentPage]).path else null
        ReadingHistoryManager.saveProgress(this, series.id, ReadingHistoryManager.Progress(
            chapter, 0, key?.let { ReadingHistoryManager.PageAnchor(it, 0) }, currentPage, pages.size,
            intent.getBooleanExtra("last", false)
        ))
        if (!completed && currentPage == pages.lastIndex && intent.getBooleanExtra("last", false)) {
            SavedLists.finish(this, series.id)
            completed = true
        }
        preloadNextPages()
    }

    private fun preloadNextPages() {
        if (preloadFrom == currentPage || archive != null) return
        preloadFrom = currentPage
        preload?.cancel()
        val upcoming = pages.indices.intersect(currentPage + 1..currentPage + 2)
        preload = lifecycleScope.launch {
            for (index in upcoming) {
                val bitmap = loadPage(index) ?: continue
                pageCache.put(index, bitmap)
            }
        }
    }

    private suspend fun loadPage(index: Int): Bitmap? {
        pageCache.get(index)?.let { return it }
        val source = pages[index]
        val book = archive
        return try {
            runInterruptible(Dispatchers.IO) {
                if (book != null) {
                    ZipFile(book).use { zip -> zip.getEntry(source)?.let { library.decodePage(zip, it) } }
                } else {
                    val data = ProviderChapters.image(source, chapter)
                    val mime = data.mime.takeUnless { it.isEmpty() || it == "application/octet-stream" }
                    library.decodeImage(data.bytes, mime, 1536, 3072)
                }
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun showOptions(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val direction = popup.menu.add(R.string.pv_native_direction)
        val zoomIn = popup.menu.add(R.string.pv_reader_zoom_in)
        val zoomOut = popup.menu.add(R.string.pv_reader_zoom_out)
        val web = popup.menu.add(R.string.pv_chapter_web)
        val download = popup.menu.add(R.string.pv_chapter_download)
        val chapters = popup.menu.add(if (series.category == "Manga") R.string.pv_chapters_heading else R.string.pv_issues_heading)
        download.isEnabled = pages.isNotEmpty() && archive == null
        popup.setOnMenuItemClickListener { item ->
            when (item) {
                direction -> chooseDirection()
                zoomIn -> (recycler.findViewHolderForAdapterPosition(currentPage) as? PageHolder)?.zoomBy(1.5f)
                zoomOut -> (recycler.findViewHolderForAdapterPosition(currentPage) as? PageHolder)?.zoomBy(1f / 1.5f)
                web -> ReaderActivity.open(this, series, chapter)
                chapters -> {
                    if (!intent.getBooleanExtra("from_series", false)) SeriesActivity.open(this, series)
                    finish()
                }
                download -> showDownloadProgress()
            }
            true
        }
        popup.show()
    }

    private fun showDownloadProgress() {
        val work = ChapterDownloadWorker.enqueue(this, series,
            ProviderChapters.Chapter(intent.getStringExtra("label").orEmpty(), chapter),
            intent.getBooleanExtra("last", false))
        showChapterDownloadProgress(this, work, library.chapterBook(series, chapter))
    }

    private fun chooseDirection() {
        if (pages.isEmpty()) return
        val labels = arrayOf(getString(R.string.pv_native_rtl), getString(R.string.pv_native_ltr), getString(R.string.pv_native_vertical))
        AlertDialog.Builder(this).setTitle(R.string.pv_native_direction)
            .setSingleChoiceItems(labels, mode.ordinal) { dialog, choice ->
                mode = Mode.entries[choice]
                modePreferences().edit().putString(series.id, mode.name).apply()
                configureLayout()
                goTo(currentPage)
                dialog.dismiss()
            }.show()
    }

    private fun modePreferences() = getSharedPreferences("native_reader_modes", MODE_PRIVATE)

    private fun setBars(visible: Boolean) {
        barsVisible = visible
        val visibility = if (visible) View.VISIBLE else View.GONE
        findViewById<View>(R.id.nativeTop).visibility = visibility
        findViewById<View>(R.id.nativeControls).visibility = visibility
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (pages.isNotEmpty()) outState.putInt("page", currentPage)
        super.onSaveInstanceState(outState)
    }

    private inner class PagesAdapter : RecyclerView.Adapter<PageHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): PageHolder = PageHolder(FrameLayout(parent.context))
        override fun getItemCount(): Int = pages.size
        override fun onBindViewHolder(holder: PageHolder, index: Int) = holder.load(index)
        override fun onViewRecycled(holder: PageHolder) { holder.clear() }
    }

    private inner class PageHolder(val frame: FrameLayout) : RecyclerView.ViewHolder(frame) {
        private val image = PageZoomView(frame.context).apply { isClickable = true }
        private val retry = Button(frame.context).apply { visibility = View.GONE }
        private var job: Job? = null

        init {
            frame.layoutParams = RecyclerView.LayoutParams(-1, -1)
            frame.addView(image, FrameLayout.LayoutParams(-1, -1))
            frame.addView(retry, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            image.onPageTap = { x ->
                when {
                    x == null || mode == Mode.VERTICAL || x in image.width * 0.25f..image.width * 0.75f -> setBars(!barsVisible)
                    (x < image.width * 0.25f) == (mode == Mode.RTL) -> goTo(currentPage + 1)
                    else -> goTo(currentPage - 1)
                }
            }
        }

        fun clear() {
            job?.cancel()
            image.setImageDrawable(null)
            image.resetZoom()
            retry.visibility = View.GONE
        }

        fun zoomBy(factor: Float) = image.zoomBy(factor)

        fun load(index: Int) {
            clear()
            image.contentDescription = getString(R.string.pv_native_page_count, index + 1, pages.size)
            job = lifecycleScope.launch {
                val bitmap = loadPage(index)
                if (bitmap == null) {
                    retry.text = getString(R.string.pv_native_page_retry, index + 1)
                    retry.visibility = View.VISIBLE
                    retry.setOnClickListener { load(index) }
                } else {
                    pageCache.put(index, bitmap)
                    image.setImageBitmap(bitmap)
                    image.resetZoom()
                    readyPages.add(index)
                    if (currentPage == index) rememberPosition()
                }
            }
        }
    }
}
