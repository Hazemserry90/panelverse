package com.example.testing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.security.MessageDigest
import org.json.JSONObject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

internal class OfflineLibrary(private val context: Context) {
    companion object {
        private const val MAX_ARCHIVE_BYTES = 250L * 1024 * 1024
        private const val MAX_IMAGE_PIXELS = 128L * 1024 * 1024
    }

    private val directory = File(context.filesDir, "offline-library")
    private val progress = context.getSharedPreferences("offline_pages", Context.MODE_PRIVATE)
    private val downloads = context.getSharedPreferences("offline_chapters", Context.MODE_PRIVATE)

    data class DownloadedChapter(val series: MangaItem, val chapter: ProviderChapters.Chapter, val last: Boolean)

    fun books(): List<File> = directory.listFiles { file -> file.isFile && file.extension == "cbz" }
        .orEmpty().sortedBy { it.name.lowercase(Locale.ROOT) }

    fun importBook(uri: Uri): File {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        val title = name.orEmpty().substringBeforeLast('.')
            .replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").trim(' ', '.').take(80).ifBlank { "Comic" }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Library unavailable")
        val temporary = File.createTempFile("import-", ".partial", directory)
        try {
            val input = resolver.openInputStream(uri) ?: throw IOException("Could not open archive")
            input.use { source ->
                temporary.outputStream().use { destination ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw IOException("Import interrupted")
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_ARCHIVE_BYTES) throw IOException("Archive exceeds the size limit")
                        destination.write(buffer, 0, count)
                    }
                }
            }
            ZipFile(temporary).use { archive ->
                for (page in OfflineArchive.pages(archive)) {
                    validatedImageBounds(OfflineArchive.readPage(archive, page), page)
                }
            }
            var file = File(directory, "$title.cbz")
            var suffix = 2
            while (file.exists()) file = File(directory, "$title ($suffix).cbz").also { suffix++ }
            if (!temporary.renameTo(file)) throw IOException("Could not save archive")
            return file
        } finally {
            temporary.delete()
        }
    }

    fun decodePage(archive: ZipFile, page: ZipEntry): Bitmap? {
        val bytes = OfflineArchive.readPage(archive, page)
        return decodeImage(bytes, OfflineArchive.expectedMime(page))
    }

    fun decodeImage(bytes: ByteArray, expectedMime: String?, maxWidth: Int = 2048, maxHeight: Int = 4096): Bitmap? {
        val options = validatedImageBounds(bytes, expectedMime)
        var sample = 1
        while (options.outWidth / sample > maxWidth || options.outHeight / sample > maxHeight) sample *= 2
        options.inJustDecodeBounds = false
        options.inSampleSize = sample
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun validatedImageBounds(bytes: ByteArray, page: ZipEntry): BitmapFactory.Options {
        return validatedImageBounds(bytes, OfflineArchive.expectedMime(page))
    }

    private fun validatedImageBounds(bytes: ByteArray, expected: String?): BitmapFactory.Options {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outMimeType !in setOf("image/jpeg", "image/png", "image/webp") ||
            (expected != null && bounds.outMimeType != expected) ||
            bounds.outWidth !in 1..20000 || bounds.outHeight !in 1..60000 ||
            bounds.outWidth.toLong() * bounds.outHeight > MAX_IMAGE_PIXELS) {
            throw OfflineArchive.Rejected("Unsupported, mislabeled or oversized image")
        }
        return bounds
    }

    fun chapterBook(series: MangaItem, chapter: String): File {
        val canonical = ProviderChapters.chapterUrl(series, chapter) ?: throw IOException("Invalid chapter")
        val digest = MessageDigest.getInstance("SHA-256").digest((series.id + canonical.encodedPath).toByteArray())
            .take(8).joinToString("") { "%02x".format(it.toInt() and 255) }
        val name = (series.title + " - " + canonical.pathSegments.last())
            .replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").trim(' ', '.').take(96)
        return File(directory, "$name [$digest].cbz")
    }

    suspend fun saveChapter(
        series: MangaItem, chapter: String, pages: List<String>,
        progress: suspend (Int) -> Unit
    ): File {
        val book = chapterBook(series, chapter)
        if (book.exists()) return book
        if (pages.size !in 1..1000) throw IOException("No downloadable pages")
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Library unavailable")
        }
        val temporary = File.createTempFile("chapter-", ".partial", directory)
        try {
            var total = 0L
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                pages.forEachIndexed { index, url ->
                    currentCoroutineContext().ensureActive()
                    val image = runInterruptible { ProviderChapters.image(url, chapter) }
                    val mime = validatedImageBounds(image.bytes, null).outMimeType
                    val extension = when (mime) {
                        "image/jpeg" -> "jpg"
                        "image/png" -> "png"
                        else -> "webp"
                    }
                    total += image.bytes.size
                    if (total > MAX_ARCHIVE_BYTES) throw IOException("Archive exceeds the size limit")
                    zip.putNextEntry(ZipEntry("${index + 1}.$extension"))
                    zip.write(image.bytes)
                    zip.closeEntry()
                    progress((index + 1) * 99 / pages.size)
                }
            }
            if (temporary.length() > MAX_ARCHIVE_BYTES) throw IOException("Download could not be saved")
            ZipFile(temporary).use { OfflineArchive.pages(it) }
            currentCoroutineContext().ensureActive()
            if (!temporary.renameTo(book)) throw IOException("Could not save chapter")
            return book
        } finally {
            temporary.delete()
        }
    }

    fun pageFor(book: File): Int = progress.getInt(book.name, 0)
    fun rememberedPage(book: File): Int? = pageFor(book).takeIf { progress.contains(book.name) }

    fun rememberChapter(book: File, series: MangaItem, chapter: ProviderChapters.Chapter, last: Boolean) {
        val record = JSONObject().put("series", series.id).put("url", chapter.url)
            .put("label", chapter.title).put("last", last)
        downloads.edit().putString(book.name, record.toString()).apply()
    }

    fun downloadedChapter(book: File): DownloadedChapter? {
        val stored = downloads.getString(book.name, null) ?: return null
        val record = try { JSONObject(stored) } catch (_: org.json.JSONException) { return null }
        val series = HomeFragment.catalogue.firstOrNull { it.id == record.optString("series") } ?: return null
        val url = ProviderChapters.chapterUrl(series, record.optString("url"))?.toString() ?: return null
        if (chapterBook(series, url).name != book.name) return null
        return DownloadedChapter(series, ProviderChapters.Chapter(record.optString("label"), url), record.optBoolean("last"))
    }

    fun remember(book: File, page: Int) {
        progress.edit().putInt(book.name, page).apply()
    }

    fun remove(book: File): Boolean {
        if (!book.delete()) return false
        progress.edit().remove(book.name).apply()
        downloads.edit().remove(book.name).apply()
        return true
    }
}
