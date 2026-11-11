package com.example.testing

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal class OfflineLibrary(private val context: Context) {
    companion object {
        private const val MAX_ARCHIVE_BYTES = 250L * 1024 * 1024
        private const val MAX_PAGE_BYTES = 24L * 1024 * 1024
        private const val MAX_ENTRIES = 2000
        private const val MAX_PAGES = 1000
        private val PAGE_TYPES = setOf("jpg", "jpeg", "png", "webp")
        private val PAGE_NUMBER = Regex("[0-9]+")
    }

    private val directory = File(context.filesDir, "offline-library")
    private val progress = context.getSharedPreferences("offline_pages", Context.MODE_PRIVATE)

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
            ZipFile(temporary).use { pages(it) }
            var file = File(directory, "$title.cbz")
            var suffix = 2
            while (file.exists()) file = File(directory, "$title ($suffix).cbz").also { suffix++ }
            if (!temporary.renameTo(file)) throw IOException("Could not save archive")
            return file
        } finally {
            temporary.delete()
        }
    }

    fun pages(archive: ZipFile): List<ZipEntry> {
        if (archive.size() > MAX_ENTRIES) throw IOException("Too many archive entries")
        val pages = archive.entries().asSequence().filter { entry ->
            !entry.isDirectory && !entry.name.startsWith("__MACOSX/") &&
                !entry.name.substringAfterLast('/').startsWith('.') &&
                entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in PAGE_TYPES
        }.toList()
        if (pages.isEmpty() || pages.size > MAX_PAGES || pages.any { it.size !in 1L..MAX_PAGE_BYTES }) {
            throw IOException("Unsupported archive contents")
        }
        // Keep paths internal to the archive and sort numbered page names naturally.
        return pages.sortedBy {
            PAGE_NUMBER.replace(it.name.lowercase(Locale.ROOT)) { number -> number.value.padStart(12, '0') }
        }
    }

    fun pageFor(book: File): Int = progress.getInt(book.name, 0)

    fun remember(book: File, page: Int) {
        progress.edit().putInt(book.name, page).apply()
    }

    fun remove(book: File): Boolean {
        if (!book.delete()) return false
        progress.edit().remove(book.name).apply()
        return true
    }
}
