package com.example.testing

import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal object OfflineArchive {
    private const val MAX_ENTRIES = 2000
    private const val MAX_PAGES = 1000
    const val MAX_PAGE_BYTES = 24L * 1024 * 1024
    private const val MAX_TOTAL_PAGE_BYTES = 512L * 1024 * 1024
    private val pageNumber = Regex("[0-9]+")
    private val imageTypes = mapOf("jpg" to "image/jpeg", "jpeg" to "image/jpeg",
        "png" to "image/png", "webp" to "image/webp")
    private val activeTypes = setOf(
        "apk", "bat", "cmd", "com", "dex", "dll", "exe", "hta", "html", "htm",
        "jar", "js", "lnk", "msi", "ps1", "scr", "sh", "svg", "vbs"
    )

    class Rejected(message: String) : IOException(message)

    fun pages(archive: ZipFile): List<ZipEntry> {
        if (archive.size() > MAX_ENTRIES) throw Rejected("Too many archive entries")
        val paths = HashSet<String>()
        val pages = ArrayList<ZipEntry>()
        var totalSize = 0L
        for (entry in archive.entries()) {
            val path = validatedPath(entry.name, entry.isDirectory)
            if (!paths.add(path)) throw Rejected("Duplicate archive path")
            if (entry.isDirectory) continue
            val extension = extension(path)
            if (extension in activeTypes) throw Rejected("Active content in archive")
            if (path.startsWith("__macosx/") || path.substringAfterLast('/').startsWith('.') ||
                extension !in imageTypes) continue
            if (entry.size !in 1L..MAX_PAGE_BYTES || entry.crc < 0) {
                throw Rejected("Invalid image entry size or checksum")
            }
            totalSize += entry.size
            if (pages.size >= MAX_PAGES || totalSize > MAX_TOTAL_PAGE_BYTES) {
                throw Rejected("Archive exceeds the expanded page limit")
            }
            pages.add(entry)
        }
        if (pages.isEmpty()) throw Rejected("No supported images in archive")
        return pages.sortedBy { entry ->
            pageNumber.replace(entry.name.lowercase(Locale.ROOT)) { number -> number.value.padStart(12, '0') }
        }
    }

    fun validatedPath(name: String, directory: Boolean): String {
        val path = if (directory) name.removeSuffix("/") else name
        if (path.isEmpty() || path.length > 1024 ||
            path.any { it == '\\' || it == ':' || it.isISOControl() ||
                Character.getType(it) == Character.FORMAT.toInt() } ||
            path.split('/').any { it.isEmpty() || it == "." || it == ".." ||
                it.endsWith('.') || it.last().isWhitespace() }) {
            throw Rejected("Unsafe archive path")
        }
        return Normalizer.normalize(path, Normalizer.Form.NFC).lowercase(Locale.ROOT)
    }

    fun readPage(archive: ZipFile, page: ZipEntry): ByteArray {
        if (page.size !in 1L..MAX_PAGE_BYTES || page.crc < 0) {
            throw Rejected("Invalid image entry size or checksum")
        }
        val bytes = ByteArray(page.size.toInt())
        archive.getInputStream(page).use { input ->
            var offset = 0
            while (offset < bytes.size) {
                if (Thread.currentThread().isInterrupted) throw IOException("Reading interrupted")
                val count = input.read(bytes, offset, minOf(8192, bytes.size - offset))
                if (count <= 0) throw Rejected("Truncated image entry")
                offset += count
            }
            if (input.read() != -1) throw Rejected("Image entry exceeds its declared size")
        }
        val actualCrc = CRC32().apply { update(bytes) }.value
        if (actualCrc != page.crc) throw Rejected("Image entry checksum mismatch")
        return bytes
    }

    fun expectedMime(page: ZipEntry): String? = imageTypes[extension(page.name)]

    private fun extension(name: String): String =
        name.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)
}
