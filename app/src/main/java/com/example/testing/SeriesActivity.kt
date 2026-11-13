package com.example.testing

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

class SeriesActivity : AppCompatActivity() {
    companion object {
        fun open(context: Context, series: MangaItem) {
            context.startActivity(Intent(context, SeriesActivity::class.java).putExtra("series_id", series.id))
        }

        internal fun resume(context: Context, series: MangaItem) {
            val saved = ReadingHistoryManager.getProgress(context, series.id)
            val chapter = ProviderChapters.chapterUrl(series, saved?.url)
            if (chapter != null && saved?.page != null) {
                val label = chapter.pathSegments.last().let { segment ->
                    if (series.category == "Manga") segment.removePrefix("c")
                    else segment.removePrefix("issue-").removePrefix("issue_")
                }
                NativeReaderActivity.open(context, series, ProviderChapters.Chapter(
                    context.getString(if (series.category == "Manga") R.string.pv_chapter_number else R.string.pv_issue_number, label),
                    chapter.toString()
                ), saved.finalChapter)
            } else {
                ReaderActivity.open(context, series)
            }
        }
    }

    private lateinit var series: MangaItem
    private lateinit var readButton: TextView
    private lateinit var saveButton: TextView
    private lateinit var wantButton: TextView
    private lateinit var chaptersView: LinearLayout
    private lateinit var library: OfflineLibrary
    private var listing: ProviderChapters.Listing? = null
    private var shown = 0
    private var request: Job? = null
    private var downloads = emptyList<WorkInfo>()
    private val downloadRows = mutableMapOf<String, MaterialButton>()
    private val startedDownloads = mutableSetOf<String>()
    private val cancellingDownloads = mutableSetOf<UUID>()
    private val comics get() = series.category != "Manga"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val seriesId = intent.getStringExtra("series_id")
        val entry = HomeFragment.catalogue.firstOrNull { it.id == seriesId }
        if (entry == null) {
            finish()
            return
        }
        series = entry
        library = OfflineLibrary(this)
        setContentView(R.layout.activity_series)
        readButton = findViewById(R.id.seriesRead)
        saveButton = findViewById(R.id.seriesSave)
        wantButton = findViewById(R.id.seriesWant)
        chaptersView = findViewById(R.id.seriesChapters)
        findViewById<ImageView>(R.id.seriesCover).setImageResource(entry.coverResId)
        findViewById<TextView>(R.id.seriesTitle).text = entry.title
        findViewById<TextView>(R.id.seriesCategory).text = entry.category.uppercase(java.util.Locale.ROOT)
        findViewById<TextView>(R.id.seriesProvider).text =
            getString(R.string.pv_ink_provider, Uri.parse(entry.url).host.orEmpty())
        findViewById<View>(R.id.seriesClose).setOnClickListener { finish() }
        findViewById<View>(R.id.seriesChaptersRetry).setOnClickListener { loadChapters() }
        findViewById<View>(R.id.seriesChaptersMore).setOnClickListener { appendChapters() }
        findViewById<TextView>(R.id.seriesChaptersWeb).apply {
            if (comics) setText(R.string.pv_browse_download_issues)
            setOnClickListener { ReaderActivity.open(this@SeriesActivity, series, browse = comics) }
        }
        WorkManager.getInstance(this).getWorkInfosByTagLiveData(ChapterDownloadWorker.seriesTag(series)).observe(this) { jobs ->
            downloads = jobs.orEmpty()
            downloadRows.forEach { (name, button) -> renderDownload(name, button) }
        }
        findViewById<View>(R.id.seriesShare).setOnClickListener {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, entry.url)
            }
            startActivity(Intent.createChooser(share, getString(R.string.pv_ink_share)))
        }
        loadChapters()
    }

    override fun onResume() {
        super.onResume()
        if (!::series.isInitialized) return
        if (comics && listing == null) {
            ComicWebData.issues(this, series)?.let(::renderListing)
        }
        bindRead()
        downloadRows.forEach { (name, button) -> renderDownload(name, button) }
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            saveButton.isEnabled = false
            wantButton.isEnabled = true
            wantButton.setOnClickListener {
                ComicBubble.show(this, getString(R.string.pv_favourite_sign_in))
            }
            return
        }
        saveButton.isEnabled = false
        wantButton.isEnabled = false
        SavedLists.account(user.uid).get().addOnCompleteListener(this) { task ->
            if (FirebaseAuth.getInstance().currentUser?.uid != user.uid) return@addOnCompleteListener
            if (!task.isSuccessful) {
                saveButton.setText(R.string.pv_ink_save_unavailable)
                bindWant(user.uid, false, false)
                return@addOnCompleteListener
            }
            val saved = task.result?.child("favorites")?.child(series.id)?.value == true
            val wanted = task.result?.child("savedLists")?.child(series.id)?.value == SavedList.WANT.value
            bindSaveState(user.uid, saved, wanted)
        }
    }

    private fun bindSaveState(uid: String, saved: Boolean, wanted: Boolean) {
        bindWant(uid, saved, wanted)
        saveButton.setText(if (saved) R.string.pv_library_favorited else R.string.pv_library_favorite)
        saveButton.isEnabled = true
        saveButton.setOnClickListener {
            if (FirebaseAuth.getInstance().currentUser?.uid != uid) return@setOnClickListener
            saveButton.isEnabled = false
            wantButton.isEnabled = false
            val write = SavedLists.setSaved(uid, series.id, !saved)
            write.addOnCompleteListener(this) { result ->
                if (FirebaseAuth.getInstance().currentUser?.uid != uid) return@addOnCompleteListener
                if (!result.isSuccessful) {
                    Toast.makeText(this, R.string.pv_ink_save_unavailable, Toast.LENGTH_SHORT).show()
                }
                bindSaveState(uid, if (result.isSuccessful) !saved else saved, wanted)
            }
        }
    }

    private fun bindWant(uid: String, saved: Boolean, wanted: Boolean) {
        wantButton.setText(if (wanted) R.string.pv_library_remove_want else R.string.pv_saved_want)
        wantButton.isEnabled = true
        wantButton.setOnClickListener {
            if (FirebaseAuth.getInstance().currentUser?.uid != uid) return@setOnClickListener
            wantButton.isEnabled = false
            saveButton.isEnabled = false
            val write = if (wanted) SavedLists.removeReadingList(uid, series.id)
                else SavedLists.move(uid, series.id, SavedList.WANT)
            write.addOnCompleteListener(this) { result ->
                if (FirebaseAuth.getInstance().currentUser?.uid != uid) return@addOnCompleteListener
                if (result.isSuccessful) {
                    SavedLists.clearCompletion(this, uid, series.id)
                    ComicBubble.show(this, getString(if (wanted) R.string.pv_library_removed_want else R.string.pv_library_added_want))
                } else {
                    Toast.makeText(this, R.string.pv_saved_error, Toast.LENGTH_SHORT).show()
                }
                bindSaveState(uid, saved, if (result.isSuccessful) !wanted else wanted)
            }
        }
    }

    private fun bindRead() {
        val chapterList = listing?.chapters.orEmpty()
        val saved = ReadingHistoryManager.getProgress(this, series.id)
        val previous = ProviderChapters.chapterUrl(series, saved?.url)?.toString()
        val match = chapterList.firstOrNull { it.url == previous }
        val chapter = match ?: chapterList.firstOrNull()
        val canResume = previous != null
        readButton.isEnabled = chapter != null || canResume || comics
        readButton.setText(if (canResume) R.string.pv_ink_resume else R.string.pv_ink_start)
        readButton.setOnClickListener {
            when {
                match != null && saved?.page == null && saved?.anchor == null -> resume(this, series)
                match != null -> openChapter(match)
                canResume -> resume(this, series)
                chapter != null -> openChapter(chapter, webFallback = comics)
                comics -> ReaderActivity.open(this, series, browse = true)
            }
        }
    }

    private fun loadChapters() {
        request?.cancel()
        val status = findViewById<TextView>(R.id.seriesChaptersStatus)
        val retry = findViewById<View>(R.id.seriesChaptersRetry)
        retry.visibility = View.GONE
        status.setText(if (comics) R.string.pv_issue_loading else R.string.pv_chapter_loading)
        findViewById<TextView>(R.id.seriesChaptersMore).setText(if (comics) R.string.pv_issue_more else R.string.pv_chapter_more)
        bindRead()
        request = lifecycleScope.launch {
            val cached = if (comics) ComicWebData.issues(this@SeriesActivity, series) else null
            if (cached != null) renderListing(cached)
            val result = try {
                runInterruptible(Dispatchers.IO) { ProviderChapters.readList(series) }
            } catch (_: IOException) {
                null
            }
            val fetched = result?.takeIf { it.chapters.isNotEmpty() }
            val available = fetched ?: listing ?: if (comics) {
                try {
                    ComicIssueLookup.read(this@SeriesActivity, series)
                } catch (_: IOException) {
                    null
                }
            } else null
            if (available != listing || listing == null) renderListing(available ?: listing)
        }
    }

    private fun renderListing(available: ProviderChapters.Listing?) {
        listing = available
        shown = 0
        chaptersView.removeAllViews()
        downloadRows.clear()
        findViewById<View>(R.id.seriesChaptersRetry).visibility = if (available == null) View.VISIBLE else View.GONE
        val status = findViewById<TextView>(R.id.seriesChaptersStatus)
        if (available == null) {
            status.setText(if (comics) R.string.pv_issue_unavailable else R.string.pv_chapter_unavailable)
            findViewById<View>(R.id.seriesChaptersMore).visibility = View.GONE
        } else {
            status.setText(if (comics) R.string.pv_issues_heading else R.string.pv_chapters_heading)
            appendChapters()
        }
        bindRead()
    }

    private fun openChapter(chapter: ProviderChapters.Chapter, webFallback: Boolean = false) {
        NativeReaderActivity.open(this, series, chapter,
            last = isFinalChapter(chapter), fromSeries = true, webFallback = webFallback)
    }

    private fun isFinalChapter(chapter: ProviderChapters.Chapter): Boolean =
        listing?.let { it.complete && it.chapters.lastOrNull() == chapter } == true

    private fun appendChapters() {
        val items = listing?.chapters.orEmpty()
        val end = minOf(shown + 32, items.size)
        for (chapter in items.subList(shown, end)) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val label = MaterialButton(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = chapter.title
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_primary))
                setOnClickListener { openChapter(chapter, webFallback = comics) }
            }
            row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            val book = library.chapterBook(series, chapter.url)
            val name = ChapterDownloadWorker.chapterTag(book.name)
            val download = MaterialButton(this).apply {
                isAllCaps = false
                tag = book
                setOnClickListener {
                    val active = downloads.firstOrNull { name in it.tags && !it.state.isFinished }
                    if (active != null) {
                        cancellingDownloads.add(active.id)
                        WorkManager.getInstance(this@SeriesActivity).cancelWorkById(active.id)
                        renderDownload(name, this)
                    } else if (book.isFile) openChapter(chapter)
                    else if (FirebaseAuth.getInstance().currentUser == null) {
                        Toast.makeText(this@SeriesActivity, R.string.pv_download_sign_in, Toast.LENGTH_SHORT).show()
                    } else if (comics) ReaderActivity.open(this@SeriesActivity, series, chapter.url,
                        downloadTitle = chapter.title, lastIssue = isFinalChapter(chapter))
                    else {
                        val work = ChapterDownloadWorker.enqueue(this@SeriesActivity, series, chapter, isFinalChapter(chapter))
                        if (work == null) {
                            Toast.makeText(this@SeriesActivity, R.string.pv_download_sign_in, Toast.LENGTH_SHORT).show()
                        } else {
                            startedDownloads.add(work)
                            isEnabled = false
                            setText(R.string.pv_download_queued)
                        }
                    }
                }
            }
            downloadRows[name] = download
            renderDownload(name, download)
            row.addView(download, LinearLayout.LayoutParams(-2, -2))
            chaptersView.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        shown = end
        findViewById<View>(R.id.seriesChaptersMore).visibility = if (shown < items.size) View.VISIBLE else View.GONE
    }

    private fun renderDownload(name: String, button: MaterialButton) {
        val book = button.tag as java.io.File
        val jobs = downloads.filter { name in it.tags }
        val active = jobs.firstOrNull { !it.state.isFinished }
        val cancelling = active != null && active.id in cancellingDownloads
        button.isEnabled = !cancelling
        button.text = when {
            cancelling -> getString(R.string.pv_download_cancelling)
            active?.state == WorkInfo.State.RUNNING -> getString(R.string.pv_download_cancel_percent,
                active.progress.getInt(ChapterDownloadWorker.PERCENT, 0))
            active != null -> getString(R.string.pv_download_cancel)
            book.isFile -> getString(R.string.pv_chapter_offline)
            FirebaseAuth.getInstance().currentUser == null -> getString(R.string.pv_download_sign_in)
            else -> getString(R.string.pv_chapter_download)
        }
        val stopped = jobs.firstOrNull { it.state.isFinished && it.id in cancellingDownloads }
        if (stopped != null) {
            cancellingDownloads.remove(stopped.id)
            startedDownloads.remove(name)
            if (!book.isFile) Toast.makeText(this, R.string.pv_download_cancelled, Toast.LENGTH_SHORT).show()
        } else if (active == null && jobs.isNotEmpty() && startedDownloads.remove(name) && !book.isFile) {
            Toast.makeText(this, R.string.pv_download_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
