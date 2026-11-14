package com.example.testing

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipFile

class OfflineActivity : AppCompatActivity() {
    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, OfflineActivity::class.java))
        }
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val library by lazy { OfflineLibrary(applicationContext) }
    private var opened: File? = null
    private var page = 0
    private var request = 0
    private var busy = false

    private lateinit var shelf: View
    private lateinit var reader: View
    private lateinit var image: PageZoomView
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var counter: TextView
    private lateinit var previous: Button
    private lateinit var next: Button
    private lateinit var importButton: Button

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || busy) return@registerForActivityResult
        busy = true
        importButton.isEnabled = false
        status.setText(R.string.pv_offline_importing)
        worker.execute {
            val result = runCatching { library.importBook(uri) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                busy = false
                importButton.isEnabled = true
                refreshShelf()
                result.onSuccess(::openBook).onFailure { error ->
                    val message = if (error is OfflineArchive.Rejected || error is java.util.zip.ZipException ||
                        error is IllegalArgumentException) {
                        R.string.pv_offline_import_rejected
                    } else {
                        R.string.pv_offline_import_failed
                    }
                    status.setText(message)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_offline)
        shelf = findViewById(R.id.offlineShelf)
        reader = findViewById(R.id.offlineReader)
        list = findViewById(R.id.offlineBooks)
        status = findViewById(R.id.offlineStatus)
        image = findViewById(R.id.offlinePageImage)
        counter = findViewById(R.id.offlinePageNumber)
        previous = findViewById(R.id.offlinePrevious)
        next = findViewById(R.id.offlineNext)
        importButton = findViewById(R.id.offlineImport)
        importButton.setOnClickListener {
            picker.launch(arrayOf("*/*"))
        }
        findViewById<View>(R.id.offlineClose).setOnClickListener { goBack() }
        previous.setOnClickListener { renderPage(page - 1) }
        next.setOnClickListener { renderPage(page + 1) }
        onBackPressedDispatcher.addCallback(this) { goBack() }

        refreshShelf()
        val previousBook = savedInstanceState?.getString("offline_book")
        library.books().firstOrNull { it.name == previousBook }?.let(::openBook)
    }

    private fun refreshShelf() {
        val books = library.books()
        list.removeAllViews()
        status.setText(if (books.isEmpty()) R.string.pv_offline_empty else R.string.pv_offline_manage)
        for (book in books) {
            val row = Button(this).apply {
                text = book.nameWithoutExtension
                isAllCaps = false
                setOnClickListener {
                    if (!busy) {
                        SeriesSelectionSound.play(this@OfflineActivity)
                        val download = library.downloadedChapter(book)
                        if (download == null) openBook(book)
                        else NativeReaderActivity.open(this@OfflineActivity, download.series, download.chapter, download.last)
                    }
                }
                setOnLongClickListener {
                    if (!busy) confirmRemove(book)
                    true
                }
            }
            list.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun confirmRemove(book: File) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.pv_offline_remove, book.nameWithoutExtension))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.pv_offline_delete) { _, _ ->
                val removed = library.remove(book)
                refreshShelf()
                if (!removed) status.setText(R.string.pv_offline_remove_failed)
            }.show()
    }

    private fun openBook(book: File) {
        opened = book
        findViewById<TextView>(R.id.offlineTitle).text = book.nameWithoutExtension
        shelf.visibility = View.GONE
        reader.visibility = View.VISIBLE
        renderPage(library.pageFor(book))
    }

    private fun renderPage(position: Int) {
        val book = opened ?: return
        val token = ++request
        previous.isEnabled = false
        next.isEnabled = false
        image.setImageDrawable(null)
        counter.setText(R.string.pv_offline_loading)
        worker.execute {
            var count = 0
            var actual = 0
            val bitmap = runCatching {
                ZipFile(book).use { zip ->
                    val pages = OfflineArchive.pages(zip)
                    count = pages.size
                    actual = position.coerceIn(pages.indices)
                    library.decodePage(zip, pages[actual])
                }
            }.getOrNull()
            runOnUiThread {
                if (isDestroyed || isFinishing || token != request) {
                    bitmap?.recycle()
                    return@runOnUiThread
                }
                showPage(book, actual, count, bitmap)
            }
        }
    }

    private fun showPage(book: File, index: Int, count: Int, bitmap: Bitmap?) {
        page = index
        previous.isEnabled = index > 0
        next.isEnabled = index + 1 < count
        if (bitmap == null) {
            counter.setText(R.string.pv_offline_page_failed)
            return
        }
        image.setImageBitmap(bitmap)
        image.resetZoom()
        counter.text = getString(R.string.pv_offline_page_number, index + 1, count)
        library.remember(book, index)
    }

    private fun goBack() {
        if (opened == null) {
            finish()
            return
        }
        request++
        opened = null
        image.setImageDrawable(null)
        reader.visibility = View.GONE
        shelf.visibility = View.VISIBLE
        findViewById<TextView>(R.id.offlineTitle).setText(R.string.pv_offline_library)
        refreshShelf()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("offline_book", opened?.name)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        request++
        worker.shutdownNow()
        super.onDestroy()
    }
}
